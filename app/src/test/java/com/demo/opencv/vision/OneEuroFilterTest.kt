package com.demo.opencv.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class OneEuroFilterTest {

    @Test
    fun testVibrationJitterSmoothing() {
        val filter = OneEuroFilter(minCutoff = 0.8, beta = 0.02)
        val trueSignal = 0.50

        // Simulate road vibration noise (15 Hz high-frequency oscillation of +/- 0.05 amplitude)
        var maxRawDev = 0.0
        var maxFilteredDev = 0.0

        for (i in 0..100) {
            val tMs = i * 16L // 60 FPS (~16.6ms intervals)
            val noise = 0.05 * sin(2.0 * Math.PI * 15.0 * (tMs / 1000.0))
            val noisyInput = trueSignal + noise

            val filtered = filter.filter(noisyInput, tMs)

            if (i > 20) { // Check after initial convergence
                val rawDev = abs(noisyInput - trueSignal)
                val filteredDev = abs(filtered - trueSignal)
                if (rawDev > maxRawDev) maxRawDev = rawDev
                if (filteredDev > maxFilteredDev) maxFilteredDev = filteredDev
            }
        }

        // Assert that the filter significantly reduces road vibration noise amplitude
        assertTrue("Filter should reduce jitter deviation by at least 60%", maxFilteredDev < maxRawDev * 0.40)
    }

    @Test
    fun testSaccadeQuickTracking() {
        val filter = OneEuroFilter(minCutoff = 1.0, beta = 0.5)

        // Baseline steady at 0.5
        for (i in 0..20) {
            filter.filter(0.5, i * 16L)
        }

        // Rapid saccade jump to 0.85 within 32ms (2 frames)
        var lastFiltered = 0.5
        for (i in 21..45) {
            lastFiltered = filter.filter(0.85, i * 16L)
        }

        // Should rapidly converge to saccade target without heavy sluggish lag
        assertEquals(0.85, lastFiltered, 0.02)
    }

    @Test
    fun testDuplicateAndNonMonotonicTimestamps() {
        val filter = OneEuroFilter()
        val first = filter.filter(0.5, 1000L)
        assertEquals(0.5, first, 1e-6)

        // Duplicate timestamp: should return previous filtered value without blowing up derivative
        val dup = filter.filter(0.8, 1000L)
        assertEquals(0.5, dup, 1e-6)

        // Non-monotonic past timestamp: should return previous value safely
        val past = filter.filter(0.9, 900L)
        assertEquals(0.5, past, 1e-6)

        // Monotonic step: normal filtering resumes
        val next = filter.filter(0.52, 1033L)
        assertTrue("Filter continues smoothly on monotonic time", next in 0.50..0.52)
    }
}
