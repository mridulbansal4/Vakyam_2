package com.demo.opencv.emergency

import com.demo.opencv.emergency.HelpSignalDetector.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelpSignalDetectorTest {

    private val detector = HelpSignalDetector()
    private val events = mutableListOf<Event>()
    private var clock = 1_000L

    /** Feeds 30 fps frames for [ms] milliseconds. */
    private fun frames(ms: Long, closed: Boolean, face: Boolean = true, fps: Int = 30) {
        val end = clock + ms
        while (clock < end) {
            detector.update(face, closed, clock).takeIf { it != Event.NONE }?.let(events::add)
            clock += 1000 / fps
        }
        clock = end
    }

    private fun blink(closedMs: Long, openMs: Long = 800L) {
        frames(closedMs, closed = true)
        frames(openMs, closed = false)
    }

    @Test
    fun threeLongBlinksRaiseTheAlarm() {
        blink(2_000L)
        blink(2_000L)
        assertEquals(listOf(Event.COUNTED, Event.COUNTED), events)
        assertEquals(2, detector.count)
        blink(2_000L)
        assertEquals(Event.TRIGGERED, events.last())
        assertEquals(0, detector.count)
    }

    @Test
    fun typingBlinksNeverCount() {
        // Natural blinks, gaze confirm blinks (250-600 ms) and blink keyboard selections (350-1200 ms).
        repeat(20) {
            blink(120L)
            blink(450L)
            blink(1_000L)
        }
        assertTrue(events.isEmpty())
    }

    @Test
    fun sleepingOrRestingIsNotACallForHelp() {
        blink(30_000L)
        blink(6_000L)
        blink(4_500L)
        assertTrue(events.isEmpty())
    }

    @Test
    fun aLongRestBetweenTwoLongBlinksDoesNotCountAsTheThird() {
        blink(2_000L)
        blink(8_000L)
        blink(2_000L)
        assertFalse(Event.TRIGGERED in events)
        assertEquals(2, detector.count)
    }

    @Test
    fun blinksSpreadOverALongTimeExpire() {
        blink(2_000L)
        frames(20_000L, closed = false)
        assertEquals(listOf(Event.COUNTED, Event.EXPIRED), events)
        blink(2_000L)
        blink(2_000L)
        assertFalse(Event.TRIGGERED in events)
    }

    @Test
    fun trackingFlickerInsideAClosureDoesNotSplitIt() {
        frames(900L, closed = true)
        frames(66L, closed = false) // two frames of the detector losing the closed eye
        frames(900L, closed = true)
        frames(800L, closed = false)
        assertEquals(listOf(Event.COUNTED), events)
    }

    @Test
    fun aBriefFaceDropoutInsideAClosureIsTolerated() {
        frames(900L, closed = true)
        frames(300L, closed = false, face = false)
        frames(900L, closed = true)
        frames(800L, closed = false)
        assertEquals(listOf(Event.COUNTED), events)
    }

    @Test
    fun losingTheFaceForLongDropsTheClosureButKeepsEarlierBlinks() {
        blink(2_000L)
        frames(1_000L, closed = true)
        frames(3_000L, closed = false, face = false) // patient turned away: the closure cannot be measured
        frames(800L, closed = false)
        assertEquals(listOf(Event.COUNTED), events)
        assertEquals(1, detector.count)
    }

    @Test
    fun worksAtTheLowFrameRateOfAHotPhone() {
        repeat(3) {
            frames(1_800L, closed = true, fps = 10)
            frames(900L, closed = false, fps = 10)
        }
        assertEquals(Event.TRIGGERED, events.last())
    }

    @Test
    fun resetForgetsCountedBlinks() {
        blink(2_000L)
        blink(2_000L)
        detector.reset()
        blink(2_000L)
        assertFalse(Event.TRIGGERED in events)
        assertEquals(1, detector.count)
    }
}

class PresenceWatchdogTest {

    private val watchdog = PresenceWatchdog(absentAfterMs = 20_000L)

    @Test
    fun nothingToLoseBeforeThePatientHasBeenSeen() {
        for (t in 0L..120_000L step 1_000L) assertFalse(watchdog.onSample(false, t))
        assertFalse(watchdog.absent)
    }

    @Test
    fun aLostFaceIsReportedAfterTheHoldAndClearedOnReturn() {
        watchdog.onSample(true, 0L)
        assertFalse(watchdog.onSample(false, 19_999L))
        assertTrue(watchdog.onSample(false, 20_000L))
        assertTrue(watchdog.absent)
        assertFalse(watchdog.onSample(false, 30_000L)) // reported once
        assertTrue(watchdog.onSample(true, 31_000L))
        assertFalse(watchdog.absent)
    }

    @Test
    fun shortDropoutsAreIgnored() {
        for (t in 0L..60_000L step 100L) assertFalse(watchdog.onSample(t % 10_000L < 5_000L, t))
    }

    @Test
    fun aStalledCameraIsNoticedByTheTick() {
        watchdog.onSample(true, 0L)
        assertFalse(watchdog.tick(10_000L))
        assertTrue(watchdog.tick(21_000L))
    }

    @Test
    fun resetStartsOver() {
        watchdog.onSample(true, 0L)
        watchdog.tick(25_000L)
        watchdog.reset()
        assertFalse(watchdog.absent)
        assertFalse(watchdog.tick(100_000L))
    }
}
