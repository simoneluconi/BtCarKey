package com.simoneluconi.btcarkey.link

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.core.content.ContextCompat
import com.simoneluconi.btcarkey.EventLog
import com.simoneluconi.btcarkey.auth.AuthOutcome
import com.simoneluconi.btcarkey.auth.Serials
import com.simoneluconi.btcarkey.keys.DeviceStore
import com.simoneluconi.btcarkey.keys.SavedDevice
import com.simoneluconi.btcarkey.protocol.AutoLockConfig
import com.simoneluconi.btcarkey.protocol.BoxStatus
import com.simoneluconi.btcarkey.protocol.CarCommand
import com.simoneluconi.btcarkey.protocol.CommandAck
import com.simoneluconi.btcarkey.protocol.KeyProtocol
import com.simoneluconi.btcarkey.protocol.PhoneInfo
import com.simoneluconi.btcarkey.protocol.ProtocolSession
import com.simoneluconi.btcarkey.protocol.Protocols
import com.simoneluconi.btcarkey.protocol.RawConfigKind
import com.simoneluconi.btcarkey.protocol.RemoteButton
import com.simoneluconi.btcarkey.protocol.SessionContext
import com.simoneluconi.btcarkey.settings.ActionConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.callback.FailCallback
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.io.IOException
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min

/**
 * Owns the connection to the selected device for the whole process: connect, authenticate, keep
 * it, and let go when the app isn't in use. Protocol-agnostic: what goes over the link is the
 * device's [KeyProtocol]/[ProtocolSession].
 *
 * Battery rules (the vendor app is a known drain):
 * - no background scanning: a saved device is connected by address; scans are short, bounded, and
 *   only to add a device or to learn its address;
 * - waiting for a device out of range uses autoConnect: the Bluetooth controller connects by itself
 *   when the device advertises;
 * - after a link loss the Nordic library reconnects the same way, and the session authenticates again;
 * - no polling: status is read once per connection and from the device's reports;
 * - [GRACE_MS] after the app leaves the screen, the connection is closed, unless the driver asked
 *   to stay connected (comfort entry needs the link: the device measures the phone's signal on it);
 *   then the link runs at the low-power connection interval;
 * - on a problem the driver has to look at (wrong key...), nothing retries by itself.
 */
