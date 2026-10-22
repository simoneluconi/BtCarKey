package com.simoneluconi.btcarkey.keys

import android.content.Context
import androidx.core.content.edit
import com.simoneluconi.btcarkey.EventLog
import com.simoneluconi.btcarkey.auth.KeyFingerprint
import com.simoneluconi.btcarkey.auth.KeyVault
import com.simoneluconi.btcarkey.auth.StoredKey
import com.simoneluconi.btcarkey.protocol.Protocols
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A box the app knows. Identity is the serial from its advertising; [mac] is where to connect. */
data class SavedDevice(
    val serial: String,
    val name: String,
    /** The [com.simoneluconi.btcarkey.protocol.KeyProtocol] it speaks, recognised when it was added. */
    val protocolId: String,
    /** BLE address, learnt from a scan; null until the box has been seen once. */
    val mac: String?,
    /** [com.simoneluconi.btcarkey.auth.KeyFingerprint] of the active key, null without one. */
    val keyFingerprint: String?,
    /** A key was sent but its pairing wasn't confirmed: it is tried after the active one. */
    val hasPendingKey: Boolean,
    /** The key replaced by the last pairing or import, kept in case the box still holds it. */
    val hasBackupKey: Boolean,
) {
    val hasKey: Boolean get() = keyFingerprint != null
}

/**
 * The boxes the driver added and their keys. Names, addresses and protocols live in plain
 * preferences, keys in [SecretStore] (Keystore-encrypted, opaque bytes whatever the protocol),
 * one set per serial:
 * - active: the key used to authenticate;
 * - pending: a new key saved before it is sent to the box, promoted when the box accepts it;
 * - backup: the previous active key.
 */
class DeviceStore(context: Context) : KeyVault {
    private val prefs = context.applicationContext.getSharedPreferences("devices", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context, "device_keys")

    private val _devices = MutableStateFlow(load())
    val devices: StateFlow<List<SavedDevice>> = _devices.asStateFlow()

    private val _selected = MutableStateFlow(prefs.getString(SELECTED, null) ?: _devices.value.firstOrNull()?.serial)
    /** Serial of the box the home screen controls. */
    val selected: StateFlow<String?> = _selected.asStateFlow()

    /** A known box moved to a new address: (serial, the address it had). */
    var onMacChanged: ((String, String) -> Unit)? = null

    /** A box was removed: (serial, the address it had, if any). */
    var onRemoved: ((String, String?) -> Unit)? = null

    fun device(serial: String): SavedDevice? = _devices.value.firstOrNull { it.serial == serial }

    /** Adds a box, or updates its name/address/protocol when it is already known. */
    @Synchronized
    fun add(serial: String, name: String, mac: String?, protocolId: String) {
        val serials = serials() + serial
        prefs.edit(commit = true) {
            putStringSet(SERIALS, serials)
            putString(nameKey(serial), name.ifBlank { serial })
            putString(protocolKey(serial), protocolId)
            if (mac != null) putString(macKey(serial), mac)
        }
        EventLog.log("Devices: saved $serial as \"${name.ifBlank { serial }}\" ($protocolId)")
        // Not selected here: the caller does it through CarKeyController.use, which connects once
        refresh()
    }

    @Synchronized
    fun rename(serial: String, name: String) {
        if (serial !in serials()) return
        prefs.edit { putString(nameKey(serial), name.ifBlank { serial }) }
        refresh()
    }

    /** The address can change after a firmware update; the serial is what identifies the box. */
    fun updateMac(serial: String, mac: String) {
        val old = synchronized(this) {
            val old = prefs.getString(macKey(serial), null)
            if (serial !in serials() || old.equals(mac, ignoreCase = true)) return
            prefs.edit { putString(macKey(serial), mac) }
            EventLog.log("Devices: $serial is at $mac")
            refresh()
            old
        }
        if (old != null) onMacChanged?.invoke(serial, old)
    }

