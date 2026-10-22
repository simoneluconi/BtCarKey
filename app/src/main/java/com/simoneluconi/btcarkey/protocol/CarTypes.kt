package com.simoneluconi.btcarkey.protocol

/** One-tap car actions, independent of how a protocol encodes them. */
enum class CarCommand { UNLOCK, LOCK, TRUNK, REMOTE_START, DROP_WINDOWS }

/** A button of the car's original remote, as a device can press and release it. */
enum class RemoteButton { UNLOCK, LOCK, TRUNK, FUNCTION }

/** What a device says about itself after authenticating. */
data class DeviceInfo(
    val serial: String,
    val firmware: String? = null,
    /** Model/type in a few words, e.g. "CAR_BOX · NORDIC". */
    val model: String? = null,
)

/** Device status as far as it reported it; null fields weren't reported. */
data class BoxStatus(
    val batteryPercent: Int? = null,
    val voltageMv: Int? = null,
    val charging: Boolean? = null,
    val firmware: String? = null,
    /** Fields the protocol doesn't interpret yet, kept as hex for the log. */
    val unknown: Map<Int, String> = emptyMap(),
) {
    /** Newer values win, missing ones keep the previous value. */
    fun merge(newer: BoxStatus) = BoxStatus(
        batteryPercent = newer.batteryPercent ?: batteryPercent,
        voltageMv = newer.voltageMv ?: voltageMv,
        charging = newer.charging ?: charging,
        firmware = newer.firmware ?: firmware,
        unknown = unknown + newer.unknown,
    )

    val isEmpty: Boolean get() = batteryPercent == null && voltageMv == null && charging == null && firmware == null && unknown.isEmpty()
}

/**
 * Comfort entry ([Feature.PROXIMITY]): the device locks when the phone's signal drops below
 * [lockDbm] and unlocks when it rises above [unlockDbm]. Thresholds are positive numbers meaning
 * -dBm (75 = -75 dBm), so a smaller number means "closer".
 */
data class AutoLockConfig(val enabled: Boolean, val lockDbm: Int, val unlockDbm: Int) {
    companion object {
        /** The original vendor app's defaults. */
        val DEFAULT = AutoLockConfig(enabled = false, lockDbm = 75, unlockDbm = 65)

        /** Lock must sit further than unlock, with some margin, or the car would toggle. */
        const val MIN_GAP = 4

        /**
         * Thresholds from signal samples taken at the car: a trimmed mean (top and bottom 10% dropped),
         * like the vendor's calibration; the lock threshold is moved out when it isn't further than unlock.
         */
        fun calibrate(unlockSamples: List<Int>, lockSamples: List<Int>): AutoLockConfig {
            val unlock = trimmedMean(unlockSamples) ?: DEFAULT.unlockDbm
            var lock = trimmedMean(lockSamples) ?: DEFAULT.lockDbm
            if (lock < unlock + MIN_GAP) lock = unlock + 8
            return AutoLockConfig(enabled = true, lockDbm = lock, unlockDbm = unlock)
        }

        fun trimmedMean(samples: List<Int>): Int? {
            if (samples.isEmpty()) return null
            val sorted = samples.sorted()
            val cut = sorted.size / 10
            val kept = sorted.subList(cut, sorted.size - cut)
            return Math.round(kept.average()).toInt()
        }
    }
}
