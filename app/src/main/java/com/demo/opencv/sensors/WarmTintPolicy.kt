package com.demo.opencv.sensors

import kotlin.math.abs

/** Pure: CCT stream → smoothed Kelvin + overlay alpha. Interpolates in mired space. */
class WarmTintPolicy @JvmOverloads constructor(
    private val warmKelvin: Float = 2700f,
    private val neutralKelvin: Float = 5000f,
    private val maxAlpha: Float = 0.14f,
    private val smoothing: Float = 0.2f,
    private val minStep: Float = 0.01f
) {
    var smoothedKelvin = Float.NaN
        private set
    var alpha = 0f
        private set

    /** @return true when [alpha] changed enough to repaint. */
    fun update(kelvin: Float): Boolean {
        if (kelvin.isNaN() || kelvin < CctReading.MIN_K || kelvin > CctReading.MAX_K) return false
        val mired = 1_000_000f / kelvin
        val smoothedMired = if (smoothedKelvin.isNaN()) mired else {
            val previous = 1_000_000f / smoothedKelvin
            previous + smoothing * (mired - previous)
        }
        smoothedKelvin = 1_000_000f / smoothedMired

        val neutralMired = 1_000_000f / neutralKelvin
        val warmMired = 1_000_000f / warmKelvin
        val t = ((smoothedMired - neutralMired) / (warmMired - neutralMired)).coerceIn(0f, 1f)
        val target = t * maxAlpha
        if (abs(target - alpha) < minStep) return false
        alpha = target
        return true
    }

    fun reset() {
        smoothedKelvin = Float.NaN
        alpha = 0f
    }
}
