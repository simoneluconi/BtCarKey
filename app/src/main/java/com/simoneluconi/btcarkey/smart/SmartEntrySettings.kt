package com.simoneluconi.btcarkey.smart

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Smart unlock preferences; one car at a time. */
class SmartEntrySettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("smart_entry", Context.MODE_PRIVATE)

    private val _enabledSerial = MutableStateFlow(prefs.getString(ENABLED, null))
    /** The car smart unlock works for; null = off. */
    val enabledSerial: StateFlow<String?> = _enabledSerial.asStateFlow()

    private val _unlockDbm = MutableStateFlow(prefs.getInt(UNLOCK, SmartEntryLogic.DEFAULT_UNLOCK_DBM))
    val unlockDbm: StateFlow<Int> = _unlockDbm.asStateFlow()

    private val _lockDbm = MutableStateFlow(prefs.getInt(LOCK, SmartEntryLogic.DEFAULT_LOCK_DBM))
    val lockDbm: StateFlow<Int> = _lockDbm.asStateFlow()

    private val _requirePhoneUnlocked = MutableStateFlow(prefs.getBoolean(REQUIRE_UNLOCKED, false))
    /** Unlock only while the phone itself is unlocked: a relay attacker can't wake it. */
    val requirePhoneUnlocked: StateFlow<Boolean> = _requirePhoneUnlocked.asStateFlow()

    /**
     * After locking (or giving up), nothing happens until the car has been out of range once: the
     * next unlock is for the next arrival, not for walking around the car. When the rest began
     * (millis), 0 when not resting: see [SmartEntry.onPresence] for how it ends.
     */
    var restingSince: Long
        get() = prefs.getLong(RESTING_SINCE, 0L)
        set(value) = prefs.edit { putLong(RESTING_SINCE, value) }

    val resting: Boolean get() = restingSince > 0

    /** The rest ends only on a real "out of range" (not on a late "in range"). */
    val restingUntilGone: Boolean get() = prefs.getBoolean(RESTING_UNTIL_GONE, false)

    fun startResting(untilGone: Boolean, now: Long = System.currentTimeMillis()) {
        restingSince = now
        prefs.edit { putBoolean(RESTING_UNTIL_GONE, untilGone) }
    }

    fun stopResting() {
        restingSince = 0L
        prefs.edit { putBoolean(RESTING_UNTIL_GONE, false) }
    }

    private val _suspendedAt = MutableStateFlow(prefs.getLong(SUSPENDED_AT, 0L).takeIf { it > 0 })
    /** When the cooldown suspended smart unlock; null when it isn't suspended. */
    val suspendedAt: StateFlow<Long?> = _suspendedAt.asStateFlow()

    fun setSuspendedAt(time: Long?) {
        prefs.edit { putLong(SUSPENDED_AT, time ?: 0L) }
        _suspendedAt.value = time
    }

    private val _paused = MutableStateFlow(prefs.getBoolean(PAUSED, false))
    /** Paused by the driver (the notification's button): nothing automatic until they resume it. */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    fun setPaused(value: Boolean) {
        prefs.edit { putBoolean(PAUSED, value) }
        _paused.value = value
    }

    private val _leftOpen = MutableStateFlow(prefs.getBoolean(LEFT_OPEN, false))
    /**
     * A visit ended with the car unlocked (link lost before the lock): the next time the car is in
     * range, smart unlock connects to lock it, whatever the rest says. Cleared by any lock.
     */
    val leftOpen: StateFlow<Boolean> = _leftOpen.asStateFlow()

    fun setLeftOpen(value: Boolean) {
        prefs.edit { putBoolean(LEFT_OPEN, value) }
        _leftOpen.value = value
    }

    /** Recent automatic visits, for the cooldown ([Cooldown]). */
    var cycles: List<Cycle>
        get() = prefs.getString(CYCLES, null)?.split(",")?.mapNotNull(Cycle::decode).orEmpty()
        set(value) = prefs.edit { putString(CYCLES, value.joinToString(",") { it.encode() }) }

    fun setEnabledSerial(serial: String?) {
        prefs.edit { putString(ENABLED, serial) }
        _enabledSerial.value = serial
        stopResting()
        setSuspendedAt(null)
        setPaused(false)
        setLeftOpen(false)
        cycles = emptyList()
    }

    /** Kept within [SmartEntryLogic.UNLOCK_RANGE] / [SmartEntryLogic.LOCK_RANGE], lock further than unlock. */
    fun setThresholds(unlockDbm: Int, lockDbm: Int) {
        val unlock = unlockDbm.coerceIn(SmartEntryLogic.UNLOCK_RANGE)
        val lock = maxOf(lockDbm, unlock + SmartEntryLogic.MIN_GAP).coerceIn(SmartEntryLogic.LOCK_RANGE)
        prefs.edit {
            putInt(UNLOCK, unlock)
            putInt(LOCK, lock)
        }
        _unlockDbm.value = unlock
        _lockDbm.value = lock
    }

    private val _returnWindowMin = MutableStateFlow(prefs.getInt(RETURN_WINDOW, SmartEntryLogic.DEFAULT_RETURN_WINDOW_MIN))
    /** Minutes after a lock during which coming back unlocks again ([SmartEntryLogic]). */
    val returnWindowMin: StateFlow<Int> = _returnWindowMin.asStateFlow()

    fun setReturnWindowMin(minutes: Int) {
        prefs.edit { putInt(RETURN_WINDOW, minutes) }
        _returnWindowMin.value = minutes
    }

    private val _cooldown = MutableStateFlow(prefs.getBoolean(COOLDOWN, true))
    /** Tell pass-bys from real use and slow down / suspend on pass-bys ([Cooldown]). On by default. */
    val cooldown: StateFlow<Boolean> = _cooldown.asStateFlow()

    fun setCooldown(value: Boolean) {
        prefs.edit { putBoolean(COOLDOWN, value) }
        _cooldown.value = value
        if (!value) {
            cycles = emptyList()
            setSuspendedAt(null)
        }
    }

    fun setRequirePhoneUnlocked(value: Boolean) {
        prefs.edit { putBoolean(REQUIRE_UNLOCKED, value) }
        _requirePhoneUnlocked.value = value
    }

    private companion object {
        const val ENABLED = "enabled_serial"
        const val UNLOCK = "unlock_dbm"
        const val LOCK = "lock_dbm"
        const val REQUIRE_UNLOCKED = "require_phone_unlocked"
        const val RESTING_SINCE = "resting_since"
        const val RESTING_UNTIL_GONE = "resting_until_gone"
        const val SUSPENDED_AT = "suspended_at"
        const val PAUSED = "paused"
        const val LEFT_OPEN = "left_open"
        const val COOLDOWN = "cooldown"
        const val RETURN_WINDOW = "return_window_min"
        const val CYCLES = "cycles"
    }
}
