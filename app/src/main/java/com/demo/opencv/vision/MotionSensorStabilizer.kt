package com.demo.opencv.vision

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

/**
 * IMU-based Shock and Vibration Stabilizer ("Motion Circuit Breaker").
 *
 * When a wheelchair hits a curb or a vehicle encounters a pothole, the physical shock causes
 * sudden head-camera displacement and triggers the human Vestibulo-Ocular Reflex (VOR).
 *
 * This component computes 3D Jerk (derivative of linear acceleration):
 *   J(t) = ||d a(t) / dt||
 *
 * If jerk exceeds the shock threshold (default 4.5 m/s^3), it engages a temporary
 * Motion-Hold window (300ms) that:
 * 1. Suppresses all gaze gesture evaluations.
 * 2. Prevents false positive inputs caused by involuntary head bobs or phone vibrations.
 * 3. Keeps typing state intact rather than resetting or mis-selecting.
 */
class MotionSensorStabilizer @JvmOverloads constructor(
    private val context: Context? = null,
    private val jerkThreshold: Float = 35.0f,
    private val suppressionDurationMs: Long = 300L
) : SensorEventListener {

    class JerkProcessor(
        private val threshold: Float = 4.5f,
        private val lockDurationMs: Long = 300L
    ) {
        private var lastAx = 0f
        private var lastAy = 0f
        private var lastAz = 0f
        private var lastTimestampNs = 0L
        @Volatile
        private var lockUntilMs = 0L

        fun update(ax: Float, ay: Float, az: Float, timestampNs: Long, currentTimeMs: Long): Boolean {
            if (lastTimestampNs == 0L) {
                lastAx = ax
                lastAy = ay
                lastAz = az
                lastTimestampNs = timestampNs
                return isStable(currentTimeMs)
            }

            if (timestampNs <= lastTimestampNs) {
                return isStable(currentTimeMs)
            }

            val dtNs = timestampNs - lastTimestampNs
            if (dtNs > 250_000_000L) {
                // Extended gap (>250ms between sensor updates, e.g. pause/resume): reset baseline
                lastAx = ax
                lastAy = ay
                lastAz = az
                lastTimestampNs = timestampNs
                return isStable(currentTimeMs)
            }

            val dt = (dtNs / 1_000_000_000.0f).coerceAtLeast(0.002f)

            val jx = (ax - lastAx) / dt
            val jy = (ay - lastAy) / dt
            val jz = (az - lastAz) / dt
            val totalJerk = sqrt(jx * jx + jy * jy + jz * jz)

            if (totalJerk > threshold) {
                lockUntilMs = currentTimeMs + lockDurationMs
            }

            lastAx = ax
            lastAy = ay
            lastAz = az
            lastTimestampNs = timestampNs

            return isStable(currentTimeMs)
        }

        fun isStable(currentTimeMs: Long): Boolean {
            return currentTimeMs >= lockUntilMs
        }
    }

    private val processor = JerkProcessor(jerkThreshold, suppressionDurationMs)
    private var sensorManager: SensorManager? = null
    private var accelSensor: Sensor? = null

    init {
        context?.let {
            sensorManager = it.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            accelSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        }
    }

    fun start() {
        accelSensor?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
    }

    fun isMotionStable(): Boolean {
        return processor.isStable(System.currentTimeMillis())
    }

    fun processDirect(ax: Float, ay: Float, az: Float, timestampNs: Long, currentTimeMs: Long): Boolean {
        return processor.update(ax, ay, az, timestampNs, currentTimeMs)
    }

    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var hasGravityBaseline = false

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val nowMs = System.currentTimeMillis()
        var ax = event.values[0]
        var ay = event.values[1]
        var az = event.values[2]

        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val alpha = 0.8f
            if (!hasGravityBaseline) {
                gravityX = ax
                gravityY = ay
                gravityZ = az
                hasGravityBaseline = true
            } else {
                gravityX = alpha * gravityX + (1f - alpha) * ax
                gravityY = alpha * gravityY + (1f - alpha) * ay
                gravityZ = alpha * gravityZ + (1f - alpha) * az
            }
            ax -= gravityX
            ay -= gravityY
            az -= gravityZ
        }

        processor.update(
            ax,
            ay,
            az,
            event.timestamp,
            nowMs
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
