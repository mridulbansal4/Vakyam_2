package com.demo.opencv.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionSensorStabilizerTest {

    @Test
    fun testPotholeShockTriggersCircuitBreaker() {
        val processor = MotionSensorStabilizer.JerkProcessor(threshold = 4.5f, lockDurationMs = 300L)

        var tNs = 1_000_000_000L
        var tMs = 1000L

        // Smooth initial motion (small constant vehicle acceleration)
        assertTrue(processor.update(0.1f, 0.0f, 9.8f, tNs, tMs))

        // Sudden vertical pothole impact: ay jumps by 8 m/s^2 in 20ms -> Jerk = 8 / 0.02 = 400 m/s^3 >> 4.5
        tNs += 20_000_000L // 20ms later
        tMs += 20L
        val isStableDuringShock = processor.update(0.1f, 8.0f, 9.8f, tNs, tMs)

        // Circuit breaker should engage immediately and lock input
        assertFalse("Shock should engage circuit breaker", isStableDuringShock)

        // Check 150ms later (still within 300ms window)
        assertFalse("System should remain locked during suppression window", processor.isStable(tMs + 150L))

        // Check 350ms later (after suppression window expires)
        assertTrue("System should automatically unlock after suppression window", processor.isStable(tMs + 350L))
    }
}
