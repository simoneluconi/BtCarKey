package com.simoneluconi.btcarkey.protocol.ftk

import com.simoneluconi.btcarkey.auth.AuthOutcome
import com.simoneluconi.btcarkey.protocol.AdFilter
import com.simoneluconi.btcarkey.protocol.Advertisement
import com.simoneluconi.btcarkey.protocol.AutoLockConfig
import com.simoneluconi.btcarkey.protocol.CarCommand
import com.simoneluconi.btcarkey.protocol.CommandAck
import com.simoneluconi.btcarkey.protocol.Feature
import com.simoneluconi.btcarkey.protocol.GattProfile
import com.simoneluconi.btcarkey.protocol.KeyProtocol
import com.simoneluconi.btcarkey.protocol.NoAnswerException
import com.simoneluconi.btcarkey.protocol.ProtocolSession
import com.simoneluconi.btcarkey.protocol.RawConfigKind
import com.simoneluconi.btcarkey.protocol.RemoteButton
import com.simoneluconi.btcarkey.protocol.SessionContext
import com.simoneluconi.btcarkey.protocol.toSerial
import com.simoneluconi.btcarkey.protocol.u16Bytes
import java.io.IOException

/**
 * The boxes sold with the "redriverbear" app (FTK in its code): manufacturer data 0xF715 carrying
 * the serial, one write/notify characteristic, 16-byte framed messages, AES-128 challenge with a
 * 16-byte device key. Full description in PROTOCOL.md.
 */
object FtkProtocol : KeyProtocol {
    const val ID = "ftk"

    override val id = ID
    override val displayName = "FTK (redriverbear)"
    override val gatt = GattProfile(service = FtkGatt.SERVICE, write = FtkGatt.DATA, notify = FtkGatt.DATA, mtu = FtkGatt.MTU)
    override val scanFilters = listOf(AdFilter.Manufacturer(FtkGatt.COMPANY_ID))
    override val keySize = DeviceKeyCrypto.KEY_SIZE
    override val commands = CarCommand.entries.toSet()
    override val buttons = RemoteButton.entries.toSet()
    override val features = Feature.entries.toSet()
    override val rawConfigs = RawConfigKind.entries.toSet()

    override fun identify(advert: Advertisement): String? {
        val data = advert.manufacturerData[FtkGatt.COMPANY_ID] ?: return null
        return if (data.size >= 6) data.toSerial(0) else null
    }

    override fun newSession(context: SessionContext): ProtocolSession = FtkSession(context)
}

