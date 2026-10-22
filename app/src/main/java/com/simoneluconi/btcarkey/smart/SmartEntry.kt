package com.simoneluconi.btcarkey.smart

import android.content.Context
import com.simoneluconi.btcarkey.BtCarKeyApp
import com.simoneluconi.btcarkey.EventLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What smart unlock is doing right now, for the screen. */
sealed interface SmartStatus {
    data object Off : SmartStatus

    /** Waiting for the system to report the car in range. */
    data object Watching : SmartStatus

    /** The car is in range: connected, measuring the signal ([level], -dBm). */
    data class Near(val level: Int?, val phase: SmartEntryLogic.Phase) : SmartStatus

    /** Done for this visit: waits for the car to be out of range once. */
    data object Resting : SmartStatus

    /** Paused by the cooldown until [until] or until the driver resumes it. */
    data class Suspended(val until: Long) : SmartStatus

    /** Paused by the driver until they resume it. */
    data object Paused : SmartStatus
}

/**
 * Smart unlock's coordinator: presence events from the system ([SmartPresenceService]) start or stop
 * [SmartEntryService]; [Cooldown] tells pass-bys from real use and suspends it when the car keeps
 * being unlocked and locked for nothing.
 */
object SmartEntry {
    private val _status = MutableStateFlow<SmartStatus>(SmartStatus.Off)
    val status: StateFlow<SmartStatus> = _status.asStateFlow()

    internal fun setStatus(status: SmartStatus) {
        _status.value = status
    }

    /** Whether the system last reported the car in range; null until it said anything in this process. */
    @Volatile
    private var present: Boolean? = null

    /** Recomputes the idle status (app start, settings changed); a visit in progress sets its own. */
    fun refresh(app: BtCarKeyApp) {
        val smart = app.smart
        expireSuspension(app)
        val suspendedAt = smart.suspendedAt.value
        if (SmartEntryService.running && smart.enabledSerial.value != null && !smart.paused.value) return
        _status.value = when {
            smart.enabledSerial.value == null -> SmartStatus.Off
            smart.paused.value -> SmartStatus.Paused
            suspendedAt != null -> SmartStatus.Suspended(suspendedAt + Cooldown.SUSPEND_MS)
            smart.resting -> SmartStatus.Resting
            else -> SmartStatus.Watching
        }
    }

    /** The system says the device at [mac] came into range ([appeared]) or left it; see [PresencePolicy]. */
    fun onPresence(context: Context, mac: String?, appeared: Boolean) {
        val app = context.applicationContext as BtCarKeyApp
        val serial = app.smart.enabledSerial.value ?: return
        val device = app.store.device(serial) ?: return
        if (mac == null || !device.mac.equals(mac, ignoreCase = true)) {
            EventLog.log("Smart: presence report for $mac, not ${device.name} at ${device.mac}: ignored")
            return
        }
        val linked = app.controller.isLinkedTo(serial)
        // A linked box doesn't advertise: "gone" then says nothing about where the car is
        present = appeared || linked
        expireSuspension(app)
        val action = PresencePolicy.onReport(
            appeared = appeared,
            linked = linked,
            restingSince = app.smart.restingSince,
            untilGone = app.smart.restingUntilGone,
            now = System.currentTimeMillis(),
        )
        EventLog.log("Smart: Android reports ${device.name} ${if (appeared) "in" else "out of"} range: $action")
        // The car may have been left unlocked: any chance to reach it is taken, to lock it
        if (appeared && !linked && app.smart.leftOpen.value && !app.smart.paused.value && action != PresencePolicy.Action.ARRIVED) {
            EventLog.log("Smart: ${device.name} may still be unlocked, connecting to lock it")
            app.smart.stopResting()
            SmartEntryService.start(context, serial)
            return
        }
        when (action) {
            PresencePolicy.Action.IGNORE, PresencePolicy.Action.STAY_RESTING -> Unit
            PresencePolicy.Action.DEPARTED -> {
                app.smart.stopResting()
                SmartEntryService.stop(context)
                refresh(app)
            }
            PresencePolicy.Action.ARRIVED -> {
                app.smart.stopResting()
                when {
                    app.smart.paused.value -> EventLog.log("Smart: paused by you, not acting")
                    // Suspended still locks a car left open, it never unlocks
                    app.smart.suspendedAt.value != null && !app.smart.leftOpen.value -> EventLog.log("Smart: suspended, not acting")
                    else -> SmartEntryService.start(context, serial)
                }
            }
        }
    }

