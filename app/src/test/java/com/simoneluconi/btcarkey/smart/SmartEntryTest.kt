package com.simoneluconi.btcarkey.smart

import com.simoneluconi.btcarkey.smart.SmartEntryLogic.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartEntryLogicTest {
    private fun logic(requireUnlocked: Boolean = false) =
        SmartEntryLogic(unlockDbm = 65, lockDbm = 80, requirePhoneUnlocked = requireUnlocked, startedAt = 0)

    /** Feeds samples one second apart, returns the decisions. */
    private fun SmartEntryLogic.feed(vararg rssi: Int, phoneUnlocked: Boolean = true, from: Long = 0): List<Decision> =
        rssi.mapIndexed { i, value -> onSample(value, from + i * 1000L, phoneUnlocked) }

    @Test
    fun `unlocks only once the averaged signal is close enough`() {
        val l = logic()
        // Far, then approaching: one strong sample alone isn't enough (noise)
        assertEquals(List(4) { Decision.NONE }, l.feed(-85, -75, -60, -70))
        assertEquals(Decision.UNLOCK, l.feed(-62, -61, from = 5000).last())
    }

    @Test
    fun `locks when walking away, never while close`() {
        val l = logic()
        l.feed(-60, -60, -60)
        l.onUnlocked(now = 2000)
        assertEquals(List(3) { Decision.NONE }, l.feed(-62, -70, -75, from = 3000))
        assertEquals(Decision.LOCK, l.feed(-82, -84, -86, from = 6000).last())
    }

    @Test
    fun `coming back soon after the lock unlocks again, without leaving range`() {
        val l = logic()
        l.feed(-60, -60, -60)
        l.onUnlocked(now = 2000)
        l.feed(-85, -85, -85, from = 3000)
        l.onLocked(now = 6000)
        // Still around, not close: nothing
        assertEquals(Decision.NONE, l.feed(-82, -80, -78, from = 9000).last())
        // Back at the car within the window
        assertEquals(Decision.UNLOCK, l.feed(-60, -58, -58, from = 60_000).last())
        l.onUnlocked(now = 62_000)
        assertEquals(SmartEntryLogic.Phase.UNLOCKED, l.phase)
    }

    @Test
    fun `no return within the window rests`() {
        val l = SmartEntryLogic(65, 80, requirePhoneUnlocked = false, startedAt = 0, returnWindowMs = 2 * 60_000L)
        l.feed(-60, -60, -60)
        l.onUnlocked(now = 2000)
        l.onLocked(now = 10_000)
        assertEquals(Decision.NONE, l.onSample(-85, 60_000, true))
        assertEquals(Decision.GIVE_UP, l.onSample(-85, 10_000 + 2 * 60_000 + 1, true))
    }

    @Test
    fun `time runs out even when no signal sample comes`() {
        val l = SmartEntryLogic(65, 80, requirePhoneUnlocked = false, startedAt = 0, returnWindowMs = 2 * 60_000L)
        assertEquals(Decision.NONE, l.onTick(SmartEntryLogic.APPROACH_TIMEOUT_MS))
        assertEquals(Decision.GIVE_UP, l.onTick(SmartEntryLogic.APPROACH_TIMEOUT_MS + 1))
        l.feed(-60, -60, -60)
        l.onUnlocked(now = 2000)
        // Unlocked: only the signal decides the lock, never the clock
        assertEquals(Decision.NONE, l.onTick(60 * 60_000L))
        l.onLocked(now = 10_000)
        assertEquals(Decision.NONE, l.onTick(10_000 + 2 * 60_000))
        assertEquals(Decision.GIVE_UP, l.onTick(10_000 + 2 * 60_000 + 1))
    }

    @Test
    fun `samples less often after a long time unlocked`() {
        val l = logic()
        assertEquals(SmartEntryLogic.APPROACH_POLL_MS, l.pollMs(0))
        l.feed(-60, -60, -60)
        l.onUnlocked(now = 2000)
        assertEquals(SmartEntryLogic.NEAR_POLL_MS, l.pollMs(3000))
        assertEquals(SmartEntryLogic.LONG_NEAR_POLL_MS, l.pollMs(2000 + SmartEntryLogic.LONG_NEAR_MS + 1))
        l.onLocked(now = 10 * 60_000L)
        assertEquals(SmartEntryLogic.RETURN_POLL_MS, l.pollMs(10 * 60_000L + 1))
    }

    @Test
    fun `a tightened threshold applies to the return`() {
        val l = logic()
        l.feed(-60, -60, -60)
        l.onUnlocked(now = 2000)
        l.onLocked(now = 3000)
        l.unlockDbm = 57 // two pass-bys: come closer
        assertEquals(Decision.NONE, l.feed(-60, -60, -60, from = 6000).last())
        assertEquals(Decision.UNLOCK, l.feed(-55, -55, -55, from = 9000).last())
    }

    @Test
    fun `with the phone-unlocked option it waits for the phone`() {
        val l = logic(requireUnlocked = true)
        assertEquals(Decision.NONE, l.feed(-60, -60, -60, phoneUnlocked = false).last())
        assertEquals(Decision.UNLOCK, l.feed(-60, phoneUnlocked = true, from = 3000).last())
    }

    @Test
    fun `gives up when near the car for long without coming close`() {
        val l = logic()
        assertEquals(Decision.NONE, l.feed(-80, -80, -80).last())
        assertEquals(Decision.GIVE_UP, l.onSample(-80, SmartEntryLogic.APPROACH_TIMEOUT_MS + 1, true))
    }

    @Test
    fun `threshold ranges keep unlock close and lock before the link drops`() {
        assertTrue(SmartEntryLogic.UNLOCK_RANGE.last <= 75)
        assertTrue(SmartEntryLogic.LOCK_RANGE.last < 90)
        assertTrue(SmartEntryLogic.DEFAULT_UNLOCK_DBM in SmartEntryLogic.UNLOCK_RANGE)
        assertTrue(SmartEntryLogic.DEFAULT_LOCK_DBM in SmartEntryLogic.LOCK_RANGE)
    }
}

