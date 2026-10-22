package com.simoneluconi.btcarkey.smart

import android.annotation.SuppressLint
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.os.Build
import androidx.annotation.RequiresApi
import com.simoneluconi.btcarkey.EventLog

/**
 * Android's companion device API, used so that the system tells the app when the car comes into
 * range or leaves it: the system scans for associated devices in its own low-power way, with the
 * app closed, and lets the app start a foreground service at that moment.
 *
 * One association per car, made once through a system dialog. Android 12+ (presence observation).
 */
object CompanionLink {
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * Asks the system to associate the device at [mac]. [onConfirm] gets the system dialog to launch;
     * [onFailure] the reason when it can't be shown (device not seen, Bluetooth off...).
     */
    @RequiresApi(Build.VERSION_CODES.S)
    fun associate(context: Context, mac: String, onConfirm: (IntentSender) -> Unit, onFailure: (String) -> Unit) {
        val cdm = cdm(context)
        // The companion scan matches the address as text, so a random static address works too
        val scanFilter = ScanFilter.Builder().setDeviceAddress(mac).build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothLeDeviceFilter.Builder().setScanFilter(scanFilter).build())
            .setSingleDevice(true)
            .build()
        EventLog.log("Smart: asking the system to associate $mac")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm.associate(request, context.mainExecutor, object : CompanionDeviceManager.Callback() {
                override fun onAssociationPending(intentSender: IntentSender) = onConfirm(intentSender)
                override fun onAssociationCreated(associationInfo: AssociationInfo) =
                    EventLog.log("Smart: association ${associationInfo.id} created")
                override fun onFailure(error: CharSequence?) = onFailure(error?.toString() ?: "unknown error")
            })
        } else {
            @Suppress("DEPRECATION")
            cdm.associate(request, object : CompanionDeviceManager.Callback() {
                @Deprecated("Replaced by onAssociationPending on Android 13")
                override fun onDeviceFound(intentSender: IntentSender) = onConfirm(intentSender)
                override fun onFailure(error: CharSequence?) = onFailure(error?.toString() ?: "unknown error")
            }, null)
        }
    }

    /** Whether the app holds an association with [mac]. */
    fun isAssociated(context: Context, mac: String): Boolean = supported && associationId(context, mac) != null

    /** Starts presence events for [mac] (the app must be associated with it). */
    fun startObserving(context: Context, mac: String): Boolean {
        if (!supported) return false
        return runCatching { observe(context, mac, start = true) }
            .onSuccess { EventLog.log("Smart: watching for $mac") }
            .onFailure { EventLog.log("Smart: can't watch for $mac (${it.message})") }
            .isSuccess
    }

    fun stopObserving(context: Context, mac: String) {
        if (!supported) return
        runCatching { observe(context, mac, start = false) }
            .onSuccess { EventLog.log("Smart: stopped watching for $mac") }
    }

    /** Android 16 observes by association id, earlier versions by address. */
    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.S)
    private fun observe(context: Context, mac: String, start: Boolean) {
        val cdm = cdm(context)
        if (Build.VERSION.SDK_INT >= 36) {
            val id = associationId(context, mac) ?: error("not associated")
            val request = ObservingDevicePresenceRequest.Builder().setAssociationId(id).build()
            if (start) cdm.startObservingDevicePresence(request) else cdm.stopObservingDevicePresence(request)
        } else {
            @Suppress("DEPRECATION")
            if (start) cdm.startObservingDevicePresence(mac) else cdm.stopObservingDevicePresence(mac)
        }
    }

    /** The MAC of an association id (presence events on Android 16 carry only the id). */
    fun macOf(context: Context, associationId: Int): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return cdm(context).myAssociations.firstOrNull { it.id == associationId }?.deviceMacAddress?.toString()?.uppercase()
    }

    private fun cdm(context: Context): CompanionDeviceManager = context.getSystemService(CompanionDeviceManager::class.java)

    private fun associationId(context: Context, mac: String): Int? {
        val cdm = cdm(context)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            cdm.myAssociations.firstOrNull { it.deviceMacAddress?.toString().equals(mac, ignoreCase = true) }?.id
        } else {
            @Suppress("DEPRECATION")
            if (cdm.associations.any { it.equals(mac, ignoreCase = true) }) 0 else null
        }
    }
}