    /** The box got a new address: the companion association is for the old one, so smart unlock stops. */
    fun onMacChanged(context: Context, serial: String, oldMac: String?) {
        val app = context.applicationContext as BtCarKeyApp
        if (app.smart.enabledSerial.value != serial) return
        EventLog.log("Smart: the box changed address, turn smart unlock on again to pair the new one")
        disable(context, oldMac)
        SmartNotifications.showNeedsSetup(context)
    }

    /** A box was removed: smart unlock can't stay pointed at it. */
    fun onRemoved(context: Context, serial: String, mac: String?) {
        val app = context.applicationContext as BtCarKeyApp
        if (app.smart.enabledSerial.value == serial) disable(context, mac)
    }

    /** The car was locked (automatically or from the app): it is no longer possibly left open. */
    fun onCarLocked(context: Context) {
        val app = context.applicationContext as BtCarKeyApp
        if (!app.smart.leftOpen.value) return
        app.smart.setLeftOpen(false)
        SmartNotifications.cancelNotLocked(context)
        EventLog.log("Smart: locked, no longer left open")
    }

    /** The unlock threshold for this arrival (-dBm): closer after recent pass-bys, if the cooldown is on. */
    fun unlockThreshold(app: BtCarKeyApp, now: Long): Int =
        if (app.smart.cooldown.value) Cooldown.unlockThreshold(app.smart.unlockDbm.value, app.smart.cycles, now)
        else app.smart.unlockDbm.value

    /**
     * An automatic unlock happened. Always recorded: besides the (optional) cooldown, a safety net
     * that can't be turned off suspends smart unlock if automatic unlocks pile up for any reason.
     * Returns whether smart unlock is now suspended.
     */
    fun onUnlocked(context: Context): Boolean {
        val app = context.applicationContext as BtCarKeyApp
        val now = System.currentTimeMillis()
        val cycles = Cooldown.onUnlocked(app.smart.cycles, now)
        app.smart.cycles = cycles
        if (Cooldown.runaway(cycles, now)) {
            EventLog.log("Smart: ${Cooldown.RUNAWAY_UNLOCKS} automatic unlocks within ${Cooldown.RUNAWAY_WINDOW_MS / 60_000} minutes, suspending (safety net)")
            suspend(context, now)
            return true
        }
        return false
    }

    /**
     * Locked on the way out: a pass-by if it came quickly, which (with the cooldown on) may suspend
     * smart unlock. Returns whether smart unlock is now suspended.
     */
    fun onLocked(context: Context): Boolean {
        val app = context.applicationContext as BtCarKeyApp
        val now = System.currentTimeMillis()
        val cycles = Cooldown.onLocked(app.smart.cycles, now)
        app.smart.cycles = cycles
        if (!app.smart.cooldown.value) return false
        val passBys = Cooldown.recentPassBys(cycles, now)
        if (passBys > 0) EventLog.log("Smart: looked like a pass-by ($passBys recent), next unlock needs a closer phone")
        if (Cooldown.shouldSuspend(cycles, now)) {
            EventLog.log("Smart: $passBys pass-bys in ${Cooldown.WINDOW_MS / 60_000} minutes, suspending")
            suspend(context, now)
            return true
        }
        return false
    }

