package com.simoneluconi.btcarkey.protocol.ftk

import com.simoneluconi.btcarkey.auth.KeyVault
import com.simoneluconi.btcarkey.auth.StoredKey

/** In-memory vault with the same rules as DeviceStore (one device). */
class MemoryVault : KeyVault {
    var active: ByteArray? = null
    var pending: ByteArray? = null
    var backup: ByteArray? = null

    override fun candidates(serial: String) =
        listOfNotNull(active?.let { StoredKey(it, false) }, pending?.let { StoredKey(it, true) })

    override fun savePending(serial: String, key: ByteArray) {
        pending = key
    }

    override fun confirm(serial: String, key: ByteArray) {
        if (active != null && !active!!.contentEquals(key)) backup = active
        active = key
        pending = null
    }
}
