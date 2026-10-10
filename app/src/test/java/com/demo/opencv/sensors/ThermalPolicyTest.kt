package com.demo.opencv.sensors

import com.demo.opencv.sensors.PerformanceTier.BALANCED
import com.demo.opencv.sensors.PerformanceTier.FULL
import com.demo.opencv.sensors.PerformanceTier.POWER_SAVER
import org.junit.Assert.assertEquals
import org.junit.Test

class ThermalPolicyTest {
    private val NONE = 0; private val LIGHT = 1; private val MODERATE = 2; private val SEVERE = 3
    private val nan = Float.NaN

    @Test
    fun coolDeviceStaysFull() {
        val p = ThermalPolicy()
        assertEquals(FULL, p.evaluate(LIGHT, 0.83f, 37f, 0L)) // values measured on the iQOO 13
    }

    @Test
    fun escalationIsImmediate() {
        val p = ThermalPolicy()
        assertEquals(BALANCED, p.evaluate(MODERATE, nan, nan, 0L))
        assertEquals(POWER_SAVER, p.evaluate(SEVERE, nan, nan, 10L))
    }

    @Test
    fun recoveryStepsDownOneTierPerHold() {
        val p = ThermalPolicy(recoveryHoldMs = 20_000L)
        p.evaluate(SEVERE, nan, nan, 0L)
        val t1 = 1_000L
        assertEquals(POWER_SAVER, p.evaluate(NONE, nan, nan, t1))
        assertEquals(POWER_SAVER, p.evaluate(NONE, nan, nan, t1 + 19_999L))
        assertEquals(BALANCED, p.evaluate(NONE, nan, nan, t1 + 20_000L))
        assertEquals(BALANCED, p.evaluate(NONE, nan, nan, t1 + 39_999L))
        assertEquals(FULL, p.evaluate(NONE, nan, nan, t1 + 40_000L))
    }

    @Test
    fun reheatingDuringRecoveryCancelsIt() {
        val p = ThermalPolicy(recoveryHoldMs = 20_000L)
        p.evaluate(SEVERE, nan, nan, 0L)
        p.evaluate(NONE, nan, nan, 1_000L)
        p.evaluate(SEVERE, nan, nan, 15_000L)
        assertEquals(POWER_SAVER, p.evaluate(NONE, nan, nan, 30_000L)) // timer restarted at 30 s
        assertEquals(BALANCED, p.evaluate(NONE, nan, nan, 50_000L))
    }

    @Test
    fun resetSkipsTheRecoveryHold() {
        val p = ThermalPolicy(recoveryHoldMs = 20_000L)
        p.evaluate(SEVERE, nan, nan, 0L)
        p.reset()
        assertEquals(FULL, p.evaluate(NONE, nan, nan, 1L))
        assertEquals(POWER_SAVER, p.evaluate(SEVERE, nan, nan, 2L)) // still escalates immediately
    }

    @Test
    fun headroomGivesEarlyWarning() {
        assertEquals(BALANCED, ThermalPolicy().evaluate(LIGHT, 0.92f, nan, 0L))
        assertEquals(POWER_SAVER, ThermalPolicy().evaluate(LIGHT, 1.02f, nan, 0L))
        assertEquals(FULL, ThermalPolicy().evaluate(LIGHT, nan, nan, 0L))
    }

    @Test
    fun batteryTemperatureTiersWithHysteresis() {
        val p = ThermalPolicy(recoveryHoldMs = 0L)
        assertEquals(BALANCED, p.evaluate(NONE, nan, 41f, 0L))
        assertEquals(POWER_SAVER, p.evaluate(NONE, nan, 44f, 1L))
        assertEquals(POWER_SAVER, p.evaluate(NONE, nan, 42.6f, 2L)) // above 44 - 1.5
        p.evaluate(NONE, nan, 42.4f, 3L)
        assertEquals(BALANCED, p.evaluate(NONE, nan, 42.4f, 4L))
        p.evaluate(NONE, nan, 39.4f, 5L)
        assertEquals(FULL, p.evaluate(NONE, nan, 39.4f, 6L))
    }
}
