package com.demo.opencv.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class BallisticGestureDetectorTest {

    private val detector = BallisticGestureDetector(
        velocityThreshold = 1.8f,
        displacementThreshold = 0.18f,
        lockoutDurationMs = 300L
    )

    @Test
    fun testIntentionalFlickRightDetected() {
        var t = 0L
        // Rest at center
        detector.process(0.50f, 0.50f, 0.30f, t)

        t += 30L
        detector.process(0.52f, 0.50f, 0.30f, t)

        // Rapid saccade flick right (dx = 0.28 in 30ms -> velocity = 9.3 units/sec)
        t += 30L
        val gesture = detector.process(0.80f, 0.50f, 0.30f, t)

        assertEquals(BallisticGestureDetector.Gesture.FLICK_RIGHT, gesture)
    }

    @Test
    fun testRoadVibrationOscillationRejected() {
        val detector = BallisticGestureDetector(
            velocityThreshold = 2.0f,
            displacementThreshold = 0.18f
        )

        var t = 0L
        detector.process(0.50f, 0.50f, 0.30f, t)

        // Road bumps cause small fast jitter (+/- 0.04), well below displacement threshold (0.18)
        for (i in 1..20) {
            t += 16L
            val jitter = if (i % 2 == 0) 0.04f else -0.04f
            val gesture = detector.process(0.50f + jitter, 0.50f, 0.30f, t)
            assertEquals("Road vibration should never trigger false gesture", BallisticGestureDetector.Gesture.NONE, gesture)
        }
    }

    @Test
    fun testDeliberateBlinkDetected() {
        var t = 1000L
        detector.process(0.50f, 0.50f, 0.30f, t)

        // Eyes close (openRatio < 0.12)
        t += 50L
        detector.process(0.50f, 0.50f, 0.05f, t)

        // Eyes remain closed for 350ms (intentional blink selection)
        t += 350L
        detector.process(0.50f, 0.50f, 0.05f, t)

        // Eyes open
        t += 50L
        val gesture = detector.process(0.50f, 0.50f, 0.30f, t)

        assertEquals(BallisticGestureDetector.Gesture.BLINK_CONFIRM, gesture)
    }

    @Test
    fun testIntentionalFlickLeftDetected() {
        var t = 0L
        detector.process(0.50f, 0.50f, 0.30f, t)

        t += 30L
        detector.process(0.48f, 0.50f, 0.30f, t)

        // Rapid saccade flick left (dx = -0.28 in 30ms -> velocity = -9.3 units/sec)
        t += 30L
        val gesture = detector.process(0.20f, 0.50f, 0.30f, t)

        assertEquals(BallisticGestureDetector.Gesture.FLICK_LEFT, gesture)
    }

    @Test
    fun testIntentionalFlickUpDetected() {
        var t = 0L
        detector.process(0.50f, 0.50f, 0.30f, t)

        t += 30L
        detector.process(0.50f, 0.45f, 0.30f, t)

        // Rapid upward saccade (dy = -0.28 in 30ms -> verticalRatio < 0.28)
        t += 30L
        val gesture = detector.process(0.50f, 0.20f, 0.30f, t)

        assertEquals(BallisticGestureDetector.Gesture.FLICK_UP, gesture)
    }

    @Test
    fun testInvoluntaryMicroBlinkRejected() {
        var t = 1000L
        detector.process(0.50f, 0.50f, 0.30f, t)

        // Rapid physiological blink (< 150ms)
        t += 50L
        detector.process(0.50f, 0.50f, 0.05f, t)

        t += 70L // Total blink duration ~120ms
        val gesture = detector.process(0.50f, 0.50f, 0.30f, t)

        // Must NOT trigger confirmation (only deliberate blinks 250-600ms count)
        assertEquals(BallisticGestureDetector.Gesture.NONE, gesture)
    }

    @Test
    fun testGestureLockoutCooldown() {
        var t = 0L
        detector.process(0.50f, 0.50f, 0.30f, t)

        t += 30L
        val firstGesture = detector.process(0.80f, 0.50f, 0.30f, t)
        assertEquals(BallisticGestureDetector.Gesture.FLICK_RIGHT, firstGesture)

        // Immediately try another gesture at t + 50ms (within 300ms lockout)
        t += 50L
        val lockedGesture = detector.process(0.20f, 0.50f, 0.30f, t)
        assertEquals(BallisticGestureDetector.Gesture.NONE, lockedGesture)
    }

    @Test
    fun testProlongedClosureDoesNotTriggerFalseSaccadeOnReopen() {
        var t = 1000L
        detector.process(0.50f, 0.50f, 0.30f, t)

        // Eyes closed for 800ms (resting eyes, not a short confirm blink)
        t += 50L
        detector.process(0.50f, 0.50f, 0.05f, t)
        t += 800L
        detector.process(0.50f, 0.50f, 0.05f, t)

        // Eyes reopen at t + 50ms, with gaze offset at 0.82f (e.g. user looked right while closed)
        t += 50L
        val reopenGesture = detector.process(0.82f, 0.50f, 0.30f, t)

        // Eye reopening must NOT be misclassified as a high-velocity flick
        assertEquals(BallisticGestureDetector.Gesture.NONE, reopenGesture)
    }
}
