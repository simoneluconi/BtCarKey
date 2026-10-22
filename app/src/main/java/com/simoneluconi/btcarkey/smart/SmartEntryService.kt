package com.simoneluconi.btcarkey.smart

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.simoneluconi.btcarkey.BtCarKeyApp
import com.simoneluconi.btcarkey.EventLog
import com.simoneluconi.btcarkey.R
import com.simoneluconi.btcarkey.link.CommandResult
import com.simoneluconi.btcarkey.link.LinkState
import com.simoneluconi.btcarkey.protocol.CarCommand
import com.simoneluconi.btcarkey.settings.HomeAction
import com.simoneluconi.btcarkey.smart.SmartEntryLogic.Decision
import com.simoneluconi.btcarkey.smart.SmartEntryLogic.Phase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Runs while the car is in range (started by [SmartEntry] on the system's presence event): connects,
 * samples the link's signal as the phone measures it, unlocks when the driver gets close and locks
 * when they walk away ([SmartEntryLogic]). Then it rests ([Rest]). A foreground service of type
 * connectedDevice; its quiet notification exists only near the car.
 *
 * Safety first: a lock that fails is reported to the driver, and once smart unlock is suspended
 * (cooldown or safety net) the visit may still lock but never unlock again.
 */
class SmartEntryService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private val app get() = application as BtCarKeyApp
    /** The car's name while this visit has left it unlocked (unlocked, not locked yet). */
    private var leftOpen: String? = null

    /**
     * Keeps the CPU awake during a visit: with the screen off and the phone still, Android suspends
     * the CPU and the sampling timers stall, so the lock could come late. Renewed on every sample with
     * a timeout, so it can never outlive a stuck visit.
     */
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BtCarKey:smart").apply {
            setReferenceCounted(false)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A startForegroundService must always be followed by startForeground, whatever comes next
        try {
            ServiceCompat.startForeground(this, SmartNotifications.ACTIVE_ID, SmartNotifications.active(this, getString(R.string.smart_near_connecting)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            EventLog.log("Smart: can't run now (${e.javaClass.simpleName})")
            stopSelf()
            return START_NOT_STICKY
        }
        val serial = intent?.getStringExtra(EXTRA_SERIAL)
        if (serial == null) {
            finish("no car given", Rest.NONE)
        } else if (job == null) {
            running = true
            job = scope.launch { run(serial) }
        }
        return START_NOT_STICKY
    }

    private suspend fun run(serial: String) {
        val controller = app.controller
        val name = app.store.device(serial)?.name ?: serial
        // The controller connects to the selected box: smart unlock works for one car, so it selects it
        if (app.store.selected.value != serial) app.store.select(serial)
        wakeLock.acquire(WAKE_MS)
        controller.setSmartActive(true)
        SmartEntry.setStatus(SmartStatus.Near(null, Phase.APPROACHING))
        val reached = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            controller.state.first { (it is LinkState.Ready || it is LinkState.Problem) && it.serialOrNull() == serial }
        }
        when (reached) {
            null -> return finish("couldn't connect to $name", Rest.NONE)
            // A wrong key and the like: nothing to retry until the driver fixes it, and not on every arrival
            is LinkState.Problem -> return finish("$name needs attention (${reached.detail})", Rest.UNTIL_GONE)
            else -> Unit
        }
        val startedAt = System.currentTimeMillis()
        val unlockAt = SmartEntry.unlockThreshold(app, startedAt)
        val logic = SmartEntryLogic(
            unlockDbm = unlockAt,
            lockDbm = maxOf(app.smart.lockDbm.value, unlockAt + SmartEntryLogic.MIN_GAP),
            requirePhoneUnlocked = app.smart.requirePhoneUnlocked.value,
            startedAt = startedAt,
            returnWindowMs = app.smart.returnWindowMin.value * 60_000L,
        )
        EventLog.log("Smart: near $name, unlocking within -$unlockAt dBm, locking beyond -${app.smart.lockDbm.value} dBm")
        // An earlier visit lost the link with the car unlocked: carry on from there, the lock comes on the way out
        val recovering = app.smart.leftOpen.value
        if (recovering) {
            EventLog.log("Smart: $name may still be unlocked from the last visit: locking as soon as you are away")
            logic.onUnlocked(startedAt)
            leftOpen = name
            notify(getString(R.string.smart_near_unlocked, name))
        } else {
            notify(getString(R.string.smart_near_approaching, name))
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        var waitingForPhone = false
        var unlockFailedAt = 0L
        var unlockedThisVisit = false
        // The signal goes to the log when it moves, or now and then: the field logs show the whole walk
        var loggedLevel: Int? = null
        var loggedAt = 0L
        while (scope.isActive) {
            wakeLock.acquire(WAKE_MS)
            val now = System.currentTimeMillis()
            if (!controller.isLinkedTo(serial)) {
                // A short drop (a dip in the signal): the link comes back by itself, and the lock is still to send
                EventLog.log("Smart: link lost, waiting up to ${RELINK_MS / 1000} s for it to come back")
                var back = withTimeoutOrNull(RELINK_MS) {
                    controller.state.first { it is LinkState.Ready && it.serial == serial }
                } != null
                if (!back && leftOpen != null) {
                    // The car is unlocked: tell the driver, but don't give up. The link comes back as soon
                    // as the car is in reach (an event, no timers: the CPU may sleep meanwhile), and the
                    // lock goes then. Android reporting the car really gone ends the visit instead
                    warnIfLeftOpen()
                    EventLog.log("Smart: waiting up to ${LEFT_OPEN_WAIT_MS / 60_000} min for the link, to lock")
                    if (wakeLock.isHeld) wakeLock.release()
                    back = withTimeoutOrNull(LEFT_OPEN_WAIT_MS) {
                        controller.state.first { it is LinkState.Ready && it.serial == serial }
                    } != null
                }
                if (!back) return finish("link lost", if (logic.phase == Phase.APPROACHING) Rest.NONE else Rest.AFTER_VISIT)
                EventLog.log("Smart: link back")
                continue
            }
            val suspended = app.smart.suspendedAt.value != null
            // The thresholds may change during the visit (the driver tuning them by the car)
            logic.unlockDbm = SmartEntry.unlockThreshold(app, now)
            logic.lockDbm = maxOf(app.smart.lockDbm.value, logic.unlockDbm + SmartEntryLogic.MIN_GAP)
            val rssi = withTimeoutOrNull(RSSI_TIMEOUT_MS) { controller.readLinkRssi() }
            val phoneUnlocked = !keyguard.isDeviceLocked
            // No sample (reads failing): the time-based decisions still apply
            val decision = when {
                // Walking to and from the car on purpose: measure, never act (and no timeout meanwhile)
                SmartEntry.calibrating -> Decision.NONE.also { if (rssi != null) logic.onSample(rssi, now, phoneUnlocked) }
                rssi != null -> logic.onSample(rssi, now, phoneUnlocked)
                else -> logic.onTick(now)
            }
            val level = logic.level
            if (rssi == null) {
                EventLog.log("Smart: no signal reading")
            } else if (level != null && (loggedLevel == null || abs(level - loggedLevel) >= LOG_STEP_DB || now - loggedAt >= LOG_EVERY_MS)) {
                EventLog.log("Smart: signal -$level dBm (${logic.phase.name.lowercase()})")
                loggedLevel = level
                loggedAt = now
            }
            if (!suspended) SmartEntry.setStatus(SmartStatus.Near(logic.level, logic.phase))
            when (decision) {
                Decision.UNLOCK -> when {
                    suspended -> return finish("suspended, not unlocking", Rest.AFTER_VISIT)
                    now - unlockFailedAt < UNLOCK_RETRY_MS -> Unit
                    else -> {
                        val returning = logic.phase == Phase.LOCKED_NEARBY
                        EventLog.log("Smart: close (-${logic.level} dBm), unlocking" + if (returning) " (came back)" else "")
                        if (act(CarCommand.UNLOCK, HomeAction.UNLOCK)) {
                            logic.onUnlocked(now)
                            leftOpen = name
                            unlockedThisVisit = true
                            SmartNotifications.cancelLocked(this)
                            // One visit is one unlock for the cooldown and the safety net, however many
                            // times the driver comes back (loading the car). The safety net may suspend
                            // here: this visit can still lock, never unlock again
                            if (!returning) SmartEntry.onUnlocked(this)
                            notify(getString(R.string.smart_near_unlocked, name))
                        } else {
                            unlockFailedAt = now
                        }
                    }
                }
                Decision.LOCK -> {
                    EventLog.log("Smart: walking away (-${logic.level} dBm), locking")
                    if (!act(CarCommand.LOCK, HomeAction.LOCK)) {
                        EventLog.log("Smart: the lock failed")
                        warnIfLeftOpen()
                        return finish("lock failed", Rest.AFTER_VISIT)
                    }
                    logic.onLocked(now)
                    leftOpen = null
                    SmartEntry.onCarLocked(this)
                    if (app.smart.confirmLock.value) SmartNotifications.showLocked(this, name)
                    // The cooldown judges visits that unlocked; a lock that only closes a car left open isn't one
                    val nowSuspended = unlockedThisVisit && SmartEntry.onLocked(this)
                    if (nowSuspended || suspended) return finish("locked, suspended", Rest.AFTER_VISIT)
                    // Still in Bluetooth range: a quick return (forgot something) unlocks again for a while
                    logic.unlockDbm = SmartEntry.unlockThreshold(app, System.currentTimeMillis())
                    val minutes = app.smart.returnWindowMin.value
                    EventLog.log("Smart: locked, unlocking again if you come back within $minutes min (-${logic.unlockDbm} dBm)")
                    notify(getString(R.string.smart_near_locked, name, minutes))
                }
                Decision.GIVE_UP -> return if (logic.phase == Phase.LOCKED_NEARBY) {
                    finish("locked, didn't come back", Rest.AFTER_VISIT)
                } else {
                    // Near the car for long without coming close (parked by the house): only a real
                    // departure ends this rest, or every late "in range" would start another 5 minutes
                    finish("near the car without coming close", Rest.UNTIL_GONE)
                }
                Decision.NONE -> {
                    val wanting = logic.phase == Phase.APPROACHING && logic.level?.let { it <= logic.unlockDbm } == true
                    if (wanting && !phoneUnlocked && !waitingForPhone) {
                        waitingForPhone = true
                        EventLog.log("Smart: close enough, waiting for the phone to be unlocked")
                        notify(getString(R.string.smart_near_waiting_phone, name))
                    }
                }
            }
            delay(logic.pollMs(now))
        }
    }

    /** Sends a command the way the driver configured it; true when the car took (or may have taken) it. */
    private suspend fun act(command: CarCommand, action: HomeAction): Boolean {
        val result = app.controller.execute(command, app.settings.actionConfig(action))
        return result !is CommandResult.Failed
    }

    /**
     * The visit ends with the car unlocked (link lost before the lock threshold, a failed lock, or
     * Android reporting the car gone): with no link the lock can't be sent, so the driver is told.
     */
    private fun warnIfLeftOpen() {
        val name = leftOpen ?: return
        if (app.smart.leftOpen.value) return
        EventLog.log("Smart: leaving $name unlocked, could not lock: locking it the next time it is in range")
        app.smart.setLeftOpen(true)
        SmartNotifications.showNotLocked(this, name, urgent = app.smart.confirmLock.value)
        SmartEntry.onUsed(this)
    }

    private fun notify(text: String) {
        runCatching { getSystemService(NotificationManager::class.java).notify(SmartNotifications.ACTIVE_ID, SmartNotifications.active(this, text)) }
    }

    private fun finish(reason: String, rest: Rest) {
        when (rest) {
            Rest.NONE -> Unit
            Rest.AFTER_VISIT -> app.smart.startResting(untilGone = false)
            Rest.UNTIL_GONE -> app.smart.startResting(untilGone = true)
        }
        EventLog.log("Smart: done ($reason)")
        job = null
        if (wakeLock.isHeld) wakeLock.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (job != null) {
            // Stopped from outside: Android reported the car gone (it may beat the link loss), or smart
            // unlock turned off. Paused by the driver: the car is left as it is on purpose
            if (!app.smart.paused.value) warnIfLeftOpen()
            EventLog.log("Smart: done (car out of range)")
        }
        job = null
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        running = false
        app.controller.setSmartActive(false)
        SmartEntry.refresh(app)
        super.onDestroy()
    }

    /** How a visit ends; see [SmartEntry.onPresence] for how a rest ends. */
    enum class Rest {
        /** Nothing happened: the next "in range" starts again. */
        NONE,

        /** Done for this visit: ends on a real "out of range", or on an "in range" that comes later. */
        AFTER_VISIT,

        /** Only a real "out of range" ends it (near the car without coming close, a box needing attention). */
        UNTIL_GONE,
    }

    companion object {
        private const val EXTRA_SERIAL = "serial"
        private const val CONNECT_TIMEOUT_MS = 60_000L
        private const val RSSI_TIMEOUT_MS = 3_000L
        /** After a failed unlock, wait before trying again. */
        private const val UNLOCK_RETRY_MS = 10_000L
        /** A dropped link reconnects within seconds while the car is in range. */
        private const val RELINK_MS = 10_000L
        /** With the car left unlocked, how long the visit waits for the link to come back to lock it. */
        private const val LEFT_OPEN_WAIT_MS = 10 * 60_000L
        /** Far longer than any wait between samples. */
        private const val WAKE_MS = 10 * 60_000L
        private const val LOG_STEP_DB = 3
        private const val LOG_EVERY_MS = 30_000L

        /** Whether a visit is running (the service exists). */
        @Volatile
        var running = false
            private set

        /** From a presence event: the companion association lets the app start this from the background. */
        fun start(context: Context, serial: String) {
            val intent = Intent(context, SmartEntryService::class.java).putExtra(EXTRA_SERIAL, serial)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { EventLog.log("Smart: start refused (${it.javaClass.simpleName})") }
        }

        /** Ends a running visit (car out of range, smart unlock turned off); nothing if none runs. */
        fun stop(context: Context) {
            if (running) context.stopService(Intent(context, SmartEntryService::class.java))
        }
    }
}

private fun LinkState.serialOrNull(): String? = when (this) {
    is LinkState.Ready -> serial
    is LinkState.Problem -> serial
    else -> null
}
