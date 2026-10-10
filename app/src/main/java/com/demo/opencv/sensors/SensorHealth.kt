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

/** Conditions the Settings-screen test panel can fake without the real environment. */
enum class SensorSimulation { DARK_ROOM, HOT_PHONE, CHARGING_HOT, WARM_LIGHT }

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
    val warmTintAlpha: Float = 0f,
    val frontLux: Float = Float.NaN,
    val roomLux: Float = Float.NaN,
    val ringContributionLux: Float = 0f,
    val ringLight: Boolean = false,
    val simulations: Set<SensorSimulation> = emptySet(),
    val uptimeMin: Float = 0f,
    /** Minutes of use before real heat first slowed the camera down; NaN while that has not happened. */
    val firstSlowdownMin: Float = Float.NaN
) {
    fun toDebugText(): String = buildString {
        appendLine("Thermal   : ${thermalStatusName(thermalStatus)}  headroom=${fmt(thermalHeadroom, 2)}")
        appendLine("Battery   : ${fmt(batteryTempC)} °C  plugged=$plugged  heat=$chargingHeat")
        appendLine("Tier      : $tier  target=${tier.targetFps} fps  measured=${fmt(measuredFps)} fps  frame=${fmt(frameMs)} ms")
        appendLine("Light     : CCT=${fmt(cctKelvin, 0)} K  lux=${fmt(ambientLux, 0)}  tint=${fmt(warmTintAlpha, 2)}")
        appendLine("Low light : front=${fmt(frontLux, 0)} lux  room≈${fmt(roomLux, 0)} lux  ring=${if (ringLight) "ON" else "off"}  self=${fmt(ringContributionLux, 0)} lux")
        append(
            if (firstSlowdownMin.isNaN()) "Endurance : no heat slow-down in ${fmt(uptimeMin)} min of use"
            else "Endurance : first heat slow-down after ${fmt(firstSlowdownMin)} min of use"
        )
        if (simulations.isNotEmpty()) append("\nSIMULATED : ${simulations.joinToString()}")
    }

    /** One line for a typing-test result, so runs under different conditions can be told apart. */
    fun toConditionText(): String =
        "tier=$tier fps=${fmt(measuredFps, 0)} lux=${fmt(roomLux, 0)} ring=${if (ringLight) "on" else "off"}" +
            " battery=${fmt(batteryTempC)}C" + if (simulations.isEmpty()) "" else " simulated=${simulations.joinToString("+")}"

    private fun fmt(v: Float, decimals: Int = 1): String =
        if (v.isNaN()) "--" else String.format(Locale.US, "%.${decimals}f", v)

    private fun thermalStatusName(s: Int): String = when (s) {
        0 -> "NONE"; 1 -> "LIGHT"; 2 -> "MODERATE"; 3 -> "SEVERE"
        4 -> "CRITICAL"; 5 -> "EMERGENCY"; 6 -> "SHUTDOWN"; else -> "UNKNOWN"
    }
}
