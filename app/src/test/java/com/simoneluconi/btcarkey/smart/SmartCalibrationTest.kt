package com.simoneluconi.btcarkey.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartCalibrationTest {
    @Test
    fun `thresholds come with margins from the two spots`() {
        // By the door about -50, at the lock spot about -78 (as Android reports them)
        val p = SmartCalibration.propose(List(20) { -50 + it % 3 - 1 }, List(20) { -78 + it % 5 - 2 })!!
        assertEquals(50, p.doorDbm)
        assertEquals(78, p.awayDbm)
        assertEquals(50 + SmartCalibration.DOOR_MARGIN_DB, p.unlockDbm)
        assertEquals(78 - SmartCalibration.AWAY_MARGIN_DB, p.lockDbm)
        assertFalse(p.tooClose)
    }

    @Test
    fun `outliers don't move the result`() {
        val door = List(18) { 55 } + listOf(30, 95)
        assertEquals(55, SmartCalibration.propose(door, List(20) { 80 })!!.doorDbm)
    }

    @Test
    fun `spots too alike are flagged, and lock still sits beyond unlock`() {
        val p = SmartCalibration.propose(List(20) { 60 }, List(20) { 63 })!!
        assertTrue(p.tooClose)
        assertTrue(p.lockDbm >= p.unlockDbm + SmartEntryLogic.MIN_GAP)
    }

    @Test
    fun `results stay within the allowed ranges`() {
        // A weak door reading can't push unlock beyond its range, a very far spot can't push lock beyond the link's end
        val p = SmartCalibration.propose(List(20) { 80 }, List(20) { 99 })!!
        assertTrue(p.unlockDbm in SmartEntryLogic.UNLOCK_RANGE)
        assertTrue(p.lockDbm in SmartEntryLogic.LOCK_RANGE)
        val near = SmartCalibration.propose(List(20) { 30 }, List(20) { 45 })!!
        assertTrue(near.unlockDbm in SmartEntryLogic.UNLOCK_RANGE)
        assertTrue(near.lockDbm in SmartEntryLogic.LOCK_RANGE)
        assertTrue(near.lockDbm >= near.unlockDbm + SmartEntryLogic.MIN_GAP)
    }

    @Test
    fun `no samples, no proposal`() {
        assertNull(SmartCalibration.propose(emptyList(), List(20) { 70 }))
    }
}
