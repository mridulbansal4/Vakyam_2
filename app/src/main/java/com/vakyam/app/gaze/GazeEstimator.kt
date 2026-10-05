package com.vakyam.app.gaze

import com.vakyam.app.vision.FaceLandmarks
import kotlin.math.hypot

/** A point in root-window pixel coordinates. */
data class ScreenPoint(val x: Float, val y: Float)

/**
 * Gaze features for one frame.
 *
 * @property rawDx horizontal iris offset, unfiltered: the iris centre projected onto the eye's
 *   corner-to-corner axis, as a fraction of eye width, centred on 0 (range roughly ±0.2).
 * @property rawDy vertical iris offset, unfiltered: the iris centre's signed distance from the
 *   corner-to-corner axis as a fraction of eye width (positive = below the axis in the image).
 * @property dx [rawDx] after the One-Euro filter.
 * @property dy [rawDy] after the One-Euro filter.
 * @property eyeOpenness lid gap divided by eye width, averaged over both eyes.
 * @property eyesClosed true while blinking / eyes shut; the offsets are then the last open-eye
 *   values and must not be used to move the pointer.
 * @property faceCenterX face position in the upright camera image, normalised to [0, 1].
 * @property eyeSpan distance between the outer eye corners as a fraction of image width; a proxy
 *   for how far the face is from the phone.
 */
data class GazeSample(
    val rawDx: Float,
    val rawDy: Float,
    val dx: Float,
    val dy: Float,
    val eyeOpenness: Float,
    val eyesClosed: Boolean,
    val faceCenterX: Float,
    val faceCenterY: Float,
    val eyeSpan: Float,
    val timestampMs: Long,
)

/**
 * Turns face landmarks into normalised gaze offsets.
 *
 * For each eye the iris centre is expressed in the eye's own coordinate frame, whose x axis runs
 * from one eye corner to the other. Because that frame moves and rotates with the head, the
 * offsets are invariant to in-plane head translation, roll and distance; the remaining mapping to
 * screen pixels is learned by `CalibrationManager`.
 *
 * Sign convention, for the unmirrored front-camera image this app uses: looking towards the right
 * edge of the screen moves the iris towards the image's left, so dx *decreases*; looking down
 * moves the iris down in the image, so dy *increases*.
 *
 * Sensitivity: the signal is small. Crossing a phone screen held at 30-45 cm turns the eye only a
 * few degrees, which is roughly 0.1 of dx across the width and 0.15-0.2 of dy across the height.
 * The filter defaults are tuned to that scale: [beta] multiplies a speed measured in these units
 * per second, so it has to be large for a real eye movement (a couple of units per second) to
 * open the filter up, while landmark jitter (well under 0.5 units per second once the speed
 * estimate is smoothed) leaves it near [minCutoffHz].
 *
 * Not thread-safe: call from the single vision callback thread.
 */
class GazeEstimator(
    minCutoffHz: Float = 1.2f,
    beta: Float = 6f,
    // Lid gap / eye width is about 0.25-0.35 for an open eye and still 0.12-0.18 when looking
    // down at the bottom of the screen, so the threshold has to sit well below that.
    private val closedEyeThreshold: Float = 0.07f,
) {
    private val filterX = OneEuroFilter(minCutoffHz, beta)
    private val filterY = OneEuroFilter(minCutoffHz, beta)
    private var lastDx = 0f
    private var lastDy = 0f

    /** @return null when the landmarks lack the iris points or the eyes are degenerate. */
    fun estimate(face: FaceLandmarks): GazeSample? {
        if (face.size < REQUIRED_LANDMARKS) return null

        val left = eyeOffsets(face, LEFT_CORNER_A, LEFT_CORNER_B, LEFT_IRIS, LEFT_LID_UPPER, LEFT_LID_LOWER)
            ?: return null
        val right = eyeOffsets(face, RIGHT_CORNER_A, RIGHT_CORNER_B, RIGHT_IRIS, RIGHT_LID_UPPER, RIGHT_LID_LOWER)
            ?: return null

        // Both eye axes point the same way across the image, so the two eyes' offsets share a sign
        // convention and averaging them halves the landmark noise.
        val rawDx = (left.horizontal + right.horizontal) / 2f
        val rawDy = (left.vertical + right.vertical) / 2f
        val openness = (left.openness + right.openness) / 2f
        val closed = openness < closedEyeThreshold

        if (!closed) {
            // Iris landmarks are unreliable under a closed lid; keep the filter state untouched so
            // the pointer resumes from where it was instead of jumping after a blink.
            lastDx = filterX.filter(rawDx, face.timestampMs)
            lastDy = filterY.filter(rawDy, face.timestampMs)
        }

        val outerAx = face.x[LEFT_CORNER_A]
        val outerBx = face.x[RIGHT_CORNER_B]
        return GazeSample(
            rawDx = rawDx,
            rawDy = rawDy,
            dx = lastDx,
            dy = lastDy,
            eyeOpenness = openness,
            eyesClosed = closed,
            faceCenterX = (outerAx + outerBx) / 2f,
            faceCenterY = (face.y[LEFT_CORNER_A] + face.y[RIGHT_CORNER_B]) / 2f,
            eyeSpan = kotlin.math.abs(outerBx - outerAx),
            timestampMs = face.timestampMs,
        )
    }

    fun reset() {
        filterX.reset()
        filterY.reset()
    }

    private class EyeOffsets(val horizontal: Float, val vertical: Float, val openness: Float)

    private fun eyeOffsets(
        face: FaceLandmarks,
        cornerA: Int,
        cornerB: Int,
        iris: Int,
        lidUpper: Int,
        lidLower: Int,
    ): EyeOffsets? {
        val ax = face.px(cornerA)
        val ay = face.py(cornerA)
        val axisX = face.px(cornerB) - ax
        val axisY = face.py(cornerB) - ay
        val widthSquared = axisX * axisX + axisY * axisY
        if (widthSquared < MIN_EYE_WIDTH_PX * MIN_EYE_WIDTH_PX) return null

        val irisX = face.px(iris) - ax
        val irisY = face.py(iris) - ay
        // Projection onto the corner axis (0 at corner A, 1 at corner B), centred on the middle.
        val horizontal = (irisX * axisX + irisY * axisY) / widthSquared - 0.5f
        // 2D cross product = perpendicular distance from the axis, in eye widths.
        val vertical = (axisX * irisY - axisY * irisX) / widthSquared

        val lidGap = hypot(
            face.px(lidUpper) - face.px(lidLower),
            face.py(lidUpper) - face.py(lidLower),
        )
        val openness = lidGap / kotlin.math.sqrt(widthSquared)
        return EyeOffsets(horizontal, vertical, openness)
    }

    companion object {
        /** The refined face mesh has 468 face points followed by 2 x 5 iris points. */
        const val REQUIRED_LANDMARKS = 478

        // "Left"/"right" follow MediaPipe's index naming. Corner A is the image-left corner and
        // corner B the image-right corner of each eye in an unmirrored image.
        const val LEFT_IRIS = 468
        const val LEFT_CORNER_A = 33
        const val LEFT_CORNER_B = 133
        const val LEFT_LID_UPPER = 159
        const val LEFT_LID_LOWER = 145

        const val RIGHT_IRIS = 473
        const val RIGHT_CORNER_A = 362
        const val RIGHT_CORNER_B = 263
        const val RIGHT_LID_UPPER = 386
        const val RIGHT_LID_LOWER = 374

        private const val MIN_EYE_WIDTH_PX = 4f
    }
}
