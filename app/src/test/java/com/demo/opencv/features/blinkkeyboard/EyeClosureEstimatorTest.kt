package com.demo.opencv.features.blinkkeyboard

import com.demo.opencv.emergency.HelpSignalDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EyeClosureEstimatorTest {

    private val estimator = EyeClosureEstimator()

    private fun openEyes(frames: Int = 30) = repeat(frames) { estimator.update(0.32f, 0.95f) }

    @Test
    fun openEyesAreOpenAndTheThresholdAdaptsToTheFace() {
        openEyes()
        assertFalse(estimator.closed)
        assertEquals(0.32f * 0.6f, estimator.threshold, 0.01f)
    }

    @Test
    fun aLowAspectRatioClosesEvenWhenTheProbabilityStaysHigh() {
        openEyes()
        // ML Kit often keeps scoring a shut eye around 0.3-0.5; the lids themselves are unambiguous.
        assertTrue(estimator.update(0.08f, 0.45f))
    }

    @Test
    fun aLowProbabilityClosesWithoutContours() {
        assertTrue(estimator.update(Float.NaN, 0.05f))
        assertFalse(estimator.update(Float.NaN, 0.95f))
    }

    @Test
    fun aNoisyFrameDoesNotReopenAClosedEye() {
        openEyes()
        estimator.update(0.08f, 0.10f)
        assertTrue(estimator.update(0.20f, 0.35f)) // just over the threshold, not clearly open
        assertTrue(estimator.update(0.08f, 0.10f))
        assertFalse(estimator.update(0.31f, 0.90f))
    }

    @Test
    fun losingTheFaceStartsOpen() {
        estimator.update(0.05f, 0.05f)
        estimator.onFaceLost()
        assertFalse(estimator.closed)
    }

    @Test
    fun noisyLongBlinksStillRaiseTheAlarm() {
        // What the gaze camera really delivers: ~20 fps, and a shut eye whose probability wobbles
        // above 0.15 every few frames, which the old "both eyes <= 0.15" test split into short blinks.
        val detector = HelpSignalDetector()
        var triggered = false
        var now = 0L
        fun frames(ms: Long, sample: (Int) -> Pair<Float, Float>) {
            val end = now + ms
            var i = 0
            while (now < end) {
                val (ear, probability) = sample(i++)
                val closed = estimator.update(ear, probability)
                if (detector.update(true, closed, now) == HelpSignalDetector.Event.TRIGGERED) triggered = true
                now += 50L
            }
        }
        frames(2_000L) { 0.32f to 0.95f }
        repeat(3) {
            frames(2_000L) { i -> 0.09f to if (i % 4 == 3) 0.30f else 0.08f }
            frames(1_000L) { 0.32f to 0.95f }
        }
        assertTrue(triggered)
    }
}
