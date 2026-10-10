package com.demo.opencv.sensors

import android.hardware.Sensor
import android.hardware.SensorManager

object CctReading {
    const val STRING_TYPE = "android.sensor.light_cct"
    const val VIVO_LIGHT_CCT_TYPE = 66557
    const val KELVIN_INDEX = 6 // stk_stk6b9x on iQOO 13
    const val MIN_K = 1000f
    const val MAX_K = 15000f

    @JvmStatic
    fun kelvinFrom(values: FloatArray): Float? {
        if (values.size <= KELVIN_INDEX) return null
        val k = values[KELVIN_INDEX]
        return if (k in MIN_K..MAX_K) k else null
    }

    @JvmStatic
    fun findSensor(sensorManager: SensorManager): Sensor? {
        val all = sensorManager.getSensorList(Sensor.TYPE_ALL)
        return all.firstOrNull { it.stringType == STRING_TYPE }
            ?: all.firstOrNull { it.type == VIVO_LIGHT_CCT_TYPE }
            ?: all.firstOrNull { it.stringType?.contains("cct", ignoreCase = true) == true }
    }
}
