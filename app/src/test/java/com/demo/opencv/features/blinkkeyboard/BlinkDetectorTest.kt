package com.demo.opencv.features.blinkkeyboard

import com.demo.opencv.features.blinkkeyboard.BlinkDetector.BlinkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlinkDetectorTest {

    private val config = BlinkConfig()
    private val events = mutableListOf<Pair<BlinkKind, Long>>()
    private var restCount = 0
    private var closureStarts = 0
    private val detector = BlinkDetector(config, object : BlinkDetector.Listener {
        override fun onBlink(kind: BlinkKind, durationMs: Long) {
            events += kind to durationMs
        }

        override fun onClosureStarted() {
            closureStarts++
        }

        override fun onRestStarted() {
            restCount++
        }

        override fun onClosureProgress(durationMs: Long) {}
    })

    private var clock = 1_000L

    /** Feeds 30 fps frames with the given eye state for [ms] milliseconds. */
    private fun frames(closed: Boolean, ms: Long) {
        val end = clock + ms
        while (clock < end) {
            detector.processEAR(if (closed) 0.10f else 0.30f, clock)
            clock += 33
        }
        clock = end
    }

    private fun blink(closedMs: Long) {
        frames(closed = true, ms = closedMs)
        frames(closed = false, ms = 300)
    }

    private fun kinds() = events.map { it.first }

    @Test
    fun naturalBlinkIsIgnored() {
        blink(150)
        assertEquals(listOf(BlinkKind.INVOLUNTARY), kinds())
    }

    @Test
    fun blinkBetweenInvoluntaryAndSelectWindowIsIgnored() {
        blink(300)
        assertEquals(listOf(BlinkKind.AMBIGUOUS), kinds())
    }

    @Test
    fun halfSecondClosureSelects() {
        blink(500)
        assertEquals(listOf(BlinkKind.SELECT), kinds())
    }

    @Test
    fun oneSecondDeliberateClosureStillSelects() {
        blink(1_000)
        assertEquals(listOf(BlinkKind.SELECT), kinds())
    }

    @Test
    fun selectWindowBoundariesAreInclusive() {
        assertEquals(BlinkKind.SELECT, detector.classify(config.minBlinkDurationMs))
        assertEquals(BlinkKind.SELECT, detector.classify(config.maxBlinkDurationMs))
        assertEquals(BlinkKind.TOO_LONG, detector.classify(config.maxBlinkDurationMs + 1))
        assertEquals(BlinkKind.INVOLUNTARY, detector.classify(config.involuntaryBlinkMaxMs - 1))
    }

    @Test
    fun trackingFlickerDuringClosureDoesNotSplitIt() {
        frames(closed = true, ms = 300)
        frames(closed = false, ms = 40)
        frames(closed = true, ms = 300)
        frames(closed = false, ms = 300)
        assertEquals(listOf(BlinkKind.SELECT), kinds())
        assertEquals(1, closureStarts)
    }

    @Test
    fun twoNaturalBlinksWithARealGapStaySeparate() {
        blink(150)
        blink(150)
        assertEquals(listOf(BlinkKind.INVOLUNTARY, BlinkKind.INVOLUNTARY), kinds())
    }

    @Test
    fun durationIsMeasuredToFirstOpenFrameNotAfterGrace() {
        blink(500)
        assertTrue(events.single().second in 495L..530L)
    }

    @Test
    fun longClosureTriggersRestOnceWhileStillClosed() {
        blink(2_500)
        assertEquals(1, restCount)
        assertEquals(listOf(BlinkKind.REST), kinds())
    }

    @Test
    fun resetDropsClosureWithoutClassifying() {
        frames(closed = true, ms = 500)
        detector.reset()
        frames(closed = false, ms = 300)
        assertTrue(events.isEmpty())
    }
}
