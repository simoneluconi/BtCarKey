package com.simoneluconi.btcarkey.smart

/**
 * One automatic unlock and, if it came, the automatic lock that followed. [lockedAt] is null while
 * the car is unlocked; [used] marks a visit that looked like real use (see [Cooldown]).
 */
data class Cycle(val unlockedAt: Long, val lockedAt: Long? = null, val used: Boolean = false) {
    fun encode(): String = "$unlockedAt:${lockedAt ?: ""}:${if (used) 1 else 0}"

    companion object {
        fun decode(text: String): Cycle? {
            val parts = text.split(":")
            if (parts.size != 3) return null
            return Cycle(parts[0].toLongOrNull() ?: return null, parts[1].toLongOrNull(), parts[2] == "1")
        }
    }
}

/**
 * Walking past the car again and again must not unlock and lock it each time. Instead of a fixed
 * counter, it looks at how each visit went:
 * - an unlock followed by a lock within [SHORT_VISIT_MS] is a *pass-by* (the car wasn't used);
 * - a longer visit, or one where the link stayed up until the phone left with the car, is *real use*
 *   and clears the history;
 * - each recent pass-by makes the next unlock need a stronger signal ([PENALTY_DB] closer), so passing
 *   by soon stops triggering it at all;
 * - [MAX_PASS_BYS] pass-bys within [WINDOW_MS] suspend smart unlock (notification, Resume button);
 *   a suspension also ends by itself after [SUSPEND_MS].
 *
 * Pure logic: the caller stores the cycles.
 */
object Cooldown {
    const val SHORT_VISIT_MS = 3 * 60_000L
    const val WINDOW_MS = 30 * 60_000L
    const val MAX_PASS_BYS = 3
    const val PENALTY_DB = 4
    const val SUSPEND_MS = 3 * 60 * 60_000L

    fun isPassBy(cycle: Cycle): Boolean =
        !cycle.used && cycle.lockedAt != null && cycle.lockedAt - cycle.unlockedAt < SHORT_VISIT_MS

    /** Pass-bys since the last real use, within the window. */
    fun recentPassBys(cycles: List<Cycle>, now: Long): Int =
        cycles.takeLastWhile { !it.used }.count { isPassBy(it) && now - it.unlockedAt <= WINDOW_MS }

    /** The unlock threshold for the next arrival: closer after each recent pass-by (-dBm, smaller is closer). */
    fun unlockThreshold(base: Int, cycles: List<Cycle>, now: Long): Int = base - PENALTY_DB * recentPassBys(cycles, now)

    fun shouldSuspend(cycles: List<Cycle>, now: Long): Boolean = recentPassBys(cycles, now) >= MAX_PASS_BYS

    fun suspensionOver(suspendedAt: Long, now: Long): Boolean = now - suspendedAt >= SUSPEND_MS

    /** Safety net, always on: this many automatic unlocks within [RUNAWAY_WINDOW_MS] means something is wrong. */
    const val RUNAWAY_UNLOCKS = 4
    const val RUNAWAY_WINDOW_MS = 10 * 60_000L

    fun runaway(cycles: List<Cycle>, now: Long): Boolean =
        cycles.count { now - it.unlockedAt <= RUNAWAY_WINDOW_MS } >= RUNAWAY_UNLOCKS

    /**
     * Keeps what still matters: the window. Cycles before the last real use stay too, since the safety
     * net counts every unlock ([recentPassBys] already stops at real use).
     */
    fun trim(cycles: List<Cycle>, now: Long): List<Cycle> =
        cycles.filter { now - it.unlockedAt <= maxOf(WINDOW_MS, RUNAWAY_WINDOW_MS) }

    fun onUnlocked(cycles: List<Cycle>, now: Long): List<Cycle> = trim(cycles, now) + Cycle(unlockedAt = now)

    /** A lock after a long visit counts as real use too. */
    fun onLocked(cycles: List<Cycle>, now: Long): List<Cycle> =
        closeLast(cycles) { it.copy(lockedAt = now, used = now - it.unlockedAt >= SHORT_VISIT_MS) }

    /** The phone stayed with the car until the link dropped (driving away), or the visit was long: real use. */
    fun onUsed(cycles: List<Cycle>, now: Long): List<Cycle> = closeLast(cycles) { it.copy(lockedAt = it.lockedAt ?: now, used = true) }

    private fun closeLast(cycles: List<Cycle>, change: (Cycle) -> Cycle): List<Cycle> {
        val last = cycles.lastOrNull() ?: return cycles
        return cycles.dropLast(1) + change(last)
    }
}