class PresencePolicyTest {
    private fun report(appeared: Boolean, linked: Boolean = false, restingSince: Long = 0, untilGone: Boolean = false, now: Long = 100_000) =
        PresencePolicy.onReport(appeared, linked, restingSince, untilGone, now)

    @Test
    fun `out of range while linked is the box not advertising, not a departure`() {
        assertEquals(PresencePolicy.Action.IGNORE, report(appeared = false, linked = true))
        assertEquals(PresencePolicy.Action.DEPARTED, report(appeared = false, linked = false))
    }

    @Test
    fun `in range right after a visit is Android catching up`() {
        val restStart = 100_000L
        // Disconnected a second ago: the box advertises again, Android says "in range"
        assertEquals(PresencePolicy.Action.STAY_RESTING, report(appeared = true, restingSince = restStart, now = restStart + 1_000))
        // Much later: Android must have seen it gone meanwhile, the driver is back
        assertEquals(PresencePolicy.Action.ARRIVED, report(appeared = true, restingSince = restStart, now = restStart + PresencePolicy.SETTLE_MS))
    }

    @Test
    fun `a rest until gone ignores arrivals`() {
        assertEquals(PresencePolicy.Action.STAY_RESTING, report(appeared = true, restingSince = 1, untilGone = true, now = 10 * 60 * 60_000L))
        assertEquals(PresencePolicy.Action.DEPARTED, report(appeared = false, restingSince = 1, untilGone = true))
    }

    @Test
    fun `an arrival without a rest starts a visit`() {
        assertEquals(PresencePolicy.Action.ARRIVED, report(appeared = true))
    }

    @Test
    fun `in range while linked is the app's own connection, not an arrival`() {
        assertEquals(PresencePolicy.Action.IGNORE, report(appeared = true, linked = true))
    }
}

class CooldownTest {
    private val minute = 60_000L

    /** A visit: unlocked at [at] minutes, locked [stay] minutes later. */
    private fun visit(cycles: List<Cycle>, at: Long, stay: Long): List<Cycle> =
        Cooldown.onLocked(Cooldown.onUnlocked(cycles, at * minute), (at + stay) * minute)

