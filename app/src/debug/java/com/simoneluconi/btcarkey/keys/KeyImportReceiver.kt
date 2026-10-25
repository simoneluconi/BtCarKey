package com.simoneluconi.btcarkey.keys

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.simoneluconi.btcarkey.BtCarKeyApp
import com.simoneluconi.btcarkey.EventLog
import com.simoneluconi.btcarkey.auth.Serials
import com.simoneluconi.btcarkey.protocol.Protocols
import com.simoneluconi.btcarkey.protocol.hexToBytesOrNull

/**
 * Adds a box with its key from the PC, over adb (`scripts\push-key.cmd`):
 *
 *     adb shell am broadcast -n com.simoneluconi.btcarkey/.keys.KeyImportReceiver \
 *         -a com.simoneluconi.btcarkey.IMPORT_KEY --es serial AA:BB:CC:DD:EE:FF --es key <hex> \
 *         [--es name "My car"] [--es mac AA:BB:CC:DD:EE:FF] [--es protocol ftk]
 *
 * Debug builds only. Guarded by android.permission.DUMP, which the adb shell holds and ordinary apps can't get.
 * The key never reaches the log; the result is in the log and in the broadcast's result data.
 */
class KeyImportReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext as BtCarKeyApp
        val serial = intent.getStringExtra("serial")?.let(Serials::normalize)
        val key = intent.getStringExtra("key")?.hexToBytesOrNull()
        val mac = intent.getStringExtra("mac")?.let(Serials::normalize)
        val name = intent.getStringExtra("name")?.trim().orEmpty()
        val existing = serial?.let(app.store::device)
        val protocolId = intent.getStringExtra("protocol") ?: existing?.protocolId ?: Protocols.default.id
        val protocol = Protocols.byId(protocolId)
        val error = when {
            serial == null -> "missing or invalid serial (expected AA:BB:CC:DD:EE:FF)"
            protocol == null -> "unknown protocol \"$protocolId\" (known: ${Protocols.all.joinToString { it.id }})"
            key == null || key.size != protocol.keySize -> "missing or invalid key (expected ${protocol.keySize * 2} hex digits)"
            else -> null
        }
        if (error != null) {
            EventLog.log("adb import: $error")
            resultCode = 1
            resultData = "ERROR: $error"
            return
        }
        val store = app.store
        store.add(serial!!, name.ifBlank { existing?.name ?: serial }, mac ?: existing?.mac, protocol!!.id)
        store.importKey(serial, key!!)
        app.controller.use(serial)
        resultCode = 0
        resultData = "OK: key imported for $serial (${protocol.displayName})"
    }

    companion object {
        const val ACTION = "com.simoneluconi.btcarkey.IMPORT_KEY"
    }
}
