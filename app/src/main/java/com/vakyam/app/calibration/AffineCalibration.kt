package com.vakyam.app.calibration

import com.vakyam.app.gaze.ScreenPoint
import kotlin.math.abs
import kotlin.math.sqrt

/** How a calibration was obtained, from most to least trustworthy. */
enum class CalibrationQuality {
    /** Full least-squares affine fit with an acceptable residual. */
    FITTED,

    /** Per-axis fallback: at least one screen axis was measured from the calibration points. */
    APPROXIMATE,

    /** Nothing usable was measured; built-in gains centred on a reference gaze sample. */
    DEFAULT,
}

/**
 * Affine map from gaze offsets to screen pixels:
 *
 *     X = x0 + xDx * dx + xDy * dy
 *     Y = y0 + yDx * dx + yDy * dy
 *
 * Valid only for the screen size it was fitted on.
 */
data class AffineCalibration(
    val x0: Float,
    val xDx: Float,
    val xDy: Float,
    val y0: Float,
    val yDx: Float,
    val yDy: Float,
    val screenWidth: Int,
    val screenHeight: Int,
    /** Root-mean-square distance between the fitted and the true target positions, in pixels. */
    val rmsErrorPx: Float,
    val quality: CalibrationQuality = CalibrationQuality.FITTED,
) {
    /** Maps gaze offsets to a screen point, clamped to the screen. */
    fun map(dx: Float, dy: Float): ScreenPoint = ScreenPoint(
        x = (x0 + xDx * dx + xDy * dy).coerceIn(0f, screenWidth.toFloat()),
        y = (y0 + yDx * dx + yDy * dy).coerceIn(0f, screenHeight.toFloat()),
    )
}

/** One calibration observation: the gaze offsets measured while looking at a known screen point. */
data class CalibrationSample(val dx: Float, val dy: Float, val screenX: Float, val screenY: Float)

object AffineSolver {

    /** Below this, dx and dy are so correlated that one screen axis cannot be recovered. */
    private const val MIN_INDEPENDENCE = 0.05

    /** An affine fit whose residual exceeds this fraction of the screen diagonal is not trusted. */
    private const val MAX_RMS_FRACTION = 0.12f

    /**
     * Built-in gains: the change in offset that corresponds to looking from one screen edge to the
     * opposite one, for a phone held 30-45 cm away. The eye turns only a few degrees to cross a
     * phone's width, so these are small.
     */
    private const val DEFAULT_DX_SPAN = 0.10f
    private const val DEFAULT_DY_SPAN = 0.18f

    /** A measured edge-to-edge offset difference smaller than this is noise, not signal. */
    private const val MIN_DX_SPAN = 0.010f
    private const val MIN_DY_SPAN = 0.015f

    /** Samples within this fraction of the screen centre on an axis belong to neither side of it. */
    private const val SIDE_MARGIN = 0.2f

    /**
     * Best available calibration for [samples]. Never fails:
     *
     * 1. the full affine [fit], if it exists and its residual is acceptable;
     * 2. otherwise [fitAxisAligned], which needs far less from the data;
     * 3. which itself falls back to built-in gains on any axis it cannot measure.
     */
    fun solve(samples: List<CalibrationSample>, screenWidth: Int, screenHeight: Int): AffineCalibration {
        val diagonal = sqrt((screenWidth.toFloat() * screenWidth + screenHeight.toFloat() * screenHeight))
        val affine = fit(samples, screenWidth, screenHeight)
        if (affine != null && affine.rmsErrorPx <= MAX_RMS_FRACTION * diagonal) return affine
        return fitAxisAligned(samples, screenWidth, screenHeight)
    }

