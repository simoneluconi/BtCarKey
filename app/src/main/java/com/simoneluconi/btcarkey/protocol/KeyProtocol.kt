package com.simoneluconi.btcarkey.protocol

import com.simoneluconi.btcarkey.auth.AuthOutcome
import com.simoneluconi.btcarkey.auth.AuthStep
import com.simoneluconi.btcarkey.auth.KeyVault
import java.io.IOException
import java.util.UUID

/**
 * A kind of car key/box the app can talk to. Everything above this interface (connection
 * lifecycle, key storage, UI) is protocol-agnostic; an implementation knows how its devices
 * advertise, which GATT characteristics they use, how they authenticate and how commands are encoded.
 *
 * To add a protocol: implement this and [ProtocolSession], then list it in [Protocols.all].
 */
interface KeyProtocol {
    /** Stable id, stored with each saved device (never change it once released). */
    val id: String

    /** Shown to the driver, e.g. "FTK (redriverbear)". */
    val displayName: String

    val gatt: GattProfile

    /** Advertising filters for scans; they run in the Bluetooth controller. */
    val scanFilters: List<AdFilter>

    /** Length in bytes of the device key the app stores for this protocol. */
    val keySize: Int

    val commands: Set<CarCommand>
    val buttons: Set<RemoteButton>
    val features: Set<Feature>
    val rawConfigs: Set<RawConfigKind>

    /** The device's serial when [advert] belongs to this protocol, null otherwise. */
    fun identify(advert: Advertisement): String?

    /** A session for one connection, created once the GATT link is ready. */
    fun newSession(context: SessionContext): ProtocolSession
}

/** What a protocol can do besides the one-tap [KeyProtocol.commands]. */
enum class Feature {
    /** The remote's buttons pressed and released on demand ([KeyProtocol.buttons]). */
    HOLD_BUTTONS,
    /** Lock/unlock by the phone's distance, done by the device ([AutoLockConfig]). */
    PROXIMITY,
    /** The phone's signal as the device measures it. */
    RSSI,
    NFC,
    /** Licence plate stored on the device. */
    PLATE,
    /** Battery level and similar status. */
    BATTERY,
    /** Opaque vendor configuration blobs ([KeyProtocol.rawConfigs]). */
    RAW_CONFIG,
    /** Clear the device's key and set a new one from an authenticated session. */
    REPAIR,
}

/** Opaque vendor settings a device may accept. */
enum class RawConfigKind { LOCK, UNLOCK, REMOTE_START, CAR_TYPE }

/** The GATT layout a protocol uses; write and notify may be the same characteristic. */
data class GattProfile(
    val service: UUID,
    val write: UUID,
    val notify: UUID,
    /** MTU to request after connecting; 0 keeps the default. */
    val mtu: Int,
)

/** A hardware scan filter. */
sealed interface AdFilter {
    /** Any Manufacturer Specific Data with this company id. */
    data class Manufacturer(val companyId: Int) : AdFilter

    data class Service(val uuid: UUID) : AdFilter
}

/** The parts of an advertisement protocols look at (plain data, testable on the JVM). */
data class Advertisement(
    val manufacturerData: Map<Int, ByteArray> = emptyMap(),
    val serviceUuids: List<UUID> = emptyList(),
    val name: String? = null,
)

/** What a session gets from the app. */
class SessionContext(
    /** Serial of the saved device: keys are filed under it. */
    val serial: String,
    val vault: KeyVault,
    /** Sends bytes to [GattProfile.write]; longer than the MTU is split by the transport. */
    val write: (ByteArray) -> Unit,
    val log: (String) -> Unit,
    /** Whether a device with no key may be paired (only devices the driver added). */
    val pairingAllowed: () -> Boolean,
    val onStep: (AuthStep) -> Unit,
    /** Status the device reported (merged by the app). */
    val onStatus: (BoxStatus) -> Unit,
    /** The phone's clock and locale, for devices that show or log them. */
    val phone: () -> PhoneInfo,
)

data class PhoneInfo(val epochSeconds: Long, val utcOffsetSeconds: Int, val is24h: Boolean, val language: String)

/**
 * One connection to one device. Methods suspend until the device answers and throw [IOException]
 * on failure: [NoAnswerException], [LinkClosedException], [UnsupportedFeatureException], or a
 * plain IOException with the device's reason.
 */
interface ProtocolSession {
    /** Bytes notified on [GattProfile.notify]. */
    fun onData(bytes: ByteArray)

    /** The link is gone: fail whatever waits. */
    fun close()

    /** Proves a stored key, or pairs a new one when the device has none. */
    suspend fun authenticate(): AuthOutcome

    /** Clears the device's key and sets a new one ([Feature.REPAIR]). */
    suspend fun repair(): AuthOutcome = throw UnsupportedFeatureException("re-pairing")

    /** Right after authenticating: clock sync, first status read... */
    suspend fun start() {}

    suspend fun refreshStatus() {}

    suspend fun command(command: CarCommand): CommandAck

    suspend fun press(button: RemoteButton, down: Boolean): CommandAck = throw UnsupportedFeatureException("remote buttons")

    /** The phone's signal at the device, as a positive number (-dBm). */
    suspend fun readRssi(): Int = throw UnsupportedFeatureException("signal reading")

    suspend fun readAutoLock(): AutoLockConfig = throw UnsupportedFeatureException("comfort entry")

    suspend fun writeAutoLock(config: AutoLockConfig): Unit = throw UnsupportedFeatureException("comfort entry")

    suspend fun registerNfc(seconds: Int): Unit = throw UnsupportedFeatureException("NFC")

    suspend fun setPlate(plate: String): Unit = throw UnsupportedFeatureException("licence plate")

    suspend fun writeRawConfig(kind: RawConfigKind, bytes: ByteArray): Unit = throw UnsupportedFeatureException("vendor configuration")
}

/** How the device took a command. */
sealed interface CommandAck {
    data object Done : CommandAck

    /** Sent, no answer: it may well have worked. */
    data object Unconfirmed : CommandAck

    data class Refused(val reason: String) : CommandAck
}

/** No answer within the timeout. */
class NoAnswerException(message: String) : IOException(message)

/** The link went down while a request waited. */
class LinkClosedException : IOException("Disconnected")

class UnsupportedFeatureException(what: String) : IOException("This device doesn't support $what")