    @Test
    fun `passing by tightens the unlock threshold, then suspends`() {
        var cycles = emptyList<Cycle>()
        cycles = visit(cycles, at = 0, stay = 1)
        assertEquals(65 - Cooldown.PENALTY_DB, Cooldown.unlockThreshold(65, cycles, 2 * minute))
        assertFalse(Cooldown.shouldSuspend(cycles, 2 * minute))
        cycles = visit(cycles, at = 5, stay = 1)
        assertEquals(65 - 2 * Cooldown.PENALTY_DB, Cooldown.unlockThreshold(65, cycles, 7 * minute))
        cycles = visit(cycles, at = 10, stay = 1)
        assertTrue(Cooldown.shouldSuspend(cycles, 12 * minute))
    }

    @Test
    fun `real use clears the history`() {
        var cycles = emptyList<Cycle>()
        cycles = visit(cycles, at = 0, stay = 1)
        cycles = visit(cycles, at = 5, stay = 1)
        // Stayed by the car for a while: real use
        cycles = visit(cycles, at = 10, stay = 20)
        assertEquals(0, Cooldown.recentPassBys(cycles, 31 * minute))
        assertEquals(65, Cooldown.unlockThreshold(65, cycles, 31 * minute))
        // Driving away with the link up until it dropped: real use too
        cycles = Cooldown.onUsed(Cooldown.onUnlocked(visit(cycles, at = 40, stay = 1), 45 * minute), 50 * minute)
        assertEquals(0, Cooldown.recentPassBys(cycles, 51 * minute))
    }

    @Test
    fun `old pass-bys stop counting`() {
        var cycles = emptyList<Cycle>()
        cycles = visit(cycles, at = 0, stay = 1)
        cycles = visit(cycles, at = 5, stay = 1)
        val later = 5 * minute + Cooldown.WINDOW_MS + minute
        assertEquals(0, Cooldown.recentPassBys(cycles, later))
        assertFalse(Cooldown.shouldSuspend(visit(cycles, at = later / minute, stay = 1), later + minute))
    }

    @Test
    fun `safety net catches unlocks piling up without locks`() {
        var cycles = emptyList<Cycle>()
        // Unlock after unlock, never locked (what a presence loop would do)
        for (i in 0 until Cooldown.RUNAWAY_UNLOCKS - 1) cycles = Cooldown.onUnlocked(cycles, i * 20_000L)
        assertFalse(Cooldown.runaway(cycles, 60_000L))
        cycles = Cooldown.onUnlocked(cycles, 80_000L)
        assertTrue(Cooldown.runaway(cycles, 80_000L))
        // Real use (the link dropped while unlocked) clears the pass-bys, not the safety net's count
        var dropped = emptyList<Cycle>()
        for (i in 0 until Cooldown.RUNAWAY_UNLOCKS) dropped = Cooldown.onUsed(Cooldown.onUnlocked(dropped, i * 60_000L), i * 60_000L + 10_000)
        assertTrue(Cooldown.runaway(dropped, 4 * 60_000L))
        assertEquals(0, Cooldown.recentPassBys(dropped, 4 * 60_000L))
        // Spread over more than the window: fine
        val spread = (0 until Cooldown.RUNAWAY_UNLOCKS).fold(emptyList<Cycle>()) { acc, i -> visit(acc, at = i * 15L, stay = 20) }
        assertFalse(Cooldown.runaway(spread, Cooldown.RUNAWAY_UNLOCKS * 15 * minute))
    }

    @Test
    fun `a suspension ends by itself`() {
        assertFalse(Cooldown.suspensionOver(0, Cooldown.SUSPEND_MS - 1))
        assertTrue(Cooldown.suspensionOver(0, Cooldown.SUSPEND_MS))
    }

    @Test
    fun `cycles survive storage`() {
        val cycles = listOf(Cycle(1, 2, false), Cycle(3, null, false), Cycle(4, 9, true))
        assertEquals(cycles, cycles.map { Cycle.decode(it.encode()) })
    }
}
