package com.demo.opencv.sensors

/** Pure: battery °C + plugged → NORMAL / WARM / HOT with hysteresis. */
class BatteryHeatGuard @JvmOverloads constructor(
    private val warmChargingC: Float = 42f,
    private val hotChargingC: Float = 45f,
    private val hotUnpluggedC: Float = 48f,
    private val hysteresisC: Float = 2f
) {
    var level = ChargingHeat.NORMAL
        private set

    fun update(tempC: Float, plugged: Boolean): ChargingHeat {
        if (tempC.isNaN()) return level
        val enterHot = if (plugged) hotChargingC else hotUnpluggedC
        val enterWarm = if (plugged) warmChargingC else hotUnpluggedC // unplugged has no WARM stage
        level = when {
            tempC >= enterHot -> ChargingHeat.HOT
            level == ChargingHeat.HOT && tempC > enterHot - hysteresisC -> ChargingHeat.HOT
            tempC >= enterWarm -> ChargingHeat.WARM
            level >= ChargingHeat.WARM && tempC > enterWarm - hysteresisC -> ChargingHeat.WARM
            else -> ChargingHeat.NORMAL
        }
        return level
    }
}
