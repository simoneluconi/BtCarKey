package com.simoneluconi.btcarkey.smart

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.os.Build
import androidx.annotation.RequiresApi
import com.simoneluconi.btcarkey.EventLog

/**
 * The system binds this while an associated car is in range and tells it when the car appears or
 * disappears. Each Android version has its own callback; all lead to [SmartEntry.onPresence]. Every
 * raw callback is logged: the system's timing is what field logs need most.
 *
 * Android 16 calls both [onDevicePresenceEvent] and the older callbacks, and the older ones also
 * fire when the phone connects to or disconnects from the box, which is not presence. So from
 * Android 16 only the BLE events of [onDevicePresenceEvent] count.
 */
@RequiresApi(Build.VERSION_CODES.S)
class SmartPresenceService : CompanionDeviceService() {
    private val modern get() = Build.VERSION.SDK_INT >= 36

    @Deprecated("Android 12")
    override fun onDeviceAppeared(address: String) {
        EventLog.log("Smart: system callback appeared(address)")
        SmartEntry.onPresence(this, address, appeared = true)
    }

    @Deprecated("Android 12")
    override fun onDeviceDisappeared(address: String) {
        EventLog.log("Smart: system callback disappeared(address)")
        SmartEntry.onPresence(this, address, appeared = false)
    }

    @Deprecated("Android 13 to 15")
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        if (modern) return
        EventLog.log("Smart: system callback appeared(association ${associationInfo.id})")
        SmartEntry.onPresence(this, associationInfo.deviceMacAddress?.toString(), appeared = true)
    }

    @Deprecated("Android 13 to 15")
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        if (modern) return
        EventLog.log("Smart: system callback disappeared(association ${associationInfo.id})")
        SmartEntry.onPresence(this, associationInfo.deviceMacAddress?.toString(), appeared = false)
    }

    @RequiresApi(36)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        EventLog.log("Smart: system presence event ${eventName(event.event)} (association ${event.associationId})")
        val mac = CompanionLink.macOf(this, event.associationId)
        // BT_CONNECTED / BT_DISCONNECTED are the phone's own link coming and going, not presence
        when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED -> SmartEntry.onPresence(this, mac, appeared = true)
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> SmartEntry.onPresence(this, mac, appeared = false)
        }
    }

    @RequiresApi(36)
    private fun eventName(event: Int): String = when (event) {
        DevicePresenceEvent.EVENT_BLE_APPEARED -> "BLE_APPEARED"
        DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> "BLE_DISAPPEARED"
        DevicePresenceEvent.EVENT_BT_CONNECTED -> "BT_CONNECTED"
        DevicePresenceEvent.EVENT_BT_DISCONNECTED -> "BT_DISCONNECTED"
        DevicePresenceEvent.EVENT_SELF_MANAGED_APPEARED -> "SELF_MANAGED_APPEARED"
        DevicePresenceEvent.EVENT_SELF_MANAGED_DISAPPEARED -> "SELF_MANAGED_DISAPPEARED"
        else -> "event $event"
    }
}
