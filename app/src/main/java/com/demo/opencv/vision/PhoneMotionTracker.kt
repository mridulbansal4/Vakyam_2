package com.demo.opencv.vision

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * Tracks how the hand-held phone is moving, from the gyroscope and accelerometer.
 *
 * - [pitch] / [yaw]: phone rotation about its X / Y axes (radians), integrated from the gyroscope.
 *   Only changes over a short window are meaningful; the absolute value drifts slowly.
 * - [isShaking]: true for a real shake or bump, false for ordinary hand tremor, so calibration can
 *   keep collecting samples while the phone is held by hand.
 *
 * [Integrator] holds the maths so it can be unit tested without Android sensors.
 */
class PhoneMotionTracker(context: Context?) : SensorEventListener {

    class Integrator(
        private val shakeAngularSpeed: Float = 1.2f, // rad/s; hand tremor is typically < 0.3
        private val shakeLinearAccel: Float = 3.0f, // m/s^2 with gravity removed
        private val smoothing: Float = 0.3f
    ) {
        var pitch = 0f
            private set
        var yaw = 0f
            private set
        var angularSpeed = 0f
            private set
        var linearAccel = 0f
            private set
        var hasGyro = false
            private set
        private var lastGyroNs = 0L

        fun onGyro(wx: Float, wy: Float, wz: Float, timestampNs: Long) {
            hasGyro = true
            if (lastGyroNs != 0L && timestampNs > lastGyroNs) {
                val dt = ((timestampNs - lastGyroNs) / 1e9f).coerceAtMost(0.1f)
                pitch += wx * dt
                yaw += wy * dt
            }
            lastGyroNs = timestampNs
            val speed = sqrt(wx * wx + wy * wy + wz * wz)
            angularSpeed += smoothing * (speed - angularSpeed)
        }

        fun onLinearAccel(ax: Float, ay: Float, az: Float) {
            val magnitude = sqrt(ax * ax + ay * ay + az * az)
            linearAccel += smoothing * (magnitude - linearAccel)
        }

        val isShaking: Boolean
            get() = angularSpeed > shakeAngularSpeed || linearAccel > shakeLinearAccel
    }

    private val integrator = Integrator()
    private val sensorManager = context?.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyro = sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val linearAccelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val accelSensor = if (linearAccelSensor == null) sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
    private val gravity = FloatArray(3)
    private var hasGravity = false

    @Volatile var pitch = Float.NaN
        private set
    @Volatile var yaw = Float.NaN
        private set
    @Volatile var isShaking = false
        private set

    fun start() {
        val sm = sensorManager ?: return
        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        (linearAccelSensor ?: accelSensor)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val v = event.values
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> integrator.onGyro(v[0], v[1], v[2], event.timestamp)
            Sensor.TYPE_LINEAR_ACCELERATION -> integrator.onLinearAccel(v[0], v[1], v[2])
            Sensor.TYPE_ACCELEROMETER -> {
                if (!hasGravity) {
                    v.copyInto(gravity, 0, 0, 3)
                    hasGravity = true
                }
                for (i in 0..2) gravity[i] = 0.8f * gravity[i] + 0.2f * v[i]
                integrator.onLinearAccel(v[0] - gravity[0], v[1] - gravity[1], v[2] - gravity[2])
            }
        }
        if (integrator.hasGyro) {
            pitch = integrator.pitch
            yaw = integrator.yaw
        }
        isShaking = integrator.isShaking
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