class CarKeyController(private val context: Context, val store: DeviceStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val scanner = BoxScanner(context)
    private val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    private val _state = MutableStateFlow<LinkState>(LinkState.Idle)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _status = MutableStateFlow(BoxStatus())
    /** Battery & co. of the connected device, as far as it reported them. */
    val status: StateFlow<BoxStatus> = _status.asStateFlow()

    private val _busy = MutableStateFlow<CarCommand?>(null)
    /** The command waiting for its answer, if any. */
    val busy: StateFlow<CarCommand?> = _busy.asStateFlow()

    private val _results = MutableSharedFlow<CommandResult>(extraBufferCapacity = 8)
    val results: SharedFlow<CommandResult> = _results.asSharedFlow()

    private var manager: BoxBleManager? = null
    private var session: ProtocolSession? = null
    /** Serial of the device [manager] is for. */
    private var target: String? = null
    private var foreground = false
    /** Stay connected in the background (KeepConnectedService runs meanwhile). */
    private var keepAlive = false
    /** Whether a connection is wanted at all. */
    private val wanted: Boolean get() = foreground || keepAlive
    private var connectJob: Job? = null
    private var authJob: Job? = null
    private var stopJob: Job? = null
    /** Consecutive failed attempts, for the back-off. */
    private var failures = 0
    /** Devices from scans: they carry the right address type, better than one rebuilt from the address. */
    private val scanned = HashMap<String, BluetoothDevice>()

    init {
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> {
                        EventLog.log("Bluetooth on")
                        if (wanted) start()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        if (_state.value != LinkState.BluetoothOff) EventLog.log("Bluetooth off")
                        teardown()
                        _state.value = LinkState.BluetoothOff
                    }
                }
            }
        }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)

        // Another device selected: switch to it
        scope.launch { store.selected.drop(1).collect { if (wanted && it != target) restart() } }
        // The connected device was removed: let it go
        scope.launch {
            store.devices.collect { devices ->
                val current = target ?: return@collect
                if (devices.none { it.serial == current }) {
                    teardown()
                    if (wanted) start()
                }
            }
        }
    }

    /** The protocol a saved device speaks; null if this build doesn't know it. */
    fun protocolOf(device: SavedDevice): KeyProtocol? = Protocols.byId(device.protocolId)

    /** The app is on screen: connect. */
    fun onForeground() {
        stopJob?.cancel()
        foreground = true
        val current = _state.value
        val waitsForDriver = current is LinkState.Problem && current.kind in setOf(
            ProblemKind.KEY_MISSING, ProblemKind.KEY_REJECTED, ProblemKind.NOT_SUPPORTED, ProblemKind.PAIRING_FAILED,
        ) && current.serial == store.selected.value
        if (!waitsForDriver) start()
        manager?.setLowPower(false)
    }

    /** The app left the screen: disconnect after a grace period (quick app switches keep the link). */
    fun onBackground() {
        foreground = false
        stopJob?.cancel()
        if (keepAlive) {
            manager?.setLowPower(true)
            return
        }
        stopJob = scope.launch {
            delay(GRACE_MS)
            if (!wanted && manager != null) {
                EventLog.log("App in background, disconnecting")
                teardown()
            }
            if (!wanted) _state.value = LinkState.Idle
        }
    }

    /** Background connection on/off (driven by KeepConnectedService). */
    fun setKeepAlive(enabled: Boolean) {
        if (keepAlive == enabled) return
        keepAlive = enabled
        EventLog.log(if (enabled) "Staying connected in the background" else "Background connection off")
        if (enabled) {
            stopJob?.cancel()
            start()
            if (!foreground) manager?.setLowPower(true)
        } else if (!foreground) {
            onBackground()
        }
    }

    /**
     * Makes [serial] the device in use and (re)connects to it, once: selecting another device restarts
     * the link by itself, the same device needs a fresh start (e.g. its key just changed). Two connection
     * attempts at once would give two GATT clients on one link, and closing one drops the other.
     */
    fun use(serial: String) {
        if (store.selected.value == serial) retry() else store.select(serial)
    }

    /** Permissions were granted, Bluetooth turned on, a key imported, or the driver tapped Retry. */
    fun retry() {
        failures = 0
        restart()
    }

    private fun restart() {
        teardown()
        start()
    }

    /** Devices in range, of any known protocol, for adding one. Scans while collected: bound it in time. */
    fun scanForBoxes(): Flow<FoundBox> = scanner.scan().onEach { scanned[it.mac] = it.device }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (!wanted) return
        val serial = store.selected.value
        val saved = serial?.let(store::device)
        if (saved == null) {
            teardown()
            _state.value = LinkState.NoDevice
            return
        }
        val protocol = protocolOf(saved)
        if (protocol == null) {
            teardown(keepState = true)
            _state.value = LinkState.Problem(saved.serial, ProblemKind.NOT_SUPPORTED, "Unknown protocol \"${saved.protocolId}\"")
            return
        }
        if (!hasPermissions(context)) {
            _state.value = LinkState.NeedsPermission
            return
        }
        if (adapter?.isEnabled != true) {
            _state.value = LinkState.BluetoothOff
            return
        }
        if (target == saved.serial && manager != null) return
        teardown()
        target = saved.serial
        _status.value = BoxStatus()
        val mac = saved.mac
        if (mac == null) {
            connectJob = scope.launch {
                val found = locate(saved.serial, LOCATE_MS)
                if (found == null) {
                    EventLog.log("Device ${saved.serial} not found nearby")
                    target = null
                    _state.value = LinkState.Problem(saved.serial, ProblemKind.NOT_FOUND, "Not in range, or connected to another phone")
                } else {
                    connect(saved.serial, protocol, found.device)
                }
            }
        } else {
            connect(saved.serial, protocol, deviceFor(mac))
        }
    }

    /** Scans for the device's advertising (bounded), and remembers its address. */
    private suspend fun locate(serial: String, timeoutMs: Long): FoundBox? {
        _state.value = LinkState.Locating(serial)
        val found = withTimeoutOrNull(timeoutMs) { scanForBoxes().first { Serials.same(it.serial, serial) } }
        found?.let { store.updateMac(serial, it.mac) }
        return found
    }

    @SuppressLint("MissingPermission")
    private fun connect(serial: String, protocol: KeyProtocol, device: BluetoothDevice) {
        val m = BoxBleManager(context, protocol.gatt) { bytes -> session?.onData(bytes) }
        manager = m
        target = serial
        m.setConnectionObserver(Observer(m, serial, protocol))
        _state.value = LinkState.Connecting(serial, waiting = false)
        EventLog.log("Connecting to $serial (${protocol.displayName}) at ${device.address}")
        // A direct attempt first: quick when the car is near. autoConnect = true keeps the link
        // armed after a link loss (the library reconnects by itself)
        m.connect(device)
            .useAutoConnect(true)
            .retry(1, 300)
            .fail { _, reason -> if (m === manager) onConnectFailed(m, serial, device, reason, waiting = false) }
            .enqueue()
        watchAddress(m, serial, device)
    }

    /**
     * The low-power wait: a pure autoConnect request with no timeout. The Bluetooth controller
     * connects as soon as the device advertises. Used when the direct attempt finds nothing (the car
     * is away), instead of retrying direct attempts (expensive).
     */
    @SuppressLint("MissingPermission")
    private fun waitFor(m: BoxBleManager, serial: String, device: BluetoothDevice) {
        _state.value = LinkState.Connecting(serial, waiting = true)
        EventLog.log("Device not reachable now, waiting for it in low-power mode")
        m.connect(device)
            .useAutoConnect(true, false)
            .fail { _, reason -> if (m === manager) onConnectFailed(m, serial, device, reason, waiting = true) }
            .enqueue()
    }

    /** One short scan, while the app is open and still not connected: the device may have a new address. */
    private fun watchAddress(m: BoxBleManager, serial: String, device: BluetoothDevice) {
        connectJob?.cancel()
        connectJob = scope.launch {
            delay(ADDRESS_CHECK_MS)
            // Only while the app is on screen: in the background the low-power wait goes on alone
            if (m !== manager || _state.value !is LinkState.Connecting || !foreground) return@launch
            val found = withTimeoutOrNull(ADDRESS_SCAN_MS) { scanForBoxes().first { Serials.same(it.serial, serial) } }
            if (m !== manager) return@launch
            when {
                found == null -> {
                    EventLog.log("Device not advertising nearby (out of range, or connected to another phone)")
                    _state.value = LinkState.Connecting(serial, waiting = true)
                }
                found.mac != device.address -> {
                    EventLog.log("Device moved to ${found.mac}, reconnecting there")
                    store.updateMac(serial, found.mac)
                    restart()
                }
                else -> _state.value = LinkState.Connecting(serial, waiting = true)
            }
        }
    }

    private fun onConnectFailed(m: BoxBleManager, serial: String, device: BluetoothDevice, reason: Int, waiting: Boolean) {
        EventLog.log("Connection failed (${failName(reason)})")
        when {
            reason == FailCallback.REASON_CANCELLED -> Unit
            reason == FailCallback.REASON_DEVICE_NOT_SUPPORTED ->
                stopWithProblem(serial, ProblemKind.NOT_SUPPORTED, "The device at this address doesn't speak its protocol")
            reason == FailCallback.REASON_BLUETOOTH_DISABLED -> _state.value = LinkState.BluetoothOff
            // The direct attempt found nothing: hand over to the controller's low-power wait
            !waiting -> waitFor(m, serial, device)
            // The wait itself broke (rare: stack trouble): back off and start over
            else -> scheduleReconnect(m, serial)
        }
    }

    /** Back-off retry while the app is open: 2, 4, 8 ... 60 s. */
    private fun scheduleReconnect(m: BoxBleManager, serial: String) {
        failures++
        val wait = min(MAX_BACKOFF_MS, 1000L shl min(failures, 6))
        _state.value = LinkState.Connecting(serial, waiting = true)
        connectJob?.cancel()
        connectJob = scope.launch {
            EventLog.log("Retrying in ${wait / 1000} s")
            delay(wait)
            if (m === manager && wanted) restart()
        }
    }

    private inner class Observer(
        private val m: BoxBleManager,
        private val serial: String,
        private val protocol: KeyProtocol,
    ) : ConnectionObserver {
        override fun onDeviceConnecting(device: BluetoothDevice) {
            if (m !== manager) return
            val waiting = (_state.value as? LinkState.Connecting)?.waiting ?: true
            if (_state.value !is LinkState.Connecting) _state.value = LinkState.Connecting(serial, waiting)
        }

        override fun onDeviceConnected(device: BluetoothDevice) {
            if (m === manager) EventLog.log("Connected, discovering services")
        }

        override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) = Unit // handled by the request's fail()

        override fun onDeviceReady(device: BluetoothDevice) {
            if (m !== manager) return
            connectJob?.cancel()
            store.updateMac(serial, device.address)
            authenticate(m, serial, protocol)
        }

        override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit

        override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
            if (m !== manager) return
            EventLog.log("Disconnected (${reasonName(reason)})")
            dropSession()
            if (_state.value is LinkState.Problem) return
            when (reason) {
                // autoConnect is armed: the controller reconnects as soon as the device is back
                ConnectionObserver.REASON_LINK_LOSS, ConnectionObserver.REASON_TERMINATE_PEER_USER ->
                    _state.value = LinkState.Connecting(serial, waiting = true)
                ConnectionObserver.REASON_NOT_SUPPORTED ->
                    stopWithProblem(serial, ProblemKind.NOT_SUPPORTED, "The device at this address doesn't speak its protocol")
                else -> if (wanted) scheduleReconnect(m, serial)
            }
        }
    }

    private fun authenticate(m: BoxBleManager, serial: String, protocol: KeyProtocol) {
        val s = protocol.newSession(
            SessionContext(
                serial = serial,
                vault = store,
                write = { bytes -> m.send(bytes) { status -> EventLog.log("TX failed (GATT $status)") } },
                log = EventLog::log,
                // Only devices the driver added are ever connected, so one with no key may be paired
                pairingAllowed = { store.device(serial) != null },
                onStep = { _state.value = LinkState.Authenticating(serial, it) },
                onStatus = { _status.value = _status.value.merge(it) },
                phone = ::phoneInfo,
            )
        )
        session = s
        authJob?.cancel()
        authJob = scope.launch {
            val outcome = try {
                s.authenticate()
            } catch (e: IOException) {
                AuthOutcome.Failed(null, e.message ?: e.javaClass.simpleName)
            }
            if (m === manager) onAuthOutcome(m, serial, protocol, outcome)
        }
    }

    private fun onAuthOutcome(m: BoxBleManager, serial: String, protocol: KeyProtocol, outcome: AuthOutcome) {
        when (outcome) {
            is AuthOutcome.Verified, is AuthOutcome.Paired -> {
                failures = 0
                val info = outcome.info!!
                _state.value = LinkState.Ready(serial, info, newPairing = outcome is AuthOutcome.Paired, protocol = protocol)
                _status.value = _status.value.merge(BoxStatus(firmware = info.firmware))
                m.setLowPower(!foreground)
                session?.let { s -> scope.launch { runCatching { s.start() } } }
            }
            is AuthOutcome.MissingKey ->
                stopWithProblem(serial, ProblemKind.KEY_MISSING, "The device is paired with a key this phone doesn't have")
            is AuthOutcome.Rejected ->
                stopWithProblem(serial, ProblemKind.KEY_REJECTED, "The device refused the stored key (${outcome.reason})")
            is AuthOutcome.PairingFailed ->
                stopWithProblem(serial, ProblemKind.PAIRING_FAILED, "The device refused the new key (${outcome.reason})")
            is AuthOutcome.PairingNotAllowed ->
                stopWithProblem(serial, ProblemKind.PAIRING_FAILED, "Pairing not allowed for this device")
            is AuthOutcome.Failed -> {
                EventLog.log("Auth failed: ${outcome.reason}")
                // Often a glitch of this connection: reconnect a few times, then stop and ask
                if (failures < MAX_PROTOCOL_RETRIES) {
                    teardown(keepState = true)
                    scheduleReconnectAfterTeardown(serial)
                } else {
                    stopWithProblem(serial, ProblemKind.PROTOCOL, outcome.reason)
                }
            }
        }
    }

    private fun scheduleReconnectAfterTeardown(serial: String) {
        failures++
        val wait = min(MAX_BACKOFF_MS, 1000L shl min(failures, 6))
        _state.value = LinkState.Connecting(serial, waiting = true)
        connectJob = scope.launch {
            EventLog.log("Reconnecting in ${wait / 1000} s")
            delay(wait)
            if (wanted) start()
        }
    }

    /** Stops on a problem: disconnects and doesn't come back by itself. */
    private fun stopWithProblem(serial: String, kind: ProblemKind, detail: String) {
        EventLog.log("Stopped: $detail")
        teardown(keepState = true)
        _state.value = LinkState.Problem(serial, kind, detail)
    }

    private fun phoneInfo(): PhoneInfo {
        val now = System.currentTimeMillis()
        return PhoneInfo(
            epochSeconds = now / 1000,
            utcOffsetSeconds = TimeZone.getDefault().getOffset(now) / 1000,
            is24h = DateFormat.is24HourFormat(context),
            language = Locale.getDefault().language,
        )
    }

    /** Re-reads the status (battery). */
    fun refreshStatus() {
        scope.launch { withSession { it.refreshStatus() } }
    }

    /** Runs a one-tap action the way the driver configured it (device command or remote button presses). */
    fun send(command: CarCommand, config: ActionConfig = ActionConfig()) {
        if (_busy.value != null) return
        scope.launch {
            val result = if (config.method == ActionConfig.Method.REMOTE) pressSequence(command, config) else sendNow(command)
            _results.emit(result)
        }
    }

    private suspend fun sendNow(command: CarCommand): CommandResult {
        val s = session
        if (_state.value !is LinkState.Ready || s == null) return CommandResult.Failed(command, "Not connected")
        _busy.value = command
        EventLog.log("Command: $command")
        return try {
            when (val ack = s.command(command)) {
                CommandAck.Done -> CommandResult.Done(command)
                CommandAck.Unconfirmed -> CommandResult.Unconfirmed(command)
                is CommandAck.Refused -> CommandResult.Failed(command, ack.reason)
            }
        } catch (e: IOException) {
            CommandResult.Failed(command, e.message ?: "Disconnected")
        } finally {
            _busy.value = null
        }.also { EventLog.log("Command: $it") }
    }

    /** Presses a remote button [ActionConfig.presses] times, each held [ActionConfig.holdMs]; always releases. */
    private suspend fun pressSequence(command: CarCommand, config: ActionConfig): CommandResult {
        val s = session
        if (_state.value !is LinkState.Ready || s == null) return CommandResult.Failed(command, "Not connected")
        val button = config.button
        _busy.value = command
        EventLog.log("Command: $command as $button x${config.presses}, ${config.holdMs} ms each")
        var down = false
        return try {
            repeat(config.presses) { i ->
                if (i > 0) delay(PRESS_GAP_MS)
                s.press(button, true)
                down = true
                delay(config.holdMs.toLong())
                s.press(button, false)
                down = false
            }
            CommandResult.Done(command)
        } catch (e: IOException) {
            CommandResult.Failed(command, e.message ?: "Disconnected")
        } finally {
            // A button must never stay pressed: release it even if the sequence was cut short
            if (down) runCatching { s.press(button, false) }
            _busy.value = null
        }.also { EventLog.log("Command: $it") }
    }

    /** Presses (down) or releases a remote button; the device holds it in between. */
    fun press(button: RemoteButton, down: Boolean) {
        val s = session ?: return
        if (_state.value !is LinkState.Ready) return
        scope.launch {
            try {
                // A release that may not have arrived is sent once more: a double release is harmless
                if (s.press(button, down) == CommandAck.Unconfirmed && !down) s.press(button, false)
            } catch (e: IOException) {
                EventLog.log("Hold $button ${if (down) "down" else "up"}: ${e.message}")
            }
        }
    }

    /** The phone's signal as the device measures it (positive, -dBm). Only on demand: calibration, live view. */
    suspend fun readRssi(): Result<Int> = withSession { it.readRssi() }

    /** Puts the device in NFC learning mode for [seconds]: hold the card to the reader meanwhile. */
    suspend fun registerNfc(seconds: Int = 10): Result<Unit> = withSession { it.registerNfc(seconds) }

    /** Licence plate stored on the device. */
    suspend fun setPlate(plate: String): Result<Unit> = withSession { it.setPlate(plate) }

    /** Vendor configuration blobs, sent as given. */
    suspend fun writeRawConfig(kind: RawConfigKind, bytes: ByteArray): Result<Unit> = withSession { it.writeRawConfig(kind, bytes) }

    /** Comfort entry settings as the device holds them. */
    suspend fun readAutoLock(): Result<AutoLockConfig> = withSession { it.readAutoLock() }

    suspend fun writeAutoLock(config: AutoLockConfig): Result<Unit> = withSession { it.writeAutoLock(config) }

    /** Clears the device's key and sets a new random one: every other phone's key stops working. */
    suspend fun repair(): Result<Unit> {
        val s = session
        val ready = _state.value as? LinkState.Ready
        val m = manager
        if (s == null || ready == null || m == null) return Result.failure(IOException("Not connected"))
        val outcome = try {
            s.repair()
        } catch (e: IOException) {
            AuthOutcome.Failed(null, e.message ?: "Disconnected")
        }
        onAuthOutcome(m, ready.serial, ready.protocol, outcome)
        return if (outcome.isAuthenticated) Result.success(Unit) else Result.failure(IOException(outcome.toString()))
    }

    private suspend fun <T> withSession(block: suspend (ProtocolSession) -> T): Result<T> {
        val s = session
        if (_state.value !is LinkState.Ready || s == null) return Result.failure(IOException("Not connected"))
        return try {
            Result.success(block(s))
        } catch (e: IOException) {
            EventLog.log("Request failed: ${e.message}")
            Result.failure(e)
        }
    }

    private fun dropSession() {
        authJob?.cancel()
        authJob = null
        session?.close()
        session = null
        _busy.value = null
    }

    /** Closes everything for the current device. Leaves [state] alone when [keepState]. */
    private fun teardown(keepState: Boolean = false) {
        connectJob?.cancel()
        connectJob = null
        dropSession()
        manager?.let { m ->
            manager = null
            m.setConnectionObserver(null)
            m.disconnect().then { m.close() }.enqueue()
        }
        target = null
        if (!keepState && _state.value !is LinkState.BluetoothOff) _state.value = LinkState.Idle
    }

    @SuppressLint("MissingPermission")
    private fun deviceFor(mac: String): BluetoothDevice {
        scanned[mac]?.let { return it }
        val adapter = adapter!!
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The two top bits set mean a random static address (the FTK box's case); otherwise public
            val first = mac.substring(0, 2).toInt(16)
            val type = if (first and 0xC0 == 0xC0) BluetoothDevice.ADDRESS_TYPE_RANDOM else BluetoothDevice.ADDRESS_TYPE_PUBLIC
            return adapter.getRemoteLeDevice(mac, type)
        }
        return adapter.getRemoteDevice(mac)
    }

    companion object {
        /** How long the link stays up after the app leaves the screen. */
        const val GRACE_MS = 20_000L
        private const val LOCATE_MS = 20_000L
        private const val ADDRESS_CHECK_MS = 20_000L
        private const val ADDRESS_SCAN_MS = 8_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val MAX_PROTOCOL_RETRIES = 3
        /** Pause between presses of a double/triple press, like a quick human double click. */
        private const val PRESS_GAP_MS = 250L

        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        fun hasPermissions(context: Context): Boolean = requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        /** A connect request's fail() code: negative library reasons, or the GATT status (e.g. 147 = timeout). */
        fun failName(reason: Int): String = when (reason) {
            FailCallback.REASON_DEVICE_DISCONNECTED -> "disconnected"
            FailCallback.REASON_DEVICE_NOT_SUPPORTED -> "not a supported device"
            FailCallback.REASON_TIMEOUT -> "timeout"
            FailCallback.REASON_CANCELLED -> "cancelled"
            FailCallback.REASON_BLUETOOTH_DISABLED -> "Bluetooth off"
            147 -> "not reachable (GATT timeout)"
            133 -> "GATT error 133"
            else -> "status $reason"
        }

        fun reasonName(reason: Int): String = when (reason) {
            ConnectionObserver.REASON_SUCCESS -> "by us"
            ConnectionObserver.REASON_TERMINATE_LOCAL_HOST -> "by the phone"
            ConnectionObserver.REASON_TERMINATE_PEER_USER -> "by the device"
            ConnectionObserver.REASON_LINK_LOSS -> "link loss"
            ConnectionObserver.REASON_NOT_SUPPORTED -> "not a supported device"
            ConnectionObserver.REASON_CANCELLED -> "cancelled"
            ConnectionObserver.REASON_TIMEOUT -> "timeout"
            else -> "reason $reason"
        }
    }
}
