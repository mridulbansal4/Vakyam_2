package com.demo.opencv.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import java.util.Locale

/** Emits (kelvin, lux) on the main thread. Does nothing if the phone has no CCT sensor. */
class AmbientColorMonitor(
    context: Context,
    private val onReading: (kelvin: Float, lux: Float) -> Unit
) : SensorEventListener {

    private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val cctSensor: Sensor? = CctReading.findSensor(sensorManager)
    private val lightSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
    private var lux = Float.NaN
    private var running = false
    private var lastRawLogMs = 0L

    fun start() {
        if (running) return
        running = true
        val cct = cctSensor
        if (cct != null) {
            sensorManager.registerListener(this, cct, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            Log.w(TAG, "No CCT sensor; warm tint disabled")
        }
        lightSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        val type = event.sensor.type
        if (type == Sensor.TYPE_LIGHT) {
            lux = event.values[0]
        } else if (type == cctSensor?.type) {
            logRaw(event.values)
            CctReading.kelvinFrom(event.values)?.let { onReading(it, lux) }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun logRaw(values: FloatArray) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRawLogMs < RAW_LOG_INTERVAL_MS) return
        lastRawLogMs = now
        Log.d(TAG, "raw[${values.size}] " + values.joinToString { String.format(Locale.US, "%.2f", it) })
    }

    companion object {
        private const val TAG = "AmbientColor"
        private const val RAW_LOG_INTERVAL_MS = 2_000L
    }
}