    private fun suspend(context: Context, now: Long) {
        val app = context.applicationContext as BtCarKeyApp
        app.smart.setSuspendedAt(now)
        _status.value = SmartStatus.Suspended(now + Cooldown.SUSPEND_MS)
        SmartNotifications.showSuspended(context)
    }

    /** The phone stayed with the car until the link dropped (driving away): real use, history cleared. */
    fun onUsed(context: Context) {
        val app = context.applicationContext as BtCarKeyApp
        app.smart.cycles = Cooldown.onUsed(app.smart.cycles, System.currentTimeMillis())
    }

    /**
     * Turns smart unlock on for [serial] (associated and with a known address): presence watching
     * starts. Legacy comfort entry must already be off (the screen sees to it).
     */
    fun enable(context: Context, serial: String, mac: String): Boolean {
        val app = context.applicationContext as BtCarKeyApp
        if (!CompanionLink.startObserving(context, mac)) return false
        // Stay connected keeps the box linked, so it never advertises and Android can't see it come and go
        if (app.settings.keepConnected.value) {
            EventLog.log("Smart: turning Stay connected off (it would hide the car from Android)")
            app.setKeepConnected(false)
        }
        app.smart.setEnabledSerial(serial)
        EventLog.log("Smart: on for $serial")
        refresh(app)
        return true
    }

    /**
     * Turns smart unlock off (when legacy comfort entry or Stay connected is turned on, the box is
     * removed or its address changed). [mac]: the address it was watched at, if not the saved one.
     */
    fun disable(context: Context, mac: String? = null) {
        val app = context.applicationContext as BtCarKeyApp
        val serial = app.smart.enabledSerial.value ?: return
        (mac ?: app.store.device(serial)?.mac)?.let { CompanionLink.stopObserving(context, it) }
        app.smart.setEnabledSerial(null)
        SmartEntryService.stop(context)
        SmartNotifications.cancelSuspended(context)
        SmartNotifications.cancelPaused(context)
        EventLog.log("Smart: off")
        refresh(app)
    }

    /**
     * The driver pauses smart unlock (the button of the near-the-car notification): the visit stops
     * at once, the car is left as it is, and nothing automatic happens until [resume].
     */
    fun pause(context: Context) {
        val app = context.applicationContext as BtCarKeyApp
        if (app.smart.enabledSerial.value == null) return
        app.smart.setPaused(true)
        EventLog.log("Smart: paused by you")
        SmartEntryService.stop(context)
        SmartNotifications.showPaused(context)
        refresh(app)
    }

    /** The driver resumes smart unlock after a pause or a cooldown (notification or app). */
    fun resume(context: Context) {
        val app = context.applicationContext as BtCarKeyApp
        val serial = app.smart.enabledSerial.value
        val wasPaused = app.smart.paused.value
        app.smart.setPaused(false)
        app.smart.setSuspendedAt(null)
        app.smart.cycles = emptyList()
        SmartNotifications.cancelSuspended(context)
        SmartNotifications.cancelPaused(context)
        // The pause itself let go of the link a moment ago: the last word on presence still counts
        val nearby = serial != null && (present == true || app.controller.isLinkedTo(serial))
        if (wasPaused && nearby) {
            // Paused by the driver near the car: carry on with this visit (the lock on the way out)
            EventLog.log("Smart: resumed by you, the car is in range")
            app.smart.stopResting()
            SmartEntryService.start(context, serial)
        } else {
            // The car may be right here unnoticed: wait until it has been out of range once before acting again
            EventLog.log("Smart: resumed")
            app.smart.startResting(untilGone = false)
        }
        refresh(app)
    }

    private fun expireSuspension(app: BtCarKeyApp) {
        val at = app.smart.suspendedAt.value ?: return
        if (Cooldown.suspensionOver(at, System.currentTimeMillis())) {
            EventLog.log("Smart: suspension over, active again")
            app.smart.setSuspendedAt(null)
            app.smart.cycles = emptyList()
            SmartNotifications.cancelSuspended(app)
        }
    }
}
