package com.simoneluconi.btcarkey.link

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.simoneluconi.btcarkey.EventLog
import com.simoneluconi.btcarkey.protocol.AdFilter
import com.simoneluconi.btcarkey.protocol.Advertisement
import com.simoneluconi.btcarkey.protocol.KeyProtocol
import com.simoneluconi.btcarkey.protocol.Protocols
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A device seen in advertising, recognised by [protocol]; [device] carries the right address type for connecting. */
data class FoundBox(
    val serial: String,
    val mac: String,
    val rssi: Int,
    val name: String?,
    val protocol: KeyProtocol,
    val device: BluetoothDevice,
)

/**
 * Finds devices of every known protocol ([Protocols]): the protocols' advertising filters run in the
 * Bluetooth controller, then each protocol says whether a result is one of its devices.
 * Only runs while collected, and callers always bound it with a timeout: scanning is the
 * expensive part of BLE, so it is used to add a device or to learn its address, never to wait for it.
 */
class BoxScanner(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun scan(): Flow<FoundBox> = callbackFlow {
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            EventLog.log("Scan: Bluetooth is off")
            close()
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                parse(result)?.let { trySend(it) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result -> parse(result)?.let { trySend(it) } }
            }

            override fun onScanFailed(errorCode: Int) {
                EventLog.log("Scan: failed ($errorCode)")
                close()
            }
        }
        val filters = Protocols.scanFilters.map { filter ->
            when (filter) {
                // Empty data matches any payload: the filter is on the company id
                is AdFilter.Manufacturer -> ScanFilter.Builder().setManufacturerData(filter.companyId, ByteArray(0)).build()
                is AdFilter.Service -> ScanFilter.Builder().setServiceUuid(ParcelUuid(filter.uuid)).build()
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        EventLog.log("Scan: started")
        scanner.startScan(filters, settings, callback)
        awaitClose {
            runCatching { scanner.stopScan(callback) }
            EventLog.log("Scan: stopped")
        }
    }

    @SuppressLint("MissingPermission")
    private fun parse(result: ScanResult): FoundBox? {
        val record = result.scanRecord ?: return null
        val manufacturer = record.manufacturerSpecificData
        val advert = Advertisement(
            manufacturerData = (0 until (manufacturer?.size() ?: 0)).associate { manufacturer!!.keyAt(it) to manufacturer.valueAt(it) },
            serviceUuids = record.serviceUuids?.map { it.uuid }.orEmpty(),
            name = record.deviceName,
        )
        val (protocol, serial) = Protocols.identify(advert) ?: return null
        return FoundBox(serial, result.device.address, result.rssi, record.deviceName, protocol, result.device)
    }
}
