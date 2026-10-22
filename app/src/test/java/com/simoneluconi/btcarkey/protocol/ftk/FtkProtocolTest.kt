package com.simoneluconi.btcarkey.protocol.ftk

import com.simoneluconi.btcarkey.protocol.Advertisement
import com.simoneluconi.btcarkey.protocol.AutoLockConfig
import com.simoneluconi.btcarkey.protocol.BoxStatus
import com.simoneluconi.btcarkey.protocol.CarCommand
import com.simoneluconi.btcarkey.protocol.hexToBytesOrNull
import com.simoneluconi.btcarkey.protocol.toHex
import com.simoneluconi.btcarkey.protocol.u16Bytes
import com.simoneluconi.btcarkey.protocol.u32Bytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wire format of the FTK protocol: frames, messages, advertising. */
class FtkProtocolTest {

    @Test
    fun `AUTH and UNLOCK frames match the captured bytes`() {
        assertEquals("55aa0000000006000000000000000501", Frame.encode(0, Cmd.AUTH, 0).toHex())
        assertEquals("55aa0000010004006400000000006801", Frame.encode(1, Cmd.SYSTEM_CTRL, CtrlKey.of(CarCommand.UNLOCK)).toHex())
    }

    @Test
    fun `payload checksum is the byte sum`() {
        val frame = Frame.encode(7, Cmd.SYSTEM_CFG, 0, byteArrayOf(1, 0xFF.toByte(), 0x80.toByte()))
        assertEquals(0x180, (frame[12].toInt() and 0xFF) or ((frame[13].toInt() and 0xFF) shl 8))
        assertTrue(Frame.decode(frame).checksumsOk)
    }

    @Test
    fun `assembler joins split frames and skips garbage`() {
        val a = FakeBox.deviceFrame(3, Cmd.AUTH, 2, u32Bytes(0), Frame.TYPE_RESPONSE)
        val b = FakeBox.deviceFrame(4, Cmd.INFO_SYNC, 0, ByteArray(40) { it.toByte() }, Frame.TYPE_REPORT)
        val stream = byteArrayOf(0x01, 0x02) + a + b
        val discarded = mutableListOf<Int>()
        val assembler = FrameAssembler { discarded += it.size }
        val frames = stream.toList().chunked(7).flatMap { assembler.push(it.toByteArray()) }
        assertEquals(listOf(3, 4), frames.map { it.seq })
        assertEquals(40, frames[1].payload.size)
        assertTrue(frames.all { it.checksumsOk })
        assertEquals(2, discarded.sum())
    }

    @Test
    fun `assembler keeps a magic split across notifications`() {
        val frame = FakeBox.deviceFrame(9, Cmd.PING, 0, ByteArray(0), Frame.TYPE_RESPONSE)
        val assembler = FrameAssembler()
        assertTrue(assembler.push(byteArrayOf(0x00, frame[0])).isEmpty())
        assertEquals(9, assembler.push(frame.copyOfRange(1, frame.size)).single().seq)
    }

    @Test
    fun `AUTH answer is parsed`() {
        val challenge = ByteArray(16) { (it * 3).toByte() }
        val payload = byteArrayOf(1, 0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 0xD4.toByte(), 0xE5.toByte(), 0xF6.toByte()) +
            u32Bytes(6) + u32Bytes(2) + u32Bytes(2) + u16Bytes(1) + u16Bytes(4) + u16Bytes(12) + challenge + u16Bytes(7) + u16Bytes(8)
        val info = AuthInfo.parse(payload)
        assertTrue(info.isBound)
        assertEquals("A1:B2:C3:D4:E5:F6", info.serial)
        assertEquals("CAR_BOX", info.deviceTypeName)
        assertEquals("NORDIC", info.platformName)
        assertEquals("1.4.12", info.firmware)
        assertArrayEquals(challenge, info.challenge)
        assertEquals("CAR_BOX · NORDIC", info.toDeviceInfo().model)
    }

    @Test
    fun `challenge answer is AES-128-ECB of the challenge`() {
        // FIPS-197 appendix C.1 vector
        val key = "000102030405060708090a0b0c0d0e0f".hexToBytesOrNull()!!
        val plain = "00112233445566778899aabbccddeeff".hexToBytesOrNull()!!
        assertEquals("69c4e0d86a7b0430d8cdb78070b4c55a", DeviceKeyCrypto.answerChallenge(key, plain).toHex())
    }

    @Test
    fun `status TLVs are read and unknown tags kept`() {
        val payload = Tlv.encode(
            Tlv.BATTERY to byteArrayOf(55),
            Tlv.VOLTAGE to u16Bytes(2950),
            Tlv.FW_MAJOR to u16Bytes(1), Tlv.FW_MIDDLE to u16Bytes(0), Tlv.FW_MINOR to u16Bytes(9),
            99 to byteArrayOf(1, 2),
        ) + byteArrayOf(23, 5, 1) // a truncated item at the end is ignored
        val status = Tlv.status(payload)
        assertEquals(55, status.batteryPercent)
        assertEquals(2950, status.voltageMv)
        assertEquals("1.0.9", status.firmware)
        assertEquals(mapOf(99 to "0102"), status.unknown)
        assertNull(status.charging)
        assertEquals(55, BoxStatus(batteryPercent = 10).merge(status).batteryPercent)
        assertEquals(10, status.merge(BoxStatus(batteryPercent = 10)).batteryPercent)
    }

    @Test
    fun `phone sync carries the time zone in seconds`() {
        val tlv = Tlv.decode(Tlv.phoneSync(1_700_000_000, 7200, true, "it")).toMap()
        assertEquals("00f15365", tlv[Tlv.TIMESTAMP]!!.toHex())
        assertEquals("201c0000", tlv[Tlv.TIME_ZONE]!!.toHex())
        assertEquals("it", String(tlv[Tlv.LANGUAGE]!!))
        assertEquals("01", tlv[Tlv.PHONE_TYPE]!!.toHex())
    }

    @Test
    fun `auto lock config round trip`() {
        val config = AutoLockConfig(enabled = true, lockDbm = 80, unlockDbm = 60)
        assertEquals("0150003c00", AutoLockCodec.encode(config).toHex())
        assertEquals(config, AutoLockCodec.parse(AutoLockCodec.encode(config) + u32Bytes(0)))
    }

    @Test
    fun `advertising with company 0xF715 is an FTK box`() {
        val serial = byteArrayOf(0x5A, 0x11, 0x4D, 0, 0, 1)
        assertEquals("5A:11:4D:00:00:01", FtkProtocol.identify(Advertisement(manufacturerData = mapOf(0xF715 to serial))))
        // Too short, or another company: not ours
        assertNull(FtkProtocol.identify(Advertisement(manufacturerData = mapOf(0xF715 to byteArrayOf(1, 2)))))
        assertNull(FtkProtocol.identify(Advertisement(manufacturerData = mapOf(0x004C to serial))))
    }
}