    /** Forgets the box and all its keys. The box itself keeps its key until it is reset or unbound. */
    fun remove(serial: String) {
        val mac = synchronized(this) { removeLocked(serial) }
        onRemoved?.invoke(serial, mac)
    }

    private fun removeLocked(serial: String): String? {
        val mac = prefs.getString(macKey(serial), null)
        prefs.edit(commit = true) {
            putStringSet(SERIALS, serials() - serial)
            remove(nameKey(serial))
            remove(macKey(serial))
            remove(protocolKey(serial))
        }
        for (slot in SLOTS) secrets.put(slotKey(serial, slot), null)
        EventLog.log("Devices: removed $serial and its keys")
        if (_selected.value == serial) select(serials().firstOrNull())
        refresh()
        return mac
    }

    fun select(serial: String?) {
        prefs.edit { putString(SELECTED, serial) }
        _selected.value = serial
    }

    /** Sets the key the box holds (import, adb). The previous key becomes the backup. */
    @Synchronized
    fun importKey(serial: String, key: ByteArray) {
        val protocol = device(serial)?.let { Protocols.byId(it.protocolId) }
        require(protocol == null || key.size == protocol.keySize) { "A ${protocol?.displayName} key is ${protocol?.keySize} bytes" }
        replaceActive(serial, key)
        secrets.put(slotKey(serial, PENDING), null)
        EventLog.log("Devices: key ${KeyFingerprint.of(key)} imported for $serial")
        refresh()
    }

    /** The active key, for the driver to see or export (after biometric confirmation in the UI). */
    fun activeKey(serial: String): ByteArray? = secrets.get(slotKey(serial, ACTIVE))

    override fun candidates(serial: String): List<StoredKey> = listOfNotNull(
        secrets.get(slotKey(serial, ACTIVE))?.let { StoredKey(it, pending = false) },
        secrets.get(slotKey(serial, PENDING))?.let { StoredKey(it, pending = true) },
    )

    @Synchronized
    override fun savePending(serial: String, key: ByteArray) {
        secrets.put(slotKey(serial, PENDING), key)
        refresh()
    }

    @Synchronized
    override fun confirm(serial: String, key: ByteArray) {
        replaceActive(serial, key)
        secrets.put(slotKey(serial, PENDING), null)
        refresh()
    }

    private fun replaceActive(serial: String, key: ByteArray) {
        val old = secrets.get(slotKey(serial, ACTIVE))
        if (old != null && !old.contentEquals(key)) secrets.put(slotKey(serial, BACKUP), old)
        secrets.put(slotKey(serial, ACTIVE), key)
    }

    private fun refresh() {
        _devices.value = load()
    }

    private fun serials(): Set<String> = prefs.getStringSet(SERIALS, emptySet()).orEmpty().toSet()

    private fun load(): List<SavedDevice> = serials().sorted().map { serial ->
        SavedDevice(
            serial = serial,
            name = prefs.getString(nameKey(serial), null) ?: serial,
            // Saved before protocols had ids: those are FTK boxes
            protocolId = prefs.getString(protocolKey(serial), null) ?: Protocols.default.id,
            mac = prefs.getString(macKey(serial), null),
            // The one decryption per box: an unreadable active key counts as none
            keyFingerprint = secrets.get(slotKey(serial, ACTIVE))?.let(KeyFingerprint::of),
            hasPendingKey = secrets.contains(slotKey(serial, PENDING)),
            hasBackupKey = secrets.contains(slotKey(serial, BACKUP)),
        )
    }.sortedBy { it.name.lowercase() }

    private companion object {
        const val SERIALS = "serials"
        const val SELECTED = "selected"
        const val ACTIVE = "active"
        const val PENDING = "pending"
        const val BACKUP = "backup"
        val SLOTS = listOf(ACTIVE, PENDING, BACKUP)

        fun nameKey(serial: String) = "name_$serial"
        fun macKey(serial: String) = "mac_$serial"
        fun protocolKey(serial: String) = "protocol_$serial"
        fun slotKey(serial: String, slot: String) = "${slot}_$serial"
    }
}