    /**
     * Ordinary least-squares fit of [AffineCalibration] to [samples] (at least 3, not collinear in
     * feature space).
     *
     * The offsets are standardised first (zero mean, unit variance). That makes the normal
     * equations well conditioned despite the offsets being tiny numbers, and reduces them to a
     * 2x2 system: with u, v standardised, sum(u) = sum(v) = 0 and sum(u²) = sum(v²) = n, so
     *
     *     [ n  S ] [c1]   [sum(u·T)]
     *     [ S  n ] [c2] = [sum(v·T)]        with S = sum(u·v), and c0 = mean(T)
     *
     * for each target axis T (screen X, then screen Y).
     *
     * @return null if the samples are degenerate.
     */
    fun fit(samples: List<CalibrationSample>, screenWidth: Int, screenHeight: Int): AffineCalibration? {
        val n = samples.size
        if (n < 3) return null

        val meanDx = samples.sumOf { it.dx.toDouble() } / n
        val meanDy = samples.sumOf { it.dy.toDouble() } / n
        val stdDx = sqrt(samples.sumOf { (it.dx - meanDx) * (it.dx - meanDx) } / n)
        val stdDy = sqrt(samples.sumOf { (it.dy - meanDy) * (it.dy - meanDy) } / n)
        if (stdDx < 1e-6 || stdDy < 1e-6) return null

        var sumUv = 0.0
        var sumUx = 0.0
        var sumVx = 0.0
        var sumUy = 0.0
        var sumVy = 0.0
        var meanX = 0.0
        var meanY = 0.0
        for (s in samples) {
            val u = (s.dx - meanDx) / stdDx
            val v = (s.dy - meanDy) / stdDy
            sumUv += u * v
            sumUx += u * s.screenX
            sumVx += v * s.screenX
            sumUy += u * s.screenY
            sumVy += v * s.screenY
            meanX += s.screenX
            meanY += s.screenY
        }
        meanX /= n
        meanY /= n

        val correlation = sumUv / n
        if (1.0 - correlation * correlation < MIN_INDEPENDENCE) return null
        val det = n.toDouble() * n - sumUv * sumUv

        // Coefficients in standardised space, then folded back to raw offsets.
        val xU = (n * sumUx - sumUv * sumVx) / det
        val xV = (n * sumVx - sumUv * sumUx) / det
        val yU = (n * sumUy - sumUv * sumVy) / det
        val yV = (n * sumVy - sumUv * sumUy) / det

        val xDx = xU / stdDx
        val xDy = xV / stdDy
        val yDx = yU / stdDx
        val yDy = yV / stdDy
        val x0 = meanX - xDx * meanDx - xDy * meanDy
        val y0 = meanY - yDx * meanDx - yDy * meanDy

        return AffineCalibration(
            x0 = x0.toFloat(),
            xDx = xDx.toFloat(),
            xDy = xDy.toFloat(),
            y0 = y0.toFloat(),
            yDx = yDx.toFloat(),
            yDy = yDy.toFloat(),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            rmsErrorPx = 0f,
            quality = CalibrationQuality.FITTED,
        ).withResidual(samples)
    }

