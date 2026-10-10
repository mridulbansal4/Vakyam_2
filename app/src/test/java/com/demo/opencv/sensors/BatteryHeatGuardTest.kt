package com.demo.opencv.sensors

import com.demo.opencv.sensors.ChargingHeat.HOT
import com.demo.opencv.sensors.ChargingHeat.NORMAL
import com.demo.opencv.sensors.ChargingHeat.WARM
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryHeatGuardTest {

    @Test
    fun normalChargingTemperaturesAreSilent() {
        val g = BatteryHeatGuard()
        assertEquals(NORMAL, g.update(37.0f, true)) // measured on the iQOO 13 while charging
        assertEquals(NORMAL, g.update(41.9f, true))
    }

    @Test
    fun chargingEscalatesWarmThenHot() {
        val g = BatteryHeatGuard()
        assertEquals(WARM, g.update(42.0f, true))
        assertEquals(HOT, g.update(45.0f, true))
    }

    @Test
    fun hysteresisPreventsFlapping() {
        val g = BatteryHeatGuard()
        g.update(45.0f, true)
        assertEquals(HOT, g.update(44.0f, true))   // > 45 - 2
        assertEquals(WARM, g.update(43.0f, true))  // ≤ 43 leaves HOT, still ≥ 42
        assertEquals(WARM, g.update(40.5f, true))  // > 42 - 2
        assertEquals(NORMAL, g.update(40.0f, true))
    }

    @Test
    fun unpluggingClearsTheChargingAlertImmediately() {
        val g = BatteryHeatGuard()
        g.update(45.5f, true)
        assertEquals(NORMAL, g.update(45.5f, false))
    }

    @Test
    fun unpluggedOnlyAlertsWhenGenuinelyHot() {
        val g = BatteryHeatGuard()
        assertEquals(NORMAL, g.update(44.0f, false))
        assertEquals(HOT, g.update(48.0f, false))
        assertEquals(HOT, g.update(46.5f, false))
        assertEquals(NORMAL, g.update(46.0f, false))
    }

    @Test
    fun nanKeepsPreviousLevel() {
        val g = BatteryHeatGuard()
        g.update(42.5f, true)
        assertEquals(WARM, g.update(Float.NaN, true))
    }
}
