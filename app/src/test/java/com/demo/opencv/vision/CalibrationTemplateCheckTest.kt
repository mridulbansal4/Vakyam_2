package com.demo.opencv.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationTemplateCheckTest {

    private val w = 44
    private val h = 18

    /** Light eye with a dark iris disc centred at (cx, cy); brightness shifts every pixel. */
    private fun eye(cx: Int, cy: Int, brightness: Float = 0f): FloatArray = FloatArray(w * h) { i ->
        val dx = i % w - cx
        val dy = i / w - cy
        (if (dx * dx + dy * dy <= 36) 0.15f else 0.55f) + brightness
    }

    private val straight = eye(22, 9)
    private val left = eye(10, 9)
    private val up = eye(22, 3)

    private fun steps(vararg images: FloatArray?): Array<FloatArray?> = arrayOf(*images, null, null, null, null).copyOf(6)

    @Test
    fun sameGazeIsRejectedAsACopyOfStraight() {
        // What used to happen: the left-up step captured while the user was still looking straight
        val earlier = steps(straight, left, eye(34, 9), up)
        assertEquals(0, CalibrationTemplateCheck.indistinctFrom(eye(22, 9), eye(22, 9), earlier, earlier, 4))
    }

    @Test
    fun brightnessChangeAloneDoesNotMakeATemplateDistinct() {
        val earlier = steps(straight)
        assertEquals(0, CalibrationTemplateCheck.indistinctFrom(eye(22, 9, 0.2f), eye(22, 9, 0.2f), earlier, earlier, 1))
    }

    @Test
    fun differentGazeIsAccepted() {
        val earlier = steps(straight, left)
        assertEquals(-1, CalibrationTemplateCheck.indistinctFrom(up, up, earlier, earlier, 2))
    }

    @Test
    fun repeatOfAnyEarlierStepIsRejectedNotJustStraight() {
        val earlier = steps(straight, left, eye(34, 9), up)
        assertEquals(3, CalibrationTemplateCheck.indistinctFrom(eye(22, 3), eye(22, 3), earlier, earlier, 4))
    }

    @Test
    fun onlyStepsBeforeTheCurrentOneAreCompared() {
        // Recalibrating: a stale image of this same step must not block it
        val earlier = steps(straight, left)
        assertEquals(-1, CalibrationTemplateCheck.indistinctFrom(left, left, earlier, earlier, 1))
        assertEquals(-1, CalibrationTemplateCheck.indistinctFrom(straight, straight, earlier, earlier, 0))
    }

    @Test
    fun blinkFrameIsNotSettled() {
        val closed = FloatArray(w * h) { i -> if (i / w == 10) 0.2f else 0.45f } // lash line, no iris
        assertFalse(CalibrationTemplateCheck.isSettled(closed, closed, left, left))
        assertTrue(CalibrationTemplateCheck.isSettled(left, left, eye(10, 9, 0.02f), eye(10, 9, 0.02f)))
    }

    @Test
    fun firstFrameWithNothingBeforeItIsNotSettled() {
        assertFalse(CalibrationTemplateCheck.isSettled(left, left, null, null))
    }

    @Test
    fun missingOrMismatchedImagesAreIgnoredNotMatched() {
        assertTrue(CalibrationTemplateCheck.distance(straight, FloatArray(10)).isInfinite())
        assertEquals(-1, CalibrationTemplateCheck.indistinctFrom(null, null, steps(straight), steps(straight), 1))
        // one eye missing: the other still decides
        assertEquals(0, CalibrationTemplateCheck.indistinctFrom(null, eye(22, 9), steps(straight), steps(straight), 1))
    }
}
