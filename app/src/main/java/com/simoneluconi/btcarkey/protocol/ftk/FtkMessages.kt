package com.simoneluconi.btcarkey.protocol.ftk

import com.simoneluconi.btcarkey.protocol.AutoLockConfig
import com.simoneluconi.btcarkey.protocol.BoxStatus
import com.simoneluconi.btcarkey.protocol.DeviceInfo
import com.simoneluconi.btcarkey.protocol.toHex
import com.simoneluconi.btcarkey.protocol.toSerial
import com.simoneluconi.btcarkey.protocol.u16
import com.simoneluconi.btcarkey.protocol.u16Bytes
import com.simoneluconi.btcarkey.protocol.u32
import com.simoneluconi.btcarkey.protocol.u32Bytes
import com.simoneluconi.btcarkey.protocol.u8
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** The box's answer to AUTH (cmd 6 / key 0). */
data class AuthInfo(
    /** 0: no key set (new box or after UNBIND), 1: a key is set and must be proved. */
    val bindState: Int,
    val serial: String,
    val number: Long,
    /** 1 = CAR_KEY, 2 = CAR_BOX. */
    val deviceType: Long,
    /** 1 = ATK, 2 = NORDIC, 3 = REALTEK. */
    val platform: Long,
    val firmware: String,
    /** 16-byte challenge for VERIFY_DEVICE_KEY. */
    val challenge: ByteArray,
) {
    val isBound: Boolean get() = bindState == 1

    val deviceTypeName: String get() = when (deviceType) { 1L -> "CAR_KEY"; 2L -> "CAR_BOX"; else -> "type $deviceType" }
    val platformName: String get() = when (platform) { 1L -> "ATK"; 2L -> "NORDIC"; 3L -> "REALTEK"; else -> "platform $platform" }

    fun toDeviceInfo() = DeviceInfo(serial = serial, firmware = firmware, model = "$deviceTypeName · $platformName")

    override fun toString(): String =
        "bindState=$bindState serial=$serial number=$number $deviceTypeName $platformName fw=$firmware"

    companion object {
        private const val MIN_SIZE = 41

        fun parse(payload: ByteArray): AuthInfo {
            require(payload.size >= MIN_SIZE) { "AUTH answer has ${payload.size} bytes, expected at least $MIN_SIZE" }
            return AuthInfo(
                bindState = payload.u8(0),
                serial = payload.toSerial(1),
                number = payload.u32(7),
                deviceType = payload.u32(11),
                platform = payload.u32(15),
                firmware = "${payload.u16(19)}.${payload.u16(21)}.${payload.u16(23)}",
                challenge = payload.copyOfRange(25, 41),
            )
        }
    }
}

/** The 16-byte device key and the proof the box asks for. */
object DeviceKeyCrypto {
    const val KEY_SIZE = 16

    /** VERIFY_DEVICE_KEY payload: AES-128-ECB(key = deviceKey, plaintext = challenge). */
    fun answerChallenge(deviceKey: ByteArray, challenge: ByteArray): ByteArray {
        require(deviceKey.size == KEY_SIZE && challenge.size == KEY_SIZE)
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(deviceKey, "AES"))
        return cipher.doFinal(challenge)
    }

    /** A fresh key for a new pairing: any 16 bytes work, the box just stores them. */
    fun newKey(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(KEY_SIZE).also { random.nextBytes(it) }
}

/** Tag-length-value list used by INFO_SYNC: tag (1 byte) | length (1 byte) | value (little-endian). */
object Tlv {
    const val TIMESTAMP = 1
    const val TIME_FORMAT = 2
    const val TIME_ZONE = 3
    const val LANGUAGE = 4
    /** Licence plate shown by the box/key (string). */
    const val CAR_NUMBER = 13
    const val PHONE_TYPE = 19
    const val BATTERY = 23
    const val FW_MAJOR = 24
    const val FW_MIDDLE = 25
    const val FW_MINOR = 26
    const val DEVICE_TYPE = 27
    const val PLATFORM = 28
    const val NUMBER = 29
    const val BOND_STATE = 30
    const val CPU_ID = 31
    const val VOLTAGE = 32
    const val CHARGING = 33
    const val POWER_STATE = 34

    const val PHONE_ANDROID = 1

    fun encode(vararg items: Pair<Int, ByteArray>): ByteArray =
        items.fold(ByteArray(0)) { acc, (tag, value) -> acc + byteArrayOf(tag.toByte(), value.size.toByte()) + value }

    /** Tags in order; a truncated last item is dropped. */
    fun decode(payload: ByteArray): List<Pair<Int, ByteArray>> {
        val out = mutableListOf<Pair<Int, ByteArray>>()
        var i = 0
        while (i + 2 <= payload.size) {
            val tag = payload.u8(i)
            val length = payload.u8(i + 1)
            if (i + 2 + length > payload.size) break
            out += tag to payload.copyOfRange(i + 2, i + 2 + length)
            i += 2 + length
        }
        return out
    }

    /** What the app sends after authenticating, like the vendor app: clock, zone, language, phone type. */
    fun phoneSync(epochSeconds: Long, utcOffsetSeconds: Int, is24h: Boolean, language: String): ByteArray = encode(
        TIMESTAMP to u32Bytes(epochSeconds),
        TIME_FORMAT to byteArrayOf(if (is24h) 1 else 0),
        TIME_ZONE to u32Bytes(utcOffsetSeconds.toLong()),
        LANGUAGE to language.toByteArray(Charsets.UTF_8),
        PHONE_TYPE to byteArrayOf(PHONE_ANDROID.toByte()),
    )

    /** Box status from INFO_SYNC / INFO_REPORT TLVs. */
    fun status(payload: ByteArray): BoxStatus {
        var status = BoxStatus()
        val fw = arrayOfNulls<Int>(3)
        val unknown = mutableMapOf<Int, String>()
        for ((tag, value) in decode(payload)) {
            if (value.isEmpty()) continue
            when (tag) {
                BATTERY -> status = status.copy(batteryPercent = value.u8(0))
                VOLTAGE -> if (value.size >= 2) status = status.copy(voltageMv = value.u16(0))
                CHARGING -> status = status.copy(charging = value.u8(0) != 0)
                // Version parts are uint16, though a 1-byte value is read too
                FW_MAJOR -> fw[0] = value.uIntLe()
                FW_MIDDLE -> fw[1] = value.uIntLe()
                FW_MINOR -> fw[2] = value.uIntLe()
                else -> unknown[tag] = value.toHex()
            }
        }
        if (fw.all { it != null }) status = status.copy(firmware = fw.joinToString("."))
        return status.copy(unknown = unknown)
    }

    private fun ByteArray.uIntLe(): Int = if (size >= 2) u16(0) else u8(0)
}

/** SYSTEM_CFG / AUTO_LOCK payloads. */
object AutoLockCodec {
    /** en(1) lock(2) unlock(2). */
    fun encode(config: AutoLockConfig): ByteArray =
        byteArrayOf(if (config.enabled) 1 else 0) + u16Bytes(config.lockDbm) + u16Bytes(config.unlockDbm)

    /** Answer to AUTO_LOCK_READ: en(1) lock(2) unlock(2) [err(4)]. */
    fun parse(payload: ByteArray): AutoLockConfig {
        require(payload.size >= 5) { "AUTO_LOCK answer has ${payload.size} bytes, expected at least 5" }
        return AutoLockConfig(payload.u8(0) != 0, payload.u16(1), payload.u16(3))
    }
}
