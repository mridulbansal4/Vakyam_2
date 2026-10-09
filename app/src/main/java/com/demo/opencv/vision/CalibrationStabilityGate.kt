package com.demo.opencv.vision

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Decides when a calibration step should be captured automatically, for a hand-held phone.
 *
 * A step is captured once the (smoothed) iris position has stayed steady for [holdMs] with eyes
 * open and both eye crops visible. Frames during the first [settleMs] of a step are ignored so the
 * user can hear the instruction and move their eyes.
 *
 * Hand-held robustness:
 * - Phone motion compensation: when the gyro angles of each sample are given, the part of the
 *   gaze movement that is explained by the phone rotating (e.g. the eyes following the moving
 *   screen) is removed before steadiness is judged.
 * - A real shake pauses collection instead of discarding it; the window is only cleared if the
 *   shake lasts longer than [shakeResetMs].
 * - Only the axes that matter for the step's [Direction] are checked (the vertical ratio is very
 *   noisy when looking far left/right), using a trimmed range so one bad frame doesn't block.
 *
 * When a Straight reference is set, the held position must also be displaced from it in the
 * step's direction by [minDisplacement], so the wrong gaze is never captured.
 *
 * Each call to [escalate] (done when a step is re-prompted) makes the gate more lenient: level 1
 * halves the required displacement and doubles the allowed spread; level 2 drops the direction
 * check, triples the spread and halves the hold time.
 */
