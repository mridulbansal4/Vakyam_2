package com.demo.opencv.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMotionTrackerTest {

    private fun spin(integrator: PhoneMotionTracker.Integrator, wy: Float, seconds: Float) {
        val stepNs = 5_000_000L
        val steps = (seconds * 1e9f / stepNs).toInt()
        for (i in 1..steps) integrator.onGyro(0f, wy, 0f, i * stepNs)
    }

    @Test
    fun integratesGyroIntoAngles() {
        val integrator = PhoneMotionTracker.Integrator()
        spin(integrator, 1.0f, 0.5f)
        assertEquals(0.5f, integrator.yaw, 0.02f)
        assertEquals(0f, integrator.pitch, 1e-6f)
    }

    @Test
    fun handTremorIsNotAShake() {
        val integrator = PhoneMotionTracker.Integrator()
        spin(integrator, 0.2f, 0.5f)
        integrator.onLinearAccel(0.3f, 0.2f, 0.1f)
        assertFalse(integrator.isShaking)
    }

    @Test
    fun fastRotationOrBumpIsAShake() {
        val rotation = PhoneMotionTracker.Integrator()
        spin(rotation, 2.5f, 0.2f)
        assertTrue(rotation.isShaking)

        val bump = PhoneMotionTracker.Integrator()
        repeat(20) { bump.onLinearAccel(6f, 0f, 0f) }
        assertTrue(bump.isShaking)
    }
}
