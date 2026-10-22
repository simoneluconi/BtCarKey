package com.simoneluconi.btcarkey.smart

import com.simoneluconi.btcarkey.protocol.AutoLockConfig

/**
 * Smart unlock's guided calibration: the signal is sampled where the driver stands to open the car
 * (by the door) and where it should be locked (walking away), and the thresholds come from those.
 * Pure logic. Levels are positive numbers meaning -dBm (smaller is closer).
 *
 * - unlock: a few dB weaker than the level by the door, so it triggers there even on a day when the
 *   signal is a bit weaker (phone in a bag, rain);
 * - lock: a few dB stronger than the level at the chosen spot, so it triggers just before it;
 * - both kept within [SmartEntryLogic.UNLOCK_RANGE] / [SmartEntryLogic.LOCK_RANGE], lock at least
 *   [SmartEntryLogic.MIN_GAP] further than unlock.
 */
object SmartCalibration {
    /** Samples per spot, [SAMPLE_MS] apart: about ten seconds standing still. */
    const val SAMPLES = 20
    const val SAMPLE_MS = 500L
    const val DOOR_MARGIN_DB = 5
    const val AWAY_MARGIN_DB = 3

    data class Proposal(
        val doorDbm: Int,
        val awayDbm: Int,
        val unlockDbm: Int,
        val lockDbm: Int,
        /** The two spots read almost the same: the lock spot should be further from the car. */
        val tooClose: Boolean,
    )

    /** Samples as Android reports them (negative dBm) or as levels; null without samples for both spots. */
    fun propose(doorSamples: List<Int>, awaySamples: List<Int>): Proposal? {
        val door = AutoLockConfig.trimmedMean(doorSamples.map(::level)) ?: return null
        val away = AutoLockConfig.trimmedMean(awaySamples.map(::level)) ?: return null
        val unlock = (door + DOOR_MARGIN_DB).coerceIn(SmartEntryLogic.UNLOCK_RANGE)
        // The ranges leave room for the gap: unlock + gap never falls outside the lock range
        val lock = maxOf(away - AWAY_MARGIN_DB, unlock + SmartEntryLogic.MIN_GAP).coerceIn(SmartEntryLogic.LOCK_RANGE)
        return Proposal(
            doorDbm = door,
            awayDbm = away,
            unlockDbm = unlock,
            lockDbm = lock,
            tooClose = away - door < SmartEntryLogic.MIN_GAP + DOOR_MARGIN_DB,
        )
    }

    private fun level(rssi: Int) = if (rssi < 0) -rssi else rssi
}
