package com.simoneluconi.btcarkey.smart

/**
 * The decisions of smart unlock, from the link's signal as the phone measures it. Pure logic, one
 * instance per arrival at the car:
 * - APPROACHING: unlock once the signal is stronger than [unlockDbm] (averaged over a few samples,
 *   BLE signal is noisy) and, if required, the phone is unlocked; give up after [approachTimeoutMs]
 *   (the car is near but the driver isn't coming: parked by the house);
 * - UNLOCKED: lock once the signal is weaker than [lockDbm];
 * - LOCKED_NEARBY: just locked, the driver may come back (forgot something) without ever leaving
 *   Bluetooth range, so Android won't report a new arrival: for [returnWindowMs] coming close again
 *   unlocks again; after that, give up (rest until the car has been out of range once).
 *
 * Thresholds are positive numbers meaning -dBm (65 = -65 dBm): smaller is closer.
 */
class SmartEntryLogic(
    unlockDbm: Int,
    lockDbm: Int,
    private val requirePhoneUnlocked: Boolean,
    private val startedAt: Long,
    private val approachTimeoutMs: Long = APPROACH_TIMEOUT_MS,
    private val returnWindowMs: Long = DEFAULT_RETURN_WINDOW_MIN * 60_000L,
) {
    enum class Phase { APPROACHING, UNLOCKED, LOCKED_NEARBY }

    enum class Decision { NONE, UNLOCK, LOCK, GIVE_UP }

    var phase = Phase.APPROACHING
        private set

    /** Follow the settings during the visit; the unlock one is tightened by the cooldown. */
    var unlockDbm: Int = unlockDbm
    var lockDbm: Int = lockDbm

    private var lockedAt = 0L
    private var unlockedAt = 0L
    private val window = ArrayDeque<Int>()

    /**
     * Milliseconds until the next sample: quick while approaching or maybe coming back (the unlock
     * should come before the driver reaches the door); slower once unlocked; slower still after a
     * long time unlocked (driving with the phone in the car).
     */
    fun pollMs(now: Long): Long = when (phase) {
        Phase.APPROACHING -> APPROACH_POLL_MS
        Phase.UNLOCKED -> if (now - unlockedAt > LONG_NEAR_MS) LONG_NEAR_POLL_MS else NEAR_POLL_MS
        Phase.LOCKED_NEARBY -> RETURN_POLL_MS
    }

    /** The time-based decisions alone, for when no signal sample comes (reads failing). */
    fun onTick(now: Long): Decision = when (phase) {
        Phase.APPROACHING -> if (now - startedAt > approachTimeoutMs) Decision.GIVE_UP else Decision.NONE
        Phase.LOCKED_NEARBY -> if (now - lockedAt > returnWindowMs) Decision.GIVE_UP else Decision.NONE
        Phase.UNLOCKED -> Decision.NONE
    }

    /** Average of the last samples, -dBm; null until the window is full. */
    val level: Int? get() = if (window.size < WINDOW) null else Math.round(window.average()).toInt()

    /**
     * One signal sample ([rssi] as Android reports it, negative dBm). [phoneUnlocked]: the phone isn't
     * on its lock screen right now.
     */
    fun onSample(rssi: Int, now: Long, phoneUnlocked: Boolean): Decision {
        window.addLast(if (rssi < 0) -rssi else rssi)
        if (window.size > WINDOW) window.removeFirst()
        val level = level
        val close = level != null && level <= unlockDbm && (phoneUnlocked || !requirePhoneUnlocked)
        return when (phase) {
            Phase.APPROACHING, Phase.LOCKED_NEARBY -> if (close) Decision.UNLOCK else onTick(now)
            Phase.UNLOCKED -> if (level != null && level >= lockDbm) Decision.LOCK else Decision.NONE
        }
    }

    /** The car took the unlock: watch for the driver walking away. */
    fun onUnlocked(now: Long) {
        phase = Phase.UNLOCKED
        unlockedAt = now
        window.clear()
    }

    /** Locked: watch for a quick return for a while. */
    fun onLocked(now: Long) {
        phase = Phase.LOCKED_NEARBY
        lockedAt = now
        window.clear()
    }

    companion object {
        /** Samples averaged per decision. */
        const val WINDOW = 3
        const val APPROACH_POLL_MS = 1_000L
        const val NEAR_POLL_MS = 2_000L
        /** As quick as approaching: it only lasts the return window. */
        const val RETURN_POLL_MS = 1_000L
        /** After this long unlocked (likely driving), sample less often: walking away still locks within seconds. */
        const val LONG_NEAR_MS = 3 * 60_000L
        const val LONG_NEAR_POLL_MS = 6_000L
        /** Near the car but never close enough: stop and rest until the car is out of range once. */
        const val APPROACH_TIMEOUT_MS = 5 * 60_000L
        /** Lock must sit further than unlock, with some margin, or the car would toggle. */
        const val MIN_GAP = 6
        const val DEFAULT_UNLOCK_DBM = 65
        const val DEFAULT_LOCK_DBM = 80
        /** Minutes after a lock during which coming back unlocks again; the choices offered. */
        const val DEFAULT_RETURN_WINDOW_MIN = 5
        val RETURN_WINDOW_CHOICES = listOf(2, 5, 10)

        /**
         * Unlock only close to the car: never weaker than -75 dBm (a few metres; at tens of metres
         * the signal is far weaker than that).
         */
        val UNLOCK_RANGE = 45..75

        /**
         * Lock while still connected: the link drops around -90/-95 dBm, and with no link there is no
         * way to send the lock. So the lock threshold stays well before that.
         */
        val LOCK_RANGE = 60..88
    }
}
