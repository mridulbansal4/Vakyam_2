package com.demo.opencv.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GazeDwellSelectorTest {

    private companion object {
        const val X = GazeDwellSelector.UNRELIABLE
        const val STRAIGHT = 0
        const val LEFT = 1
        const val RIGHT = 2
        const val UP = 3
        const val CLOSED = 5
        const val LEFT_UP = 6
    }

    private lateinit var selector: GazeDwellSelector
    private var t = 0L

    @Before
    fun setUp() {
        selector = GazeDwellSelector()
        t = 1_000L
    }

    /** Feeds one frame per entry at the given frame interval and returns every selection made. */
    private fun feed(frames: List<Int>, frameMs: Long = 33L): List<Int> {
        val fired = ArrayList<Int>()
        for (gaze in frames) {
            t += frameMs
            val out = selector.update(gaze, t)
            if (out != 0) fired.add(out)
        }
        return fired
    }

    private fun hold(gaze: Int, ms: Long, frameMs: Long = 33L) = feed(List((ms / frameMs).toInt()) { gaze }, frameMs)

    @Test
    fun heldGazeFiresExactlyOnce() {
        assertEquals(listOf(LEFT), hold(LEFT, 2_000L))
    }

    @Test
    fun shortGlanceDoesNotFire() {
        // Old behaviour: 3 frames (~100 ms at 30 fps) was enough to enter an option
        assertEquals(emptyList<Int>(), hold(LEFT, 200L) + hold(STRAIGHT, 500L))
    }

    @Test
    fun straightNeverFires() {
        assertEquals(emptyList<Int>(), hold(STRAIGHT, 3_000L))
    }

    @Test
    fun holdTimeIsTheSameAtEveryFrameRate() {
        for (frameMs in longArrayOf(33L, 50L, 100L)) {
            setUp()
            assertEquals("no fire before the dwell at $frameMs ms/frame", emptyList<Int>(), hold(RIGHT, 250L, frameMs))
            assertEquals("fires after the dwell at $frameMs ms/frame", listOf(RIGHT), hold(RIGHT, 250L, frameMs))
        }
    }

    @Test
    fun naturalBlinkIsNotASelection() {
        hold(STRAIGHT, 300L)
        assertEquals(emptyList<Int>(), hold(CLOSED, 300L) + hold(STRAIGHT, 500L))
    }

    @Test
    fun deliberateEyeClosureFiresOnceWhileStillClosed() {
        hold(STRAIGHT, 300L)
        assertEquals(listOf(CLOSED), hold(CLOSED, 1_500L))
        assertEquals(emptyList<Int>(), hold(STRAIGHT, 500L))
    }

    @Test
    fun blinkInTheMiddleOfAHoldDoesNotFireTheBlink() {
        val fired = hold(UP, 200L) + hold(CLOSED, 150L) + hold(UP, 600L)
        assertEquals(listOf(UP), fired)
    }

    @Test
    fun singleMisreadFrameDoesNotRestartTheHold() {
        val fired = feed(listOf(LEFT, LEFT, LEFT, LEFT, STRAIGHT, LEFT, LEFT, X, LEFT, LEFT, LEFT, LEFT))
        assertEquals(listOf(LEFT), fired)
    }

    @Test
    fun flickerAfterSelectionDoesNotEnterTheOptionTwice() {
        // Old behaviour: Left x3, one other frame, Left x3 entered Left twice
        val frames = ArrayList<Int>()
        repeat(15) { frames.add(LEFT) }
        repeat(8) { frames.addAll(listOf(STRAIGHT, LEFT, LEFT, LEFT, X, LEFT, LEFT, LEFT, LEFT, LEFT, UP, LEFT, LEFT)) }
        assertEquals(listOf(LEFT), feed(frames))
    }

    @Test
    fun sameOptionCanBeEnteredAgainAfterLookingBackToCentre() {
        val fired = hold(LEFT, 600L) + hold(STRAIGHT, 200L) + hold(LEFT, 600L)
        assertEquals(listOf(LEFT, LEFT), fired)
    }

    @Test
    fun sameOptionRearmsWithoutAStraightReading() {
        // Not calibrated: frames with open eyes are unreadable, so there is never a straight reading
        val fired = hold(CLOSED, 800L) + hold(X, 700L) + hold(CLOSED, 800L)
        assertEquals(listOf(CLOSED, CLOSED), fired)
    }

    @Test
    fun differentOptionCanFollowDirectly() {
        val fired = hold(LEFT, 600L) + hold(RIGHT, 700L)
        assertEquals(listOf(LEFT, RIGHT), fired)
    }

    @Test
    fun passingThroughAnotherDirectionOnTheWayBackDoesNotFire() {
        // Returning from the upper-left corner the eye is read as Up / Left for a few frames
        val fired = hold(LEFT_UP, 600L) + feed(listOf(UP, UP, LEFT, LEFT, UP)) + hold(STRAIGHT, 500L)
        assertEquals(listOf(LEFT_UP), fired)
    }

    @Test
    fun nothingFiresDuringTheLockoutAfterASelection() {
        assertEquals(listOf(LEFT), hold(LEFT, 400L))
        val lockoutStart = t
        var firedAt = -1L
        while (firedAt < 0L && t < lockoutStart + 2_000L) {
            t += 33L
            if (selector.update(RIGHT, t) != 0) firedAt = t
        }
        // lockout (300 ms) then a full dwell (300 ms)
        assertTrue("fired ${firedAt - lockoutStart} ms after the previous selection", firedAt - lockoutStart >= 550L)
    }

    @Test
    fun gazeSeenAgainAfterALongGapStartsOver() {
        // Face lost for two seconds in the middle of a hold: the frames before the gap must not count
        hold(LEFT, 250L)
        t += 2_000L
        assertEquals(emptyList<Int>(), hold(LEFT, 250L))
        assertEquals(listOf(LEFT), hold(LEFT, 200L))
    }

    @Test
    fun unreadableFramesDropTheHold() {
        assertEquals(emptyList<Int>(), hold(LEFT, 250L) + hold(X, 400L) + hold(LEFT, 250L))
    }

    @Test
    fun cancelDropsTheHold() {
        hold(LEFT, 250L)
        selector.cancel()
        assertEquals(emptyList<Int>(), hold(LEFT, 250L))
        assertEquals(listOf(LEFT), hold(LEFT, 200L))
    }

    @Test
    fun alternatingClassificationNeverFires() {
        val frames = ArrayList<Int>()
        repeat(60) { frames.addAll(listOf(LEFT, UP)) }
        assertEquals(emptyList<Int>(), feed(frames))
    }

    @Test
    fun clockGoingBackwardsDoesNotFire() {
        hold(LEFT, 250L)
        t -= 5_000L
        assertEquals(emptyList<Int>(), hold(LEFT, 250L))
    }
}
