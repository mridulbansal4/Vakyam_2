package com.demo.opencv.sensors

import androidx.annotation.StringRes
import com.demo.opencv.R
import java.util.Locale

enum class PerformanceTier(val targetFps: Int) {
    FULL(30),
    BALANCED(24),
    POWER_SAVER(20) // never below 10: BallisticGestureDetector resets its baseline on gaps > 200 ms
}

enum class ChargingHeat { NORMAL, WARM, HOT }

/** Declaration order is display priority: the first active banner wins. */
enum class SensorBanner(@StringRes val messageRes: Int, val colorArgb: Int) {
    CHARGING_HOT(R.string.Sensor_ChargingHot, 0xFFC62828.toInt()),
    THERMAL_CRITICAL(R.string.Sensor_ThermalCritical, 0xFFEF6C00.toInt()),
    CHARGING_WARM(R.string.Sensor_ChargingWarm, 0xFFF9A825.toInt())
}

data class SensorHealthSnapshot(
    val thermalStatus: Int = -1,
    val thermalHeadroom: Float = Float.NaN,
    val batteryTempC: Float = Float.NaN,
    val plugged: Boolean = false,
    val chargingHeat: ChargingHeat = ChargingHeat.NORMAL,
    val tier: PerformanceTier = PerformanceTier.FULL,
    val measuredFps: Float = 0f,
    val frameMs: Float = Float.NaN,
    val cctKelvin: Float = Float.NaN,
    val ambientLux: Float = Float.NaN,
    val warmTintAlpha: Float = 0f
) {
    fun toDebugText(): String = buildString {
        appendLine("Thermal   : ${thermalStatusName(thermalStatus)}  headroom=${fmt(thermalHeadroom, 2)}")
        appendLine("Battery   : ${fmt(batteryTempC)} °C  plugged=$plugged  heat=$chargingHeat")
        appendLine("Tier      : $tier  target=${tier.targetFps} fps  measured=${fmt(measuredFps)} fps  frame=${fmt(frameMs)} ms")
        append("Light     : CCT=${fmt(cctKelvin, 0)} K  lux=${fmt(ambientLux, 0)}  tint=${fmt(warmTintAlpha, 2)}")
    }

    private fun fmt(v: Float, decimals: Int = 1): String =
        if (v.isNaN()) "--" else String.format(Locale.US, "%.${decimals}f", v)

    private fun thermalStatusName(s: Int): String = when (s) {
        0 -> "NONE"; 1 -> "LIGHT"; 2 -> "MODERATE"; 3 -> "SEVERE"
        4 -> "CRITICAL"; 5 -> "EMERGENCY"; 6 -> "SHUTDOWN"; else -> "UNKNOWN"
    }
}