    /**
     * Fallback fit that treats the two screen axes independently: X depends on dx only and Y on dy
     * only. For each axis it draws the line through the mean offset of the targets on one side of
     * the screen and the mean offset of the targets on the other side. Two numbers per axis are
     * much harder to get wrong than the six of a full affine fit, at the cost of ignoring
     * cross-talk between the axes.
     *
     * An axis whose two sides are indistinguishable (or missing) uses the built-in gain instead,
     * centred on the offset measured at the centre target.
     */
    fun fitAxisAligned(samples: List<CalibrationSample>, screenWidth: Int, screenHeight: Int): AffineCalibration {
        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f
        val marginX = SIDE_MARGIN * screenWidth
        val marginY = SIDE_MARGIN * screenHeight

        // Where the eyes rest when looking at the middle of the screen.
        val central = samples.filter { abs(it.screenX - centerX) <= marginX && abs(it.screenY - centerY) <= marginY }
        val reference = central.ifEmpty { samples }
        val referenceDx = reference.meanOf { it.dx } ?: 0f
        val referenceDy = reference.meanOf { it.dy } ?: 0f

        val xAxis = fitAxis(
            low = samples.filter { it.screenX < centerX - marginX },
            high = samples.filter { it.screenX > centerX + marginX },
            offset = { it.dx },
            position = { it.screenX },
            minSpan = MIN_DX_SPAN,
        )
        val yAxis = fitAxis(
            low = samples.filter { it.screenY < centerY - marginY },
            high = samples.filter { it.screenY > centerY + marginY },
            offset = { it.dy },
            position = { it.screenY },
            minSpan = MIN_DY_SPAN,
        )

        val default = defaultCalibration(screenWidth, screenHeight, referenceDx, referenceDy)
        return AffineCalibration(
            x0 = xAxis?.intercept ?: default.x0,
            xDx = xAxis?.slope ?: default.xDx,
            xDy = 0f,
            y0 = yAxis?.intercept ?: default.y0,
            yDx = 0f,
            yDy = yAxis?.slope ?: default.yDy,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            rmsErrorPx = 0f,
            quality = if (xAxis != null || yAxis != null) CalibrationQuality.APPROXIMATE else CalibrationQuality.DEFAULT,
        ).withResidual(samples)
    }

    /**
     * Calibration from built-in gains alone, mapping the offsets ([centerDx], [centerDy]) to the
     * middle of the screen.
     *
     * Signs follow the unmirrored front-camera image: looking towards the right edge of the screen
     * moves the iris towards the image's left, so X falls as dx rises; looking down moves the iris
     * down in the image, so Y rises with dy.
     */
    fun defaultCalibration(screenWidth: Int, screenHeight: Int, centerDx: Float, centerDy: Float): AffineCalibration {
        val xDx = -screenWidth / DEFAULT_DX_SPAN
        val yDy = screenHeight / DEFAULT_DY_SPAN
        return AffineCalibration(
            x0 = screenWidth / 2f - xDx * centerDx,
            xDx = xDx,
            xDy = 0f,
            y0 = screenHeight / 2f - yDy * centerDy,
            yDx = 0f,
            yDy = yDy,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            rmsErrorPx = 0f,
            quality = CalibrationQuality.DEFAULT,
        )
    }

    private class AxisFit(val slope: Float, val intercept: Float)

    private inline fun fitAxis(
        low: List<CalibrationSample>,
        high: List<CalibrationSample>,
        offset: (CalibrationSample) -> Float,
        position: (CalibrationSample) -> Float,
        minSpan: Float,
    ): AxisFit? {
        val lowOffset = low.meanOf(offset) ?: return null
        val highOffset = high.meanOf(offset) ?: return null
        val span = highOffset - lowOffset
        if (abs(span) < minSpan) return null
        val lowPosition = low.meanOf(position)!!
        val highPosition = high.meanOf(position)!!
        val slope = (highPosition - lowPosition) / span
        return AxisFit(slope, intercept = lowPosition - slope * lowOffset)
    }

    private inline fun List<CalibrationSample>.meanOf(selector: (CalibrationSample) -> Float): Float? {
        if (isEmpty()) return null
        var sum = 0f
        for (sample in this) sum += selector(sample)
        return sum / size
    }

    private fun AffineCalibration.withResidual(samples: List<CalibrationSample>): AffineCalibration {
        if (samples.isEmpty()) return this
        var squaredError = 0.0
        for (s in samples) {
            // Unclamped on purpose: the residual should reflect how far off the map really is.
            val ex = x0 + xDx * s.dx + xDy * s.dy - s.screenX
            val ey = y0 + yDx * s.dx + yDy * s.dy - s.screenY
            squaredError += ex * ex + ey * ey
        }
        return copy(rmsErrorPx = sqrt(squaredError / samples.size).toFloat())
    }
}
