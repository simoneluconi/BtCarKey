package com.simoneluconi.btcarkey.smart

/**
 * How Android's presence reports are read. Pure logic.
 *
 * A connected box stops advertising, and Android's presence watching is based on advertising: while
 * the phone is connected, Android soon reports the car "out of range" although it is right here, and
 * once the phone disconnects it reports it "in range" again within a second or two. So:
 * - any report while linked to the car is ignored (the live link is the better witness). Before
 *   Android 16 the system also reports the phone's own connection as "in range", and the app being
 *   opened near the car is no arrival;
 * - "out of range" otherwise is a real departure: it ends any rest;
 * - "in range" during a rest is Android catching up if it comes within [SETTLE_MS] of the rest's
 *   start; later, Android must have seen the car absent meanwhile: the driver left and is back. A rest
 *   that must end only on a real departure ([untilGone]) ignores "in range" altogether.
 */
object PresencePolicy {
    /** The box advertises again within a second or two of the disconnection: this leaves ample margin. */
    const val SETTLE_MS = 30_000L

    enum class Action {
        /** Ignore the report. */
        IGNORE,

        /** A real departure: end the rest and the visit. */
        DEPARTED,

        /** An arrival while resting: still resting, do nothing. */
        STAY_RESTING,

        /** An arrival: start a visit (ending the rest if there was one). */
        ARRIVED,
    }

    fun onReport(appeared: Boolean, linked: Boolean, restingSince: Long, untilGone: Boolean, now: Long): Action = when {
        linked -> Action.IGNORE
        !appeared -> Action.DEPARTED
        restingSince <= 0 -> Action.ARRIVED
        untilGone -> Action.STAY_RESTING
        now - restingSince >= SETTLE_MS -> Action.ARRIVED
        else -> Action.STAY_RESTING
    }
}
