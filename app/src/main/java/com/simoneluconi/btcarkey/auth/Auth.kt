package com.simoneluconi.btcarkey.auth

import com.simoneluconi.btcarkey.protocol.DeviceInfo
import com.simoneluconi.btcarkey.protocol.toHex
import java.security.MessageDigest

/** Where device keys live; one active key per serial plus a pending one while a pairing is in flight. */
interface KeyVault {
    /** Keys to try, best first: the active key, then a pending key whose pairing wasn't confirmed. */
    fun candidates(serial: String): List<StoredKey>

    /** Remembers [key] before it is sent, so a lost answer can't lose the key the device now holds. */
    fun savePending(serial: String, key: ByteArray)

    /** [key] is what the device holds now: it becomes the active key; the previous one is kept as a backup. */
    fun confirm(serial: String, key: ByteArray)
}

data class StoredKey(val bytes: ByteArray, val pending: Boolean)

/** Progress shown to the driver while authenticating. */
enum class AuthStep { HELLO, VERIFYING, PAIRING, UNBINDING }

sealed interface AuthOutcome {
    val info: DeviceInfo?

    /** Proved a stored key (reuse). */
    data class Verified(override val info: DeviceInfo, val usedPendingKey: Boolean) : AuthOutcome

    /** The device had no key: a new one was generated, set and saved. */
    data class Paired(override val info: DeviceInfo) : AuthOutcome

    /** The device has a key but none of ours is stored for this serial: import it, or reset the device. */
    data class MissingKey(override val info: DeviceInfo) : AuthOutcome

    /** The device refused every stored key. */
    data class Rejected(override val info: DeviceInfo, val reason: String) : AuthOutcome

    /** The device has no key, but the driver didn't choose it: nothing is written to it. */
    data class PairingNotAllowed(override val info: DeviceInfo) : AuthOutcome

    /** The device refused the new key. */
    data class PairingFailed(override val info: DeviceInfo, val reason: String) : AuthOutcome

    /** Unexpected answer (protocol error). */
    data class Failed(override val info: DeviceInfo?, val reason: String) : AuthOutcome

    val isAuthenticated: Boolean get() = this is Verified || this is Paired
}

object KeyFingerprint {
    /** A short, non-reversible tag to tell keys apart in logs and on screen: first 4 bytes of SHA-256. */
    fun of(key: ByteArray): String = "#" + MessageDigest.getInstance("SHA-256").digest(key).copyOfRange(0, 4).toHex()
}
