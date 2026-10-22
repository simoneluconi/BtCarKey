package com.simoneluconi.btcarkey.link

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import com.simoneluconi.btcarkey.EventLog
import com.simoneluconi.btcarkey.protocol.GattProfile
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ConnectionPriorityRequest

/**
 * GATT client for any protocol's [GattProfile]: notifications in, writes out (Write Without
 * Response when the characteristic allows it). Setup after service discovery (Nordic's init
 * queue): notifications on, then the MTU; the device is "ready" once both are done. Longer writes
 * are split to MTU - 3 by the library.
 */
class BoxBleManager(
    context: Context,
    private val profile: GattProfile,
    private val onData: (ByteArray) -> Unit,
) : BleManager(context) {
    private var writeTarget: BluetoothGattCharacteristic? = null
    private var notifySource: BluetoothGattCharacteristic? = null

    // DEBUG shows how each connection is opened (connectGatt with or without autoConnect), which is
    // what tells a cheap low-power wait from repeated direct attempts in a field log
    override fun getMinLogPriority(): Int = Log.DEBUG

    override fun log(priority: Int, message: String) {
        if (priority >= Log.DEBUG) EventLog.log("GATT: $message")
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        val service = gatt.getService(profile.service)
        if (service == null) {
            EventLog.log("GATT: service ${profile.service} not found, this device doesn't speak the expected protocol")
            return false
        }
        val write = service.getCharacteristic(profile.write)
        val notify = service.getCharacteristic(profile.notify)
        if (write == null || notify == null) {
            EventLog.log("GATT: characteristic ${if (write == null) profile.write else profile.notify} not found")
            return false
        }
        val canWrite = write.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
        val canNotify = notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        if (!canNotify || !canWrite) {
            EventLog.log("GATT: characteristic properties lack notify or write")
            return false
        }
        writeTarget = write
        notifySource = notify
        return true
    }

    override fun initialize() {
        setNotificationCallback(notifySource).with { _, value -> value.value?.let(onData) }
        enableNotifications(notifySource)
            .fail { _, status -> EventLog.log("GATT: enabling notifications failed ($status)") }
            .enqueue()
        if (profile.mtu > 0) {
            requestMtu(profile.mtu)
                .with { _, mtu -> EventLog.log("GATT: MTU $mtu") }
                .fail { _, status -> EventLog.log("GATT: MTU request refused ($status), staying at the default") }
                .enqueue()
        }
    }

    override fun onServicesInvalidated() {
        writeTarget = null
        notifySource = null
    }

    /**
     * Low power (longer connection interval) while the app is in the background, balanced while it
     * is on screen so commands feel instant.
     */
    fun setLowPower(lowPower: Boolean) {
        if (!isReady) return
        requestConnectionPriority(
            if (lowPower) ConnectionPriorityRequest.CONNECTION_PRIORITY_LOW_POWER else ConnectionPriorityRequest.CONNECTION_PRIORITY_BALANCED
        ).enqueue()
    }

    /** Sends bytes. Write Without Response when possible: delivery is confirmed by the device's answer. */
    fun send(bytes: ByteArray, onFail: (Int) -> Unit) {
        val target = writeTarget ?: return onFail(-1)
        val type = if (target.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        writeCharacteristic(target, bytes, type)
            .split()
            .fail { _, status -> onFail(status) }
            .enqueue()
    }
}
