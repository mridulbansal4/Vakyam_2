package com.demo.opencv.vision

import kotlin.math.PI
import kotlin.math.abs

/**
 * 1€ (One-Euro) Filter for low-latency, jitter-resistant filtering of gaze signals in motion.
 *
 * In a vibrating vehicle or wheelchair:
 * - When the user is fixating (low speed), the filter acts with a low cutoff frequency (minCutoff),
 *   effectively filtering out high-frequency mechanical road bumps and sensor jitter.
 * - When the user performs an intentional saccadic movement (high speed), the cutoff frequency
 *   scales dynamically with speed (beta * |speed|), eliminating lag and making gestures instantaneous.
 *
 * Reference: Casiez et al., "1€ Filter: A Simple Speed-based Low-pass Filter for Noisy Input in HCI"
 */
class OneEuroFilter @JvmOverloads constructor(
    private val minCutoff: Double = 1.0,  // Hz: cutoff frequency at zero speed (lower = smoother)
    private val beta: Double = 0.5,       // speed coefficient (higher = faster response to saccades)
    private val dCutoff: Double = 1.0     // Hz: cutoff frequency for derivative calculation
) {
    private var xPrev: Double? = null
    private var dxPrev: Double = 0.0
    private var tPrev: Double? = null

    fun reset() {
        xPrev = null
        dxPrev = 0.0
        tPrev = null
    }

    private fun alpha(rate: Double, cutoff: Double): Double {
        val tau = 1.0 / (2.0 * PI * cutoff)
        val te = 1.0 / rate
        return 1.0 / (1.0 + tau / te)
    }

    /**
     * Filters a new incoming signal point.
     * @param x Current raw coordinate / ratio.
     * @param timestampMs Timestamp in milliseconds.
     * @return Filtered, jitter-stabilized value.
     */
    fun filter(x: Double, timestampMs: Long): Double {
        val t = timestampMs / 1000.0 // Convert to seconds

        if (tPrev == null || xPrev == null) {
            xPrev = x
            dxPrev = 0.0
            tPrev = t
            return x
        }

        val dtRaw = t - tPrev!!
        if (dtRaw <= 0.0) {
            // Non-monotonic or duplicate timestamp; return previous filtered value without corrupting state
            return xPrev!!
        }
        if (dtRaw > 0.5) {
            // Extended gap (>500ms): reset derivative and reinitialize filter at current position
            xPrev = x
            dxPrev = 0.0
            tPrev = t
            return x
        }
        val dt = dtRaw.coerceAtLeast(1e-4)
        val rate = 1.0 / dt

        // Estimate rate of change (derivative)
        val dx = (x - xPrev!!) / dt
        val aD = alpha(rate, dCutoff)
        val dxHat = aD * dx + (1.0 - aD) * dxPrev

        // Dynamic cutoff frequency based on movement speed
        val cutoff = minCutoff + beta * abs(dxHat)
        val a = alpha(rate, cutoff)
        val xHat = a * x + (1.0 - a) * xPrev!!

        xPrev = xHat
        dxPrev = dxHat
        tPrev = t

        return xHat
    }
}
