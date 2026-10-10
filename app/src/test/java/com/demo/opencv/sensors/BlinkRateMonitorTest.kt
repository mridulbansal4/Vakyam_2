package com.demo.opencv.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlinkRateMonitorTest {

    private val monitor = BlinkRateMonitor()
    private var now = 0L
    private var changes = 0

    private fun frames(ms: Long, closed: Boolean = false, face: Boolean = true) {
        val end = now + ms
        while (now < end) {
            if (monitor.update(face, closed, now)) changes++
            now += 50L
        }
    }

    /** [ms] of watching with a 150 ms blink every [everyMs]. */
    private fun watch(ms: Long, everyMs: Long) {
        val end = now + ms
        while (now < end) {
            frames(everyMs - 150L)
            frames(150L, closed = true)
        }
    }

    @Test
    fun thereIsNoRateUntilAFullMinuteHasBeenWatched() {
        watch(30_000L, everyMs = 4_000L)
        assertTrue(monitor.blinksPerMin(now).isNaN())
        assertFalse(monitor.restDue)
    }

    @Test
    fun aNormalBlinkRateNeverAsksForARest() {
        watch(5 * 60_000L, everyMs = 4_000L) // 15 a minute
        assertEquals(15f, monitor.blinksPerMin(now), 1f)
        assertFalse(monitor.restDue)
        assertEquals(0, changes)
    }

    @Test
    fun staringForAMinuteAsksForARest() {
        watch(59_000L, everyMs = 20_000L) // 3 a minute
        assertFalse(monitor.restDue)
        frames(2_000L)
        assertTrue(monitor.restDue)
        assertEquals(1, changes)
    }

    @Test
    fun closingTheEyesIsTheRestAndClearsTheReminder() {
        watch(62_000L, everyMs = 20_000L)
        assertTrue(monitor.restDue)
        frames(2_500L, closed = true)
        assertFalse(monitor.restDue)
        frames(1_000L)
        assertTrue(monitor.blinksPerMin(now).isNaN()) // a fresh minute starts after the rest
    }

    @Test
    fun theReminderGoesAwayByItselfAndStaysQuietForTenMinutes() {
        watch(59_000L, everyMs = 20_000L)
        frames(2_000L)
        assertTrue(monitor.restDue)
        frames(31_000L)
        assertFalse(monitor.restDue)
        frames(9 * 60_000L) // still staring
        assertFalse(monitor.restDue)
        frames(60_000L)
        assertTrue(monitor.restDue)
    }

    @Test
    fun theReminderTimesOutWithoutCameraFrames() {
        watch(59_000L, everyMs = 20_000L)
        frames(2_000L)
        assertTrue(monitor.restDue)
        assertFalse(monitor.tick(now + 10_000L))
        assertTrue(monitor.tick(now + 31_000L))
        assertFalse(monitor.restDue)
    }

    @Test
    fun aLongRestIsNotCountedAsABlink() {
        watch(30_000L, everyMs = 4_000L)
        frames(5_000L, closed = true)
        watch(61_000L, everyMs = 4_000L)
        assertEquals(15f, monitor.blinksPerMin(now), 1f)
    }

    @Test
    fun watchingStartsOverWhenThePatientLeavesTheCamera() {
        watch(50_000L, everyMs = 20_000L)
        frames(5_000L, face = false)
        frames(20_000L)
        assertTrue(monitor.blinksPerMin(now).isNaN())
        assertFalse(monitor.restDue)
    }

    @Test
    fun aBriefLossOfTheFaceDoesNotStartOver() {
        watch(40_000L, everyMs = 20_000L)
        frames(1_000L, face = false)
        frames(25_000L)
        assertTrue(monitor.restDue)
    }
}
