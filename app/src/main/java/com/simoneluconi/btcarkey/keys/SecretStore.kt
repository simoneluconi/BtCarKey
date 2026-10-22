package com.simoneluconi.btcarkey.keys

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import com.simoneluconi.btcarkey.EventLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small secrets (device keys) encrypted with an AES-GCM key that never leaves the Android Keystore.
 * Values are stored as base64 of IV + ciphertext. A value that can't be decrypted any more (Keystore
 * key lost, e.g. after a backup restore) reads as absent and is logged.
 */
class SecretStore(context: Context, name: String) {
    private val prefs = context.applicationContext.getSharedPreferences("secrets_$name", Context.MODE_PRIVATE)

    fun get(key: String): ByteArray? {
        val stored = prefs.getString(key, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES)
        }.onFailure { EventLog.log("Secrets: a stored value can't be read any more (${it.javaClass.simpleName})") }.getOrNull()
    }

    fun put(key: String, value: ByteArray?) {
        if (value == null) {
            prefs.edit(commit = true) { remove(key) }
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(value)
        // Synchronous: a key must be on disk before it is sent to the box
        prefs.edit(commit = true) { putString(key, Base64.encodeToString(out, Base64.NO_WRAP)) }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "btcarkey_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
