package com.simoneluconi.btcarkey.protocol.ftk

import com.simoneluconi.btcarkey.auth.AuthOutcome
import com.simoneluconi.btcarkey.auth.AuthStep
import com.simoneluconi.btcarkey.auth.KeyFingerprint
import com.simoneluconi.btcarkey.auth.KeyVault
import com.simoneluconi.btcarkey.auth.Serials
import com.simoneluconi.btcarkey.protocol.toHex

/**
 * The FTK connection handshake. AUTH tells whether the box already holds a key (bindState):
 * - bound: prove a stored key with VERIFY_DEVICE_KEY = AES-128-ECB(key, challenge);
 * - unbound: generate a random key, save it as pending, send it with SET_DEVICE_KEY and make it
 *   active once the box accepts it. No PIN: the vendor app's AES(PIN, serial) is just one way
 *   to make 16 bytes, and compatibility with it is not a goal.
 *
 * Keys never reach the log: only a short fingerprint ([KeyFingerprint]).
 */
class FtkAuthenticator(
    /** Serial of the saved box we connected to: keys are filed under it. */
    private val serial: String,
    private val channel: BoxChannel,
    private val vault: KeyVault,
    private val log: (String) -> Unit,
    /** Whether the box may be paired if it has no key: only a box the driver picked, never any box in range. */
    private val pairingAllowed: () -> Boolean,
    private val newKey: () -> ByteArray = { DeviceKeyCrypto.newKey() },
    private val onStep: (AuthStep) -> Unit = {},
) {
    suspend fun authenticate(): AuthOutcome {
        var info = hello() ?: return AuthOutcome.Failed(null, "AUTH answer unreadable")
        if (!info.isBound) return pair(info)

        val keys = vault.candidates(serial)
        if (keys.isEmpty()) {
            log("Auth: box $serial is paired but no key is stored for it")
            return AuthOutcome.MissingKey(info.toDeviceInfo())
        }
        var lastCode = -1L
        for ((index, key) in keys.withIndex()) {
            // Each attempt answers a fresh challenge: the box may not accept a second try on the same one
            if (index > 0) info = hello() ?: return AuthOutcome.Failed(null, "AUTH answer unreadable")
            if (!info.isBound) return pair(info)
            onStep(AuthStep.VERIFYING)
            val kind = if (key.pending) "pending" else "stored"
            log("Auth: verifying with the $kind key ${KeyFingerprint.of(key.bytes)}")
            val answer = channel.request(Cmd.AUTH, AuthKey.VERIFY_DEVICE_KEY, DeviceKeyCrypto.answerChallenge(key.bytes, info.challenge))
            lastCode = answer.errorCode
            if (lastCode == ResultCode.OK) {
                if (key.pending) {
                    log("Auth: the pending key is the one the box holds, making it the active key")
                    vault.confirm(serial, key.bytes)
                }
                log("Auth: verified")
                return AuthOutcome.Verified(info.toDeviceInfo(), key.pending)
            }
            log("Auth: key ${KeyFingerprint.of(key.bytes)} refused (${ResultCode.describe(lastCode)})")
        }
        return AuthOutcome.Rejected(info.toDeviceInfo(), ResultCode.describe(lastCode))
    }

    /**
     * Re-pairing from an authenticated session: UNBIND clears the box's key, then a new key is set.
     * The old key stays in the vault as a backup until the new one is confirmed.
     */
    suspend fun repair(): AuthOutcome {
        onStep(AuthStep.UNBINDING)
        log("Auth: unbinding")
        val answer = channel.request(Cmd.AUTH, AuthKey.UNBIND, ByteArray(0))
        if (answer.payload.size >= 4 && answer.errorCode != ResultCode.OK) {
            return AuthOutcome.Failed(null, "UNBIND refused (${ResultCode.describe(answer.errorCode)})")
        }
        val info = hello() ?: return AuthOutcome.Failed(null, "AUTH answer unreadable")
        if (info.isBound) return AuthOutcome.Failed(info.toDeviceInfo(), "the box still reports a key after UNBIND")
        return pair(info, force = true)
    }

    private suspend fun hello(): AuthInfo? {
        onStep(AuthStep.HELLO)
        val answer = channel.request(Cmd.AUTH, AuthKey.AUTH, ByteArray(0))
        return runCatching { AuthInfo.parse(answer.payload) }
            .onSuccess {
                log("Auth: $it")
                if (!Serials.same(it.serial, serial)) log("Auth: the box reports serial ${it.serial}, saved as $serial")
            }
            .onFailure { log("Auth: bad AUTH answer: ${it.message} (${answer.payload.toHex()})") }
            .getOrNull()
    }

    private suspend fun pair(info: AuthInfo, force: Boolean = false): AuthOutcome {
        if (!force && !pairingAllowed()) {
            log("Auth: box $serial has no key but wasn't chosen for pairing, leaving it alone")
            return AuthOutcome.PairingNotAllowed(info.toDeviceInfo())
        }
        onStep(AuthStep.PAIRING)
        val key = newKey()
        vault.savePending(serial, key)
        log("Auth: box has no key, setting a new one ${KeyFingerprint.of(key)}")
        val answer = channel.request(Cmd.AUTH, AuthKey.SET_DEVICE_KEY, key)
        val code = answer.errorCode
        if (code != ResultCode.OK) {
            log("Auth: new key refused (${ResultCode.describe(code)})")
            return AuthOutcome.PairingFailed(info.toDeviceInfo(), ResultCode.describe(code))
        }
        vault.confirm(serial, key)
        log("Auth: paired")
        return AuthOutcome.Paired(info.toDeviceInfo())
    }
}