class CalibrationStabilityGate @JvmOverloads constructor(
    private val windowSize: Int = 8,
    private val maxSpreadX: Float = 0.05f,
    private val maxSpreadY: Float = 0.10f,
    private val holdMs: Long = 400L,
    private val settleMs: Long = 700L,
    private val minDisplacement: Float = 0.06f,
    private val shakeResetMs: Long = 600L
) {
    enum class Status { SETTLING, NO_EYES, EYES_CLOSED, PHONE_MOVING, NOT_STEADY, TOO_CLOSE_TO_REFERENCE, HOLDING, CAPTURED }

    /** Expected gaze movement from Straight: x grows to the user's right, y grows downwards. */
    enum class Direction(val dx: Int, val dy: Int) {
        STRAIGHT(0, 0), LEFT(-1, 0), RIGHT(1, 0), UP(0, -1), DOWN(0, 1), LEFT_UP(-1, -1), RIGHT_UP(1, -1)
    }

    private val xs = ArrayDeque<Float>()
    private val ys = ArrayDeque<Float>()
    private val pitches = ArrayDeque<Float>()
    private val yaws = ArrayDeque<Float>()
    private var stableSinceMs = -1L
    private var shakeSinceMs = -1L
    private var stepStartMs = 0L
    private var direction = Direction.STRAIGHT
    private var referenceX = Float.NaN
    private var referenceY = Float.NaN

    var status = Status.SETTLING
        private set
    var leniency = 0
        private set
    var lastMeanX = Float.NaN
        private set
    var lastMeanY = Float.NaN
        private set
    var lastSpread = Float.NaN
        private set
    var lastDistanceFromReference = Float.NaN
        private set
    /** Largest gaze correction applied for phone rotation in the last window. */
    var lastMotionCorrection = 0f
        private set

    private val spreadScale get() = (1 + leniency).toFloat()
    private val requiredHoldMs get() = if (leniency >= 2) holdMs / 2 else holdMs

    @JvmOverloads
    fun startStep(nowMs: Long, stepDirection: Direction = Direction.STRAIGHT) {
        clearWindow()
        stepStartMs = nowMs
        direction = stepDirection
        leniency = 0
        shakeSinceMs = -1L
        status = Status.SETTLING
        lastSpread = Float.NaN
        lastDistanceFromReference = Float.NaN
        lastMotionCorrection = 0f
    }

    fun setReference(x: Float, y: Float) {
        referenceX = x
        referenceY = y
    }

    fun clearReference() {
        referenceX = Float.NaN
        referenceY = Float.NaN
    }

    /** Make the current step easier to capture; resets on the next [startStep]. */
    fun escalate() {
        if (leniency < 2) leniency++
        stableSinceMs = -1L
    }

    /**
     * Returns true when the current step should be captured. [motionStable] is false while the
     * phone is being shaken; [phonePitch]/[phoneYaw] are gyro-integrated phone angles (radians) or
     * NaN when no gyroscope is available.
     */
    @JvmOverloads
    fun update(
        x: Float,
        y: Float,
        eyesOpen: Boolean,
        motionStable: Boolean,
        eyesVisible: Boolean,
        nowMs: Long,
        phonePitch: Float = Float.NaN,
        phoneYaw: Float = Float.NaN
    ): Boolean {
        val blocked = when {
            nowMs - stepStartMs < settleMs -> Status.SETTLING
            !eyesVisible || x.isNaN() || y.isNaN() -> Status.NO_EYES
            !eyesOpen -> Status.EYES_CLOSED
            else -> null
        }
        if (blocked != null) {
            clearWindow()
            status = blocked
            return false
        }

        if (!motionStable) {
            if (shakeSinceMs < 0L) shakeSinceMs = nowMs
            if (nowMs - shakeSinceMs > shakeResetMs) clearWindow() else stableSinceMs = -1L
            status = Status.PHONE_MOVING
            return false
        }
        shakeSinceMs = -1L

        xs.addLast(x)
        ys.addLast(y)
        pitches.addLast(phonePitch)
        yaws.addLast(phoneYaw)
        if (xs.size > windowSize) {
            xs.removeFirst()
            ys.removeFirst()
            pitches.removeFirst()
            yaws.removeFirst()
        }
        if (xs.size < windowSize) {
            status = Status.NOT_STEADY
            return false
        }

        lastMotionCorrection = 0f
        val spreadX = trimmedRange(compensate(xs))
        val spreadY = trimmedRange(compensate(ys))
        val (limitX, limitY) = axisLimits()
        lastSpread = maxOf(if (limitX.isFinite()) spreadX else 0f, if (limitY.isFinite()) spreadY else 0f)
        if (spreadX >= limitX * spreadScale || spreadY >= limitY * spreadScale) {
            stableSinceMs = -1L
            status = Status.NOT_STEADY
            return false
        }

        val meanX = median(xs)
        val meanY = median(ys)
        lastMeanX = meanX
        lastMeanY = meanY

        if (!referenceX.isNaN()) {
            lastDistanceFromReference = hypot(meanX - referenceX, meanY - referenceY)
            if (!movedInDirection(meanX - referenceX, meanY - referenceY)) {
                stableSinceMs = -1L
                status = Status.TOO_CLOSE_TO_REFERENCE
                return false
            }
        }

        if (stableSinceMs < 0L) stableSinceMs = nowMs
        if (nowMs - stableSinceMs >= requiredHoldMs) {
            clearWindow()
            status = Status.CAPTURED
            return true
        }
        status = Status.HOLDING
        return false
    }

    /** Hold progress from 0 to 1, for UI feedback. */
    fun progress(nowMs: Long): Float =
        if (stableSinceMs < 0L) 0f
        else ((nowMs - stableSinceMs) / requiredHoldMs.toFloat()).coerceIn(0f, 1f)

    private fun axisLimits(): Pair<Float, Float> = when (direction) {
        Direction.LEFT, Direction.RIGHT -> maxSpreadX to Float.POSITIVE_INFINITY
        Direction.UP, Direction.DOWN -> maxSpreadX * 2 to maxSpreadY
        else -> maxSpreadX to maxSpreadY
    }

    private fun movedInDirection(dx: Float, dy: Float): Boolean {
        if (direction == Direction.STRAIGHT || leniency >= 2) return true
        val required = if (leniency == 0) minDisplacement else minDisplacement / 2
        if (direction.dx != 0 && dx * direction.dx < required) return false
        if (direction.dy != 0 && dy * direction.dy < required) return false
        return true
    }

    /**
     * Removes the component of [values] explained by the phone's rotation over the window, using a
     * small ridge regression on the gyro angles. The fitted gain is clamped so a noisy fit can only
     * remove a bounded amount.
     */
    private fun compensate(values: ArrayDeque<Float>): FloatArray {
        val n = values.size
        val out = FloatArray(n) { values[it] }
        if (pitches.any { it.isNaN() } || yaws.any { it.isNaN() }) return out

        val vm = values.average().toFloat()
        val pm = pitches.average().toFloat()
        val qm = yaws.average().toFloat()
        var spp = 0f; var sqq = 0f; var spq = 0f; var svp = 0f; var svq = 0f
        for (i in 0 until n) {
            val v = values[i] - vm
            val p = pitches[i] - pm
            val q = yaws[i] - qm
            spp += p * p; sqq += q * q; spq += p * q; svp += v * p; svq += v * q
        }
        val ridge = n * RIDGE_ANGLE * RIDGE_ANGLE
        val a = spp + ridge
        val d = sqq + ridge
        val det = a * d - spq * spq
        if (det <= 0f) return out
        val gainP = ((svp * d - svq * spq) / det).coerceIn(-MAX_GAIN, MAX_GAIN)
        val gainQ = ((svq * a - svp * spq) / det).coerceIn(-MAX_GAIN, MAX_GAIN)
        for (i in 0 until n) {
            val correction = gainP * (pitches[i] - pm) + gainQ * (yaws[i] - qm)
            out[i] -= correction
            lastMotionCorrection = maxOf(lastMotionCorrection, abs(correction))
        }
        return out
    }

    private fun trimmedRange(values: FloatArray): Float {
        values.sort()
        val trim = if (values.size >= 6) 1 else 0
        return values[values.size - 1 - trim] - values[trim]
    }

    private fun median(values: ArrayDeque<Float>): Float {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
    }

    private fun clearWindow() {
        xs.clear()
        ys.clear()
        pitches.clear()
        yaws.clear()
        stableSinceMs = -1L
    }

    private companion object {
        const val MAX_GAIN = 1.5f // gaze-ratio units per radian of phone rotation
        const val RIDGE_ANGLE = 0.005f // radians; below this phone rotation the fit is ignored
    }
}
