package com.demo.opencv.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowLightPolicyTest {

    @Test
    fun litRoomNeverTurnsTheRingOn() {
        val p = LowLightPolicy()
        for (t in 0L..20_000L step 500L) assertFalse(p.update(120f, t))
        assertFalse(p.ringOn)
    }

    @Test
    fun darkRoomTurnsTheRingOnAfterTheHold() {
        val p = LowLightPolicy(onHoldMs = 3_000L)
        assertFalse(p.update(2f, 0L))
        assertFalse(p.update(2f, 2_999L))
        assertTrue(p.update(2f, 3_000L))
        assertTrue(p.ringOn)
    }

    @Test
    fun briefShadowDoesNotTrigger() {
        val p = LowLightPolicy(onHoldMs = 3_000L)
        p.update(1f, 0L)          // hand over the sensor
        p.update(80f, 1_500L)     // hand gone
        assertFalse(p.update(1f, 3_500L))
        assertFalse(p.ringOn)
    }

    @Test
    fun ringsOwnLightDoesNotSwitchItOff() {
        val p = turnedOn(luxBefore = 2f)
        // The ring adds ~45 lux to the front sensor; that is measured, then subtracted.
        assertFalse(p.update(47f, 5_000L))
        assertEquals(45f, p.ringContributionLux, 0.01f)
        for (t in 5_000L..60_000L step 1_000L) assertFalse(p.update(47f, t))
        assertTrue(p.ringOn)
        assertEquals(2f, p.roomLux(47f), 0.01f)
    }

    @Test
    fun roomLightsComingOnSwitchItOffAfterTheHold() {
        val p = turnedOn(luxBefore = 2f, offHoldMs = 5_000L)
        p.update(47f, 5_000L)                 // calibrates 45 lux of self-light
        assertFalse(p.update(347f, 10_000L))  // room lamp on: estimate 302 lux
        assertFalse(p.update(347f, 14_999L))
        assertTrue(p.update(347f, 15_000L))
        assertFalse(p.ringOn)
    }

    @Test
    fun noDecisionBeforeCalibration() {
        val p = turnedOn(luxBefore = 2f)
        // Brightness is still ramping; a high reading here must not count as "room is bright".
        assertFalse(p.update(140f, 3_500L))
        assertTrue(p.ringOn)
    }

    @Test
    fun veryBrightRoomAlwaysWinsEvenIfCalibrationWasWrong() {
        val p = turnedOn(luxBefore = 2f, offHoldMs = 1_000L)
        assertFalse(p.update(140f, 5_000L)) // a lamp went on during calibration → contribution capped at 100
        assertEquals(100f, p.ringContributionLux, 0.01f)
        assertTrue(p.update(400f, 6_000L))
    }

    @Test
    fun invalidReadingsAreIgnored() {
        val p = LowLightPolicy(onHoldMs = 0L)
        assertFalse(p.update(Float.NaN, 0L))
        assertFalse(p.update(-1f, 0L))
        assertFalse(p.ringOn)
    }

    private fun turnedOn(luxBefore: Float, offHoldMs: Long = 5_000L): LowLightPolicy {
        val p = LowLightPolicy(onHoldMs = 3_000L, offHoldMs = offHoldMs, calibrateAfterMs = 2_000L)
        p.update(luxBefore, 0L)
        assertTrue(p.update(luxBefore, 3_000L))
        return p
    }
}
