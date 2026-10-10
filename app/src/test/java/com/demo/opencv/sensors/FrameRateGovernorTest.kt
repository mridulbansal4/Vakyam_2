package com.demo.opencv.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRateGovernorTest {

    private fun run(targetFps: Int, frames: Int = 300, jitterMs: Long = 0L): Pair<Int, Long> {
        val g = FrameRateGovernor(targetFps)
        var accepted = 0
        var lastAccepted = -1L
        var maxGap = 0L
        for (i in 0 until frames) {
            val t = (i * 1000L / 30L) + if (i % 2 == 0) jitterMs else -jitterMs
            if (g.shouldProcess(t)) {
                if (lastAccepted >= 0) maxGap = maxOf(maxGap, t - lastAccepted)
                lastAccepted = t
                accepted++
            }
        }
        return accepted to maxGap
    }

    @Test fun fullPassesEverything() = assertEquals(300, run(30).first)

    @Test
    fun balancedIs24Fps() {
        val (n, gap) = run(24)
        assertTrue("got $n", n in 237..243)
        assertTrue("gap $gap", gap <= 70L)
    }

    @Test
    fun powerSaverIs20FpsAndKeepsGesturesAlive() {
        val (n, gap) = run(20, jitterMs = 3L)
        assertTrue("got $n", n in 197..203)
        assertTrue("gap $gap must stay < 200 ms for BallisticGestureDetector", gap < 200L)
    }

    @Test
    fun neverBelowTenFps() {
        val g = FrameRateGovernor()
        g.targetFps = 2
        assertEquals(FrameRateGovernor.MIN_FPS, g.targetFps)
    }
}
