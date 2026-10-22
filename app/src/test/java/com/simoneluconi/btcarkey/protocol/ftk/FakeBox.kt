package com.simoneluconi.btcarkey.protocol.ftk

import com.simoneluconi.btcarkey.protocol.putU16
import com.simoneluconi.btcarkey.protocol.sum16
import com.simoneluconi.btcarkey.protocol.u16Bytes
import com.simoneluconi.btcarkey.protocol.u32Bytes
import kotlin.random.Random

/**
 * A box in software, speaking the wire protocol: what the ESP32 simulator does, for JVM tests.
 * Feed it the app's bytes with [receive]; its answers go to [send], split in [chunk]-byte notifications.
 */
class FakeBox(
    var send: (ByteArray) -> Unit = {},
    var deviceKey: ByteArray? = null,
    private val serial: ByteArray = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66),
    private val chunk: Int = 20,
    private val random: Random = Random(1),
) {
    /** When false the box swallows SYSTEM_CTRL requests without answering. */
    var answersCommands = true
    /** Drops the answer to the next SET_DEVICE_KEY (after storing the key): a lost notification. */
    var loseNextSetAnswer = false
    var authenticated = false
        private set
    val commands = mutableListOf<Int>()
    /** (SYSTEM_CTRL key, 1-byte payload) of hold-button frames: 0 pressed, 1 released. */
    val presses = mutableListOf<Pair<Int, Int>>()
    val rawConfigs = mutableListOf<Pair<Int, ByteArray>>()
    val infoSyncs = mutableListOf<ByteArray>()
    /** The phone's signal as the box reports it (positive, -dBm). */
    var rssi = 58
    var autoLock = byteArrayOf(0, 75, 0, 65, 0)
    var battery = 87

    private var challenge = ByteArray(16)
    private val assembler = FrameAssembler()

    fun receive(bytes: ByteArray) {
        for (frame in assembler.push(bytes)) handle(frame)
    }

    private fun handle(f: Frame) {
        when (f.cmd) {
            Cmd.AUTH -> when (f.key) {
                AuthKey.AUTH -> {
                    authenticated = false
                    challenge = random.nextBytes(16)
                    val payload = byteArrayOf(if (deviceKey != null) 1 else 0) + serial +
                        u32Bytes(6) + u32Bytes(2) + u32Bytes(2) + u16Bytes(1) + u16Bytes(2) + u16Bytes(3) + challenge
                    answer(f, payload)
                }
                AuthKey.SET_DEVICE_KEY -> {
                    val code = if (deviceKey != null) 4L else if (f.payload.size != 16) 3L else 0L
                    if (code == 0L) {
                        deviceKey = f.payload.copyOf()
                        authenticated = true
                    }
                    if (loseNextSetAnswer) loseNextSetAnswer = false else answer(f, u32Bytes(code))
                }
                AuthKey.VERIFY_DEVICE_KEY -> {
                    val key = deviceKey
                    val ok = key != null && DeviceKeyCrypto.answerChallenge(key, challenge).contentEquals(f.payload)
                    authenticated = ok
                    answer(f, u32Bytes(if (ok) 0 else 1))
                }
                AuthKey.UNBIND -> {
                    if (authenticated) deviceKey = null
                    answer(f, u32Bytes(if (authenticated) 0 else 1))
                    authenticated = false
                }
            }
            Cmd.SYSTEM_CTRL -> {
                if (!authenticated) return answer(f, u32Bytes(1))
                commands += f.key
                if (f.payload.size == 1) presses += f.key to f.payload[0].toInt()
                when {
                    f.key == CtrlKey.RSSI -> answer(f, byteArrayOf(rssi.toByte()))
                    answersCommands -> answer(f, u32Bytes(0))
                }
            }
            Cmd.INFO_SYNC -> {
                if (f.payload.isNotEmpty()) infoSyncs += f.payload.copyOf()
                answer(f, Tlv.encode(Tlv.BATTERY to byteArrayOf(battery.toByte()), Tlv.VOLTAGE to u16Bytes(3012)))
            }
            Cmd.SYSTEM_CFG -> when (f.key) {
                CfgKey.AUTO_LOCK_READ -> answer(f, autoLock + u32Bytes(0))
                CfgKey.AUTO_LOCK -> {
                    autoLock = f.payload.copyOf()
                    answer(f, u32Bytes(0))
                }
                else -> {
                    rawConfigs += f.key to f.payload.copyOf()
                    answer(f, u32Bytes(0))
                }
            }
        }
    }

    /** Sends a frame the app didn't ask for (INFO_REPORT and the like). */
    fun report(cmd: Int, key: Int, payload: ByteArray) = emit(deviceFrame(0, cmd, key, payload, Frame.TYPE_REPORT))

    private fun answer(request: Frame, payload: ByteArray) =
        emit(deviceFrame(request.seq, request.cmd, request.key, payload, Frame.TYPE_RESPONSE))

    private fun emit(bytes: ByteArray) {
        var i = 0
        while (i < bytes.size) {
            send(bytes.copyOfRange(i, minOf(bytes.size, i + chunk)))
            i += chunk
        }
    }

    companion object {
        /** A device->app frame (magic AA 55). */
        fun deviceFrame(seq: Int, cmd: Int, key: Int, payload: ByteArray, type: Int): ByteArray {
            val out = ByteArray(Frame.HEADER_SIZE + payload.size)
            out[0] = 0xAA.toByte()
            out[1] = 0x55
            out[3] = type.toByte()
            out.putU16(4, seq)
            out.putU16(6, cmd)
            out.putU16(8, key)
            out.putU16(10, payload.size)
            out.putU16(12, payload.sum16())
            out.putU16(14, out.sum16(0, 14))
            payload.copyInto(out, Frame.HEADER_SIZE)
            return out
        }
    }
}
