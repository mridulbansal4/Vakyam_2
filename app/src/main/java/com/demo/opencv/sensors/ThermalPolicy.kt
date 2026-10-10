package com.demo.opencv.sensors

/** Pure: inputs → tier, with immediate escalation and slow, one-step-at-a-time recovery. */
class ThermalPolicy @JvmOverloads constructor(
    private val recoveryHoldMs: Long = 20_000L,
    private val balancedHeadroom: Float = 0.90f,
    private val powerSaverHeadroom: Float = 1.0f,
    private val warmBatteryC: Float = 41f,
    private val hotBatteryC: Float = 44f,
    private val batteryHysteresisC: Float = 1.5f
) {
    var tier = PerformanceTier.FULL
        private set
    private var batteryTier = PerformanceTier.FULL
    private var recoveryStartMs = -1L

    fun evaluate(thermalStatus: Int, headroom: Float, batteryTempC: Float, nowMs: Long): PerformanceTier {
        batteryTier = batteryTierFor(batteryTempC)
        val target = maxOf(statusTier(thermalStatus), headroomTier(headroom), batteryTier)
        when {
            target > tier -> {
                tier = target
                recoveryStartMs = -1L
            }
            target < tier -> {
                if (recoveryStartMs < 0L) {
                    recoveryStartMs = nowMs
                } else if (nowMs - recoveryStartMs >= recoveryHoldMs) {
                    tier = PerformanceTier.values()[tier.ordinal - 1]
                    recoveryStartMs = if (target < tier) nowMs else -1L
                }
            }
            else -> recoveryStartMs = -1L
        }
        return tier
    }

    private fun statusTier(status: Int) = when {
        status >= THERMAL_STATUS_SEVERE -> PerformanceTier.POWER_SAVER
        status >= THERMAL_STATUS_MODERATE -> PerformanceTier.BALANCED
        else -> PerformanceTier.FULL
    }

    private fun headroomTier(h: Float) = when {
        h.isNaN() -> PerformanceTier.FULL
        h >= powerSaverHeadroom -> PerformanceTier.POWER_SAVER
        h >= balancedHeadroom -> PerformanceTier.BALANCED
        else -> PerformanceTier.FULL
    }

    private fun batteryTierFor(t: Float): PerformanceTier {
        if (t.isNaN()) return PerformanceTier.FULL
        return when {
            t >= hotBatteryC -> PerformanceTier.POWER_SAVER
            batteryTier == PerformanceTier.POWER_SAVER && t > hotBatteryC - batteryHysteresisC -> PerformanceTier.POWER_SAVER
            t >= warmBatteryC -> PerformanceTier.BALANCED
            batteryTier >= PerformanceTier.BALANCED && t > warmBatteryC - batteryHysteresisC -> PerformanceTier.BALANCED
            else -> PerformanceTier.FULL
        }
    }

    companion object {
        // Mirrors android.os.PowerManager.THERMAL_STATUS_* so this class stays JVM-testable.
        const val THERMAL_STATUS_MODERATE = 2
        const val THERMAL_STATUS_SEVERE = 3
    }
}
