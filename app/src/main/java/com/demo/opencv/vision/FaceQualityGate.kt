package com.demo.opencv.vision

/**
 * What [FaceQualityMeter] measured on one frame's face, all with OpenCV 5. NaN means "not measured", and an
 * unmeasured value never raises an issue.
 */
data class FaceQuality(
    /** Face box width in the upright frame, pixels. */
    val faceWidthPx: Float = Float.NaN,
    /** Variance of the Laplacian over the face, resized to a fixed width so it does not depend on distance. */
    val sharpness: Float = Float.NaN,
    /** Mean grey level of the face, 0-255. */
    val faceLuma: Float = Float.NaN,
    /** Mean grey level of the whole frame, 0-255. */
    val frameLuma: Float = Float.NaN,
    /** Share of face pixels at 250 or above. */
    val clippedFraction: Float = Float.NaN,
    /** Head pose from solvePnP on the YuNet landmarks, degrees. Pitch is the least reliable of the three. */
    val yawDeg: Float = Float.NaN,
    val pitchDeg: Float = Float.NaN,
    val rollDeg: Float = Float.NaN,
    /** YuNet confidence, 0-1. */
    val faceScore: Float = Float.NaN,
) {
    /** One line for logs and benchmark traces. */
    fun summary(): String = String.format(
        java.util.Locale.US,
        "faceW=%.0f sharp=%.0f luma=%.0f frameLuma=%.0f clipped=%.3f yaw=%.0f pitch=%.0f roll=%.0f score=%.2f",
        faceWidthPx, sharpness, faceLuma, frameLuma, clippedFraction, yawDeg, pitchDeg, rollDeg, faceScore,
    )
}

/**
 * A reason the camera image is not good enough. Listed in the order a caregiver should fix them.
 *
 * Only [blocksInput] issues stop a held look from being entered. Everything else is shown to the caregiver and
 * refuses calibration captures, but never locks the patient out: a dim room is a reason to add light, not a
 * reason to go silent.
 */
enum class QualityIssue(val blocksInput: Boolean) {
    /** The head is turned well away from the phone, so the eye templates no longer apply. */
    TURNED_AWAY(true),
    /** The face is too small for the eye crops to carry detail. */
    TOO_FAR(false),
    TOO_DARK(false),
    /** Bright background, dark face: auto-exposure is metering the window, not the person. */
    BACKLIT(false),
    OVEREXPOSED(false),
    BLURRY(false),
    /** The head is tipped well up or down. */
    TILTED(false),
}

object HeadPose {
    /** Yaw (about y), pitch (about x) and roll (about z) in degrees from a row-major 3x3 rotation matrix. */
    @JvmStatic
    fun eulerDegrees(r: DoubleArray): DoubleArray {
        val pitch = Math.toDegrees(kotlin.math.atan2(-r[5], r[8]))
        val yaw = Math.toDegrees(kotlin.math.asin(r[2].coerceIn(-1.0, 1.0)))
        val roll = Math.toDegrees(kotlin.math.atan2(-r[1], r[0]))
        return doubleArrayOf(yaw, pitch, roll)
    }
}

/** Decides which [QualityIssue]s a frame has, and holds each one for [holdMs] so a single bad frame doesn't flicker the UI. */
class FaceQualityGate(private val holdMs: Long = DEFAULT_HOLD_MS) {

    private val firstSeenMs = HashMap<QualityIssue, Long>()

    /** Issues present in the latest frame, with no hold applied. Calibration uses these: a capture frame must itself be clean. */
    var current: Set<QualityIssue> = emptySet()
        private set

    /** Issues present continuously for at least [holdMs]. The UI and live input use these. */
    var active: Set<QualityIssue> = emptySet()
        private set

    val blocksInput: Boolean get() = active.any { it.blocksInput }
    val okForCalibration: Boolean get() = current.isEmpty()

    /** The issue to tell the caregiver about first, or null when the image is fine. */
    val primary: QualityIssue? get() = active.minByOrNull { it.ordinal }

    fun update(quality: FaceQuality?, nowMs: Long): Set<QualityIssue> {
        current = if (quality == null) emptySet() else issuesOf(quality)
        firstSeenMs.keys.retainAll(current)
        for (issue in current) firstSeenMs.getOrPut(issue) { nowMs }
        active = current.filterTo(HashSet()) { nowMs - firstSeenMs.getValue(it) >= holdMs }
        return active
    }

    fun reset() {
        firstSeenMs.clear()
        current = emptySet()
        active = emptySet()
    }

    companion object {
        const val DEFAULT_HOLD_MS = 400L

        // Starting values from desktop tests on sample faces; tune them on the phone.
        const val MAX_YAW_DEG = 35f
        const val MAX_PITCH_DEG = 30f
        const val MIN_FACE_WIDTH_PX = 90f
        const val MIN_FACE_LUMA = 50f
        const val BACKLIT_LUMA_GAP = 50f
        const val BACKLIT_MAX_FACE_LUMA = 100f
        const val MAX_CLIPPED_FRACTION = 0.12f
        const val MIN_SHARPNESS = 150f

        /** The issues one frame has, with no hold applied. */
        @JvmStatic
        fun issuesOf(q: FaceQuality): Set<QualityIssue> {
            val issues = HashSet<QualityIssue>()
            if (abs(q.yawDeg) > MAX_YAW_DEG) issues += QualityIssue.TURNED_AWAY
            if (q.faceWidthPx < MIN_FACE_WIDTH_PX) issues += QualityIssue.TOO_FAR
            if (q.faceLuma < MIN_FACE_LUMA) issues += QualityIssue.TOO_DARK
            if (q.frameLuma - q.faceLuma > BACKLIT_LUMA_GAP && q.faceLuma < BACKLIT_MAX_FACE_LUMA) issues += QualityIssue.BACKLIT
            if (q.clippedFraction > MAX_CLIPPED_FRACTION) issues += QualityIssue.OVEREXPOSED
            if (q.sharpness < MIN_SHARPNESS) issues += QualityIssue.BLURRY
            if (abs(q.pitchDeg) > MAX_PITCH_DEG) issues += QualityIssue.TILTED
            return issues
        }

        // NaN compares false with everything, so an unmeasured value never raises an issue
        private fun abs(v: Float): Float = if (v.isNaN()) Float.NaN else kotlin.math.abs(v)
    }
}
