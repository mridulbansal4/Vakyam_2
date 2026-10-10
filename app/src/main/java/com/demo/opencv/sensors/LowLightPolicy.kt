package com.demo.opencv.sensors

/**
 * Pure: front lux → ring light on/off.
 *
 * The ring is drawn on the screen, and the front light sensor sees the screen, so once the ring is on
 * the raw lux includes the ring's own light. The policy measures that contribution shortly after
 * switching on and subtracts it, so the ring cannot switch itself off.
 * Call [update] on every lux event and once per second (lux is on-change, the hold timers are not).
 */
class LowLightPolicy @JvmOverloads constructor(
    private val onBelowLux: Float = 10f,
    private val offAboveLux: Float = 30f,
    private val absoluteOffLux: Float = 150f,
    private val onHoldMs: Long = 3_000L,
    private val offHoldMs: Long = 5_000L,
    private val calibrateAfterMs: Long = 2_000L
) {
    var ringOn = false
        private set
    var ringContributionLux = 0f
        private set

    private var darkSinceMs = -1L
    private var brightSinceMs = -1L
    private var ringOnAtMs = 0L
    private var luxBeforeRing = 0f
    private var calibrated = false

    /** Returns true when [ringOn] changed. */
    fun update(lux: Float, nowMs: Long): Boolean {
        if (lux.isNaN() || lux < 0f) return false
        return if (ringOn) updateWhileOn(lux, nowMs) else updateWhileOff(lux, nowMs)
    }

    /** Room light with the ring's own contribution removed. */
    fun roomLux(lux: Float): Float =
        if (ringOn && !lux.isNaN()) (lux - ringContributionLux).coerceAtLeast(0f) else lux

    fun reset() {
        ringOn = false
        ringContributionLux = 0f
        darkSinceMs = -1L
        brightSinceMs = -1L
        calibrated = false
    }

    private fun updateWhileOff(lux: Float, nowMs: Long): Boolean {
        if (lux >= onBelowLux) {
            darkSinceMs = -1L
            return false
        }
        if (darkSinceMs < 0L) darkSinceMs = nowMs
        if (nowMs - darkSinceMs < onHoldMs) return false
        ringOn = true
        ringOnAtMs = nowMs
        luxBeforeRing = lux
        ringContributionLux = 0f
        calibrated = false
        darkSinceMs = -1L
        brightSinceMs = -1L
        return true
    }

    private fun updateWhileOn(lux: Float, nowMs: Long): Boolean {
        if (!calibrated) {
            if (nowMs - ringOnAtMs < calibrateAfterMs) return false
            ringContributionLux = (lux - luxBeforeRing).coerceIn(0f, MAX_CONTRIBUTION_LUX)
            calibrated = true
        }
        val bright = lux >= absoluteOffLux || lux - ringContributionLux > offAboveLux
        if (!bright) {
            brightSinceMs = -1L
            return false
        }
        if (brightSinceMs < 0L) brightSinceMs = nowMs
        if (nowMs - brightSinceMs < offHoldMs) return false
        reset()
        return true
    }

    companion object {
        // Below absoluteOffLux, so a real room light can always switch the ring off.
        private const val MAX_CONTRIBUTION_LUX = 100f
    }
}
