package com.demo.opencv.features.blinkkeyboard

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
    fun aShutEyeWithAWobblingProbabilityStaysOneClosure() {
        // What the gaze camera really delivers: a shut eye whose probability wobbles above the
        // cut-off every few frames. That must not split one closure into several blinks.
        openEyes()
        var reopened = false
        repeat(40) { i -> if (!estimator.update(0.09f, if (i % 4 == 3) 0.30f else 0.08f)) reopened = true }
        assertFalse(reopened)
        assertFalse(estimator.update(0.32f, 0.95f))
    }
}
