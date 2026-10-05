package com.vakyam.app.gaze

import kotlin.math.PI
import kotlin.math.abs

/**
 * One-Euro filter (Casiez, Roussel, Vogel 2012): a low-pass filter whose cutoff rises with the
 * signal's speed. While the eye fixates, the cutoff sits at [minCutoffHz] and removes
 * micro-saccade / landmark jitter; during a real saccade the cutoff opens up so the pointer does
 * not lag behind the eye.
 *
 * @param minCutoffHz cutoff when the signal is still. Lower = steadier but laggier.
 * @param beta how fast the cutoff grows with speed (in signal units per second). Higher = snappier.
 * @param derivativeCutoffHz cutoff for the speed estimate itself.
 */
class OneEuroFilter(
    private val minCutoffHz: Float,
    private val beta: Float,
    private val derivativeCutoffHz: Float = 1f,
) {
    private var initialised = false
    private var lastValue = 0f
    private var lastDerivative = 0f
    private var lastTimestampMs = 0L

    fun filter(value: Float, timestampMs: Long): Float {
        if (!initialised) {
            initialised = true
            lastValue = value
            lastDerivative = 0f
            lastTimestampMs = timestampMs
            return value
        }
        val dt = (timestampMs - lastTimestampMs) / 1000f
        if (dt <= 0f) return lastValue
        lastTimestampMs = timestampMs

        val rawDerivative = (value - lastValue) / dt
        val derivative = lerp(lastDerivative, rawDerivative, alpha(derivativeCutoffHz, dt))
        val cutoff = minCutoffHz + beta * abs(derivative)
        val filtered = lerp(lastValue, value, alpha(cutoff, dt))

        lastDerivative = derivative
        lastValue = filtered
        return filtered
    }

    fun reset() {
        initialised = false
    }

    private fun alpha(cutoffHz: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoffHz)
        return 1f / (1f + tau / dt)
    }

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t
}