/** One connection to an FTK box. */
class FtkSession(
    private val context: SessionContext,
    /** Replaceable in tests, to avoid random keys. */
    private val newKey: () -> ByteArray = { DeviceKeyCrypto.newKey() },
) : ProtocolSession {
    private val frames = FrameSession(write = context.write, log = context.log, onUnsolicited = ::onReport)
    private val log = context.log

    private fun authenticator(pairingAllowed: () -> Boolean) = FtkAuthenticator(
        serial = context.serial,
        channel = frames,
        vault = context.vault,
        log = log,
        pairingAllowed = pairingAllowed,
        newKey = newKey,
        onStep = context.onStep,
    )

    override fun onData(bytes: ByteArray) = frames.receive(bytes)

    override fun close() = frames.close()

    override suspend fun authenticate(): AuthOutcome = authenticator(context.pairingAllowed).authenticate()

    override suspend fun repair(): AuthOutcome = authenticator { true }.repair()

    /** Clock, time zone and language to the box, like the vendor app; the answer may carry the status. */
    override suspend fun start() = refreshStatus()

    override suspend fun refreshStatus() {
        val phone = context.phone()
        val payload = Tlv.phoneSync(phone.epochSeconds, phone.utcOffsetSeconds, phone.is24h, phone.language)
        try {
            report(frames.request(Cmd.INFO_SYNC, 0, payload, SHORT_TIMEOUT_MS).payload)
        } catch (e: NoAnswerException) {
            log("INFO_SYNC: no answer, status will come from the box's reports")
        }
    }

    override suspend fun command(command: CarCommand): CommandAck = try {
        val answer = frames.request(Cmd.SYSTEM_CTRL, CtrlKey.of(command), ByteArray(0), SHORT_TIMEOUT_MS)
        if (answer.payload.size >= 4 && answer.errorCode != ResultCode.OK) {
            CommandAck.Refused(ResultCode.describe(answer.errorCode))
        } else {
            CommandAck.Done
        }
    } catch (e: NoAnswerException) {
        log("Command: $command sent, no answer from the box")
        CommandAck.Unconfirmed
    }

    override suspend fun press(button: RemoteButton, down: Boolean): CommandAck = try {
        frames.request(Cmd.SYSTEM_CTRL, CtrlKey.of(button), byteArrayOf(if (down) 0 else 1), SHORT_TIMEOUT_MS)
        CommandAck.Done
    } catch (e: NoAnswerException) {
        log("Hold $button ${if (down) "down" else "up"}: no answer")
        CommandAck.Unconfirmed
    }

    override suspend fun readRssi(): Int {
        val answer = frames.request(Cmd.SYSTEM_CTRL, CtrlKey.RSSI, ByteArray(0), SHORT_TIMEOUT_MS)
        if (answer.payload.isEmpty()) throw IOException("Empty RSSI answer")
        return answer.payload[0].toInt().let { if (it < 0) -it else it }
    }

    override suspend fun readAutoLock(): AutoLockConfig {
        val answer = frames.request(Cmd.SYSTEM_CFG, CfgKey.AUTO_LOCK_READ, ByteArray(0), SHORT_TIMEOUT_MS)
        if (answer.payload.size >= 9 && answer.payload.copyOfRange(5, 9).any { it.toInt() != 0 }) {
            throw IOException("The box refused to read the settings")
        }
        return AutoLockCodec.parse(answer.payload).also { log("Comfort entry: $it") }
    }

    override suspend fun writeAutoLock(config: AutoLockConfig) {
        log("Comfort entry: writing $config")
        expectOk(frames.request(Cmd.SYSTEM_CFG, CfgKey.AUTO_LOCK, AutoLockCodec.encode(config), SHORT_TIMEOUT_MS))
    }

    override suspend fun registerNfc(seconds: Int) {
        log("NFC: learning a card for $seconds s")
        expectOk(frames.request(Cmd.SYSTEM_CTRL, CtrlKey.NFC_REGISTER, u16Bytes(seconds), (seconds + 5) * 1000L))
    }

    override suspend fun setPlate(plate: String) {
        log("Plate: \"$plate\"")
        frames.request(Cmd.INFO_SYNC, 0, Tlv.encode(Tlv.CAR_NUMBER to plate.toByteArray(Charsets.UTF_8)), SHORT_TIMEOUT_MS)
    }

    override suspend fun writeRawConfig(kind: RawConfigKind, bytes: ByteArray) {
        log("Config $kind: writing ${bytes.size} bytes")
        expectOk(frames.request(Cmd.SYSTEM_CFG, CfgKey.of(kind), bytes, SHORT_TIMEOUT_MS))
    }

    private fun expectOk(answer: Frame) {
        if (answer.payload.size >= 4 && answer.errorCode != ResultCode.OK) {
            throw IOException("Refused (${ResultCode.describe(answer.errorCode)})")
        }
    }

    /** INFO_SYNC / INFO_REPORT frames the box sends on its own. */
    private fun onReport(frame: Frame) {
        if (frame.cmd == Cmd.INFO_SYNC || frame.cmd == Cmd.INFO_REPORT) report(frame.payload)
    }

    private fun report(payload: ByteArray) {
        if (payload.isEmpty()) return
        val status = Tlv.status(payload)
        if (status.isEmpty) return
        log("Status: battery=${status.batteryPercent ?: "-"}% voltage=${status.voltageMv ?: "-"} mV" +
            (if (status.unknown.isEmpty()) "" else " other=${status.unknown}"))
        context.onStatus(status)
    }

    private companion object {
        const val SHORT_TIMEOUT_MS = 4_000L
    }
}
