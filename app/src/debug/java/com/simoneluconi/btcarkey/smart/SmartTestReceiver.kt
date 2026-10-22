package com.simoneluconi.btcarkey.smart

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.simoneluconi.btcarkey.BtCarKeyApp
import com.simoneluconi.btcarkey.EventLog

/**
 * Debug builds only: drives smart unlock's settings and state from the PC, for the automated tests
 * against the ESP32 simulator (tools/esp32-sim, "tx" and "away" console commands):
 *
 *     adb shell am broadcast -n com.simoneluconi.btcarkey/.smart.SmartTestReceiver \
 *         -a com.simoneluconi.btcarkey.SMART_TEST [--ei unlock 60] [--ei lock 70] [--ei window 2] \
 *         [--ez cooldown true] [--ez reset true] [--ez pause true] [--ez resume true]
 *
 * reset clears the rest, the cooldown history, a suspension, a pause and a car marked left open.
 * Every call logs the state.
 * Guarded by android.permission.DUMP, like the key import.
 */
class SmartTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext as BtCarKeyApp
        val smart = app.smart
        if (intent.hasExtra("unlock") || intent.hasExtra("lock")) {
            smart.setThresholds(intent.getIntExtra("unlock", smart.unlockDbm.value), intent.getIntExtra("lock", smart.lockDbm.value))
        }
        if (intent.hasExtra("window")) smart.setReturnWindowMin(intent.getIntExtra("window", smart.returnWindowMin.value))
        if (intent.hasExtra("cooldown")) smart.setCooldown(intent.getBooleanExtra("cooldown", true))
        if (intent.getBooleanExtra("reset", false)) {
            smart.stopResting()
            smart.cycles = emptyList()
            smart.setSuspendedAt(null)
            smart.setPaused(false)
            smart.setLeftOpen(false)
            SmartNotifications.cancelSuspended(context)
            SmartNotifications.cancelNotLocked(context)
            SmartNotifications.cancelPaused(context)
        }
        // The notification's buttons
        if (intent.getBooleanExtra("pause", false)) SmartEntry.pause(context)
        if (intent.getBooleanExtra("resume", false)) SmartEntry.resume(context)
        SmartEntry.refresh(app)
        val state = "unlock -${smart.unlockDbm.value} lock -${smart.lockDbm.value} window ${smart.returnWindowMin.value} min " +
            "cooldown ${smart.cooldown.value} resting ${smart.resting}${if (smart.restingUntilGone) " (until gone)" else ""} " +
            "suspended ${smart.suspendedAt.value != null} paused ${smart.paused.value} left open ${smart.leftOpen.value} " +
            "cycles ${smart.cycles.size} " +
            "visit ${SmartEntryService.running} status ${SmartEntry.status.value}"
        EventLog.log("Smart test: $state")
        resultData = state
    }

    companion object {
        const val ACTION = "com.simoneluconi.btcarkey.SMART_TEST"
    }
}
