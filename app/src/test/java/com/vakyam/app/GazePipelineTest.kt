package com.vakyam.app

import com.vakyam.app.calibration.AffineCalibration
import com.vakyam.app.calibration.AffineSolver
import com.vakyam.app.calibration.CalibrationManager
import com.vakyam.app.calibration.CalibrationQuality
import com.vakyam.app.calibration.CalibrationSample
import com.vakyam.app.calibration.CalibrationStep
import com.vakyam.app.calibration.CalibrationStore
import com.vakyam.app.gaze.GazeEstimator
import com.vakyam.app.gaze.GazeSample
import com.vakyam.app.gaze.OneEuroFilter
import com.vakyam.app.vision.FaceLandmarks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class GazePipelineTest {

    private val width = 1080
    private val height = 2340

    /** A synthetic face: eyes on a horizontal line, irises displaced by the given eye-width fractions. */
    private fun face(
        irisDx: Float,
        irisDy: Float,
        rollDegrees: Double = 0.0,
        lidGap: Float = 0.3f,
        timestampMs: Long = 0,
    ): FaceLandmarks {
        val imageW = 480
        val imageH = 640
        val x = FloatArray(GazeEstimator.REQUIRED_LANDMARKS)
        val y = FloatArray(GazeEstimator.REQUIRED_LANDMARKS)
        val cosR = cos(Math.toRadians(rollDegrees))
        val sinR = sin(Math.toRadians(rollDegrees))
        fun put(index: Int, pxX: Double, pxY: Double) {
            // Rotate about the image centre to simulate head roll.
            val cx = pxX - imageW / 2.0
            val cy = pxY - imageH / 2.0
            x[index] = ((cx * cosR - cy * sinR + imageW / 2.0) / imageW).toFloat()
            y[index] = ((cx * sinR + cy * cosR + imageH / 2.0) / imageH).toFloat()
        }
        val eyeWidth = 60.0
        fun eye(cornerA: Int, cornerB: Int, iris: Int, lidUp: Int, lidLow: Int, startX: Double) {
            val eyeY = 300.0
            put(cornerA, startX, eyeY)
            put(cornerB, startX + eyeWidth, eyeY)
            put(iris, startX + eyeWidth * (0.5 + irisDx), eyeY + eyeWidth * irisDy)
            put(lidUp, startX + eyeWidth / 2, eyeY - eyeWidth * lidGap / 2)
            put(lidLow, startX + eyeWidth / 2, eyeY + eyeWidth * lidGap / 2)
        }
        eye(
            GazeEstimator.LEFT_CORNER_A, GazeEstimator.LEFT_CORNER_B, GazeEstimator.LEFT_IRIS,
            GazeEstimator.LEFT_LID_UPPER, GazeEstimator.LEFT_LID_LOWER, 130.0,
        )
        eye(
            GazeEstimator.RIGHT_CORNER_A, GazeEstimator.RIGHT_CORNER_B, GazeEstimator.RIGHT_IRIS,
            GazeEstimator.RIGHT_LID_UPPER, GazeEstimator.RIGHT_LID_LOWER, 290.0,
        )
        return FaceLandmarks(x, y, imageW, imageH, timestampMs)
    }

    private fun sample(dx: Float, dy: Float, closed: Boolean = false, t: Long = 0) =
        GazeSample(dx, dy, dx, dy, if (closed) 0.02f else 0.3f, closed, 0.5f, 0.5f, 0.2f, t)

    private class MemoryStore : CalibrationStore {
        var saved: AffineCalibration? = null
        override fun load() = saved
        override fun save(calibration: AffineCalibration) { saved = calibration }
        override fun clear() { saved = null }
    }

    @Test
    fun estimatorRecoversIrisOffsets() {
        val result = GazeEstimator().estimate(face(irisDx = 0.1f, irisDy = -0.05f))!!
        assertEquals(0.1f, result.rawDx, 1e-3f)
        assertEquals(-0.05f, result.rawDy, 1e-3f)
        assertFalse(result.eyesClosed)
    }

    @Test
    fun estimatorIsInvariantToHeadRoll() {
        val result = GazeEstimator().estimate(face(irisDx = 0.1f, irisDy = -0.05f, rollDegrees = 20.0))!!
        assertEquals(0.1f, result.rawDx, 2e-3f)
        assertEquals(-0.05f, result.rawDy, 2e-3f)
    }

    @Test
    fun closedEyesAreFlaggedAndDoNotMoveTheFilteredGaze() {
        val estimator = GazeEstimator()
        val open = estimator.estimate(face(0.1f, 0f, timestampMs = 0))!!
        val closed = estimator.estimate(face(-0.2f, 0.1f, lidGap = 0.03f, timestampMs = 33))!!
        assertTrue(closed.eyesClosed)
        assertEquals(open.dx, closed.dx, 0f)
        assertEquals(open.dy, closed.dy, 0f)
    }

    @Test
    fun estimatorRejectsMeshWithoutIrisLandmarks() {
        val noIris = FaceLandmarks(FloatArray(468), FloatArray(468), 480, 640, 0)
        assertNull(GazeEstimator().estimate(noIris))
    }

    @Test
    fun oneEuroFilterSuppressesJitterButFollowsSaccades() {
        val filter = OneEuroFilter(minCutoffHz = 0.8f, beta = 1.5f)
        var t = 0L
        var out = 0f
        // Fixation with +-0.01 alternating jitter around 0.
        repeat(60) { i ->
            out = filter.filter(if (i % 2 == 0) 0.01f else -0.01f, t)
            t += 33
        }
        assertTrue("jitter should be attenuated, was $out", abs(out) < 0.006f)
        // Saccade to 0.2: must be mostly there within ~200 ms.
        repeat(6) {
            out = filter.filter(0.2f, t)
            t += 33
        }
        assertTrue("saccade should be followed, was $out", out > 0.15f)
    }

    @Test
    fun affineFitRecoversKnownMapping() {
        // Screen = (540 - 4000*dx + 300*dy, 1170 + 200*dx + 9000*dy), i.e. mirrored x with cross-talk.
        fun truth(dx: Float, dy: Float) = (540f - 4000f * dx + 300f * dy) to (1170f + 200f * dx + 9000f * dy)
        val offsets = listOf(0f to 0f, 0.1f to -0.1f, -0.1f to -0.1f, 0.1f to 0.1f, -0.1f to 0.1f)
        val samples = offsets.map { (dx, dy) ->
            val (sx, sy) = truth(dx, dy)
            CalibrationSample(dx, dy, sx, sy)
        }
        val fit = AffineSolver.fit(samples, width, height)!!
        assertEquals(-4000f, fit.xDx, 1f)
        assertEquals(300f, fit.xDy, 1f)
        assertEquals(9000f, fit.yDy, 1f)
        assertEquals(540f, fit.x0, 0.5f)
        assertTrue(fit.rmsErrorPx < 1f)

        val (expectedX, expectedY) = truth(0.05f, 0.02f)
        val mapped = fit.map(0.05f, 0.02f)
        assertEquals(expectedX, mapped.x, 1f)
        assertEquals(expectedY, mapped.y, 1f)
    }

    @Test
    fun affineFitRejectsDegenerateSamples() {
        // dy is a multiple of dx: the vertical axis cannot be separated from the horizontal one.
        val collinear = listOf(-0.1f, -0.05f, 0f, 0.05f, 0.1f).map { CalibrationSample(it, it * 2, 500f, 500f) }
        assertNull(AffineSolver.fit(collinear, width, height))
        val constant = List(5) { CalibrationSample(0.1f, 0.1f, 100f * it, 100f * it) }
        assertNull(AffineSolver.fit(constant, width, height))
    }

    @Test
    fun mappedPointIsClampedToScreen() {
        val calibration = AffineCalibration(540f, 4000f, 0f, 1170f, 0f, 9000f, width, height, 0f)
        val point = calibration.map(5f, -5f)
        assertEquals(width.toFloat(), point.x, 0f)
        assertEquals(0f, point.y, 0f)
    }

    /** Runs a full calibration with a simulated user whose eyes follow each target exactly. */
    private fun runCalibration(manager: CalibrationManager, eyes: (Float, Float) -> GazeSample?): CalibrationStep {
        var now = 1_000L
        var step: CalibrationStep = manager.begin(width, height, now)
        while (step is CalibrationStep.InProgress && now < 60_000) {
            now += 33
            val current = step
            step = manager.onSample(eyes(current.targetX, current.targetY), now)!!
        }
        return step
    }

    @Test
    fun calibrationFlowFitsAndPersists() {
        val store = MemoryStore()
        val manager = CalibrationManager(store)
        var visited = 0
        var lastTarget = -1f to -1f
        val result = runCalibration(manager) { targetX, targetY ->
            if (targetX to targetY != lastTarget) {
                lastTarget = targetX to targetY
                visited++
            }
            // Inverse of X = 540 + 4000*dx, Y = 1170 + 9000*dy.
            sample((targetX - 540f) / 4000f, (targetY - 1170f) / 9000f)
        }
        assertTrue(result is CalibrationStep.Completed)
        assertEquals(5, visited)
        assertNotNull(store.saved)

        val point = manager.mapToScreen(0.05f, 0.05f)!!
        assertEquals(740f, point.x, 2f)
        assertEquals(1620f, point.y, 2f)

        // A fresh manager restores it only for the same screen size.
        assertTrue(CalibrationManager(store).restoreSaved(width, height))
        assertFalse(CalibrationManager(store).restoreSaved(height, width))
    }

    @Test
    fun calibrationNeverFailsWhenEyesDoNotMove() {
        val store = MemoryStore()
        val manager = CalibrationManager(store)
        val result = runCalibration(manager) { _, _ -> sample(0.01f, 0.01f) }
        val calibration = (result as CalibrationStep.Completed).calibration
        assertEquals(CalibrationQuality.DEFAULT, calibration.quality)
        // The fixed gaze is taken as the screen centre, and a default is never persisted.
        val point = manager.mapToScreen(0.01f, 0.01f)!!
        assertEquals(width / 2f, point.x, 1f)
        assertEquals(height / 2f, point.y, 1f)
        assertNull(store.saved)
    }

    @Test
    fun calibrationFinishesEvenIfEyesAreNeverReadable() {
        // A face is in view the whole time, but the eyes always read as closed.
        val manager = CalibrationManager(MemoryStore())
        var now = 0L
        var step: CalibrationStep = manager.begin(width, height, now)
        while (step is CalibrationStep.InProgress && now < 120_000) {
            now += 33
            step = manager.onSample(sample(0f, 0f, closed = true), now)!!
        }
        assertTrue(step is CalibrationStep.Completed)
        assertTrue("took $now ms", now <= 5 * 8_000 + 500)
        assertNotNull(manager.mapToScreen(0f, 0f))
    }

    @Test
    fun noisyCalibrationFallsBackToAxisAlignedFit() {
        // True mapping X = 540 - 5000*dx, Y = 1170 + 7000*dy. The user glanced away during the
        // centre point, which no affine map can reconcile with the four corners; the corners
        // themselves are only mildly noisy.
        val noise = listOf(0.10f to 0.15f, 0.01f to 0.01f, -0.01f to -0.01f, -0.01f to 0.01f, 0.01f to -0.01f)
        val samples = CalibrationManager.TARGETS.mapIndexed { i, (fx, fy) ->
            val sx = fx * width
            val sy = fy * height
            CalibrationSample((540f - sx) / 5000f + noise[i].first, (sy - 1170f) / 7000f + noise[i].second, sx, sy)
        }
        val result = AffineSolver.solve(samples, width, height)
        assertEquals(CalibrationQuality.APPROXIMATE, result.quality)
        assertEquals(0f, result.xDy, 0f)
        assertEquals(0f, result.yDx, 0f)
        // Directions are right: more dx is further left, more dy is further down.
        assertTrue(result.xDx < 0f)
        assertTrue(result.yDy > 0f)
        val centre = result.map(0f, 0f)
        assertEquals(540f, centre.x, 120f)
        assertEquals(1170f, centre.y, 250f)
    }

    @Test
    fun solvePrefersTheAffineFitWhenItIsGood() {
        val samples = CalibrationManager.TARGETS.map { (fx, fy) ->
            val sx = fx * width
            val sy = fy * height
            CalibrationSample((540f - sx) / 5000f, (sy - 1170f) / 7000f, sx, sy)
        }
        val result = AffineSolver.solve(samples, width, height)
        assertEquals(CalibrationQuality.FITTED, result.quality)
        assertEquals(-5000f, result.xDx, 5f)
        assertEquals(7000f, result.yDy, 5f)
    }

    @Test
    fun axisAlignedFitUsesDefaultsOnlyForTheAxisItCannotMeasure() {
        // Horizontal eye movement was measured; vertical was not (dy is constant).
        val samples = CalibrationManager.TARGETS.map { (fx, fy) ->
            CalibrationSample((540f - fx * width) / 5000f, 0.02f, fx * width, fy * height)
        }
        val result = AffineSolver.solve(samples, width, height)
        assertEquals(CalibrationQuality.APPROXIMATE, result.quality)
        assertEquals(-5000f, result.xDx, 5f)
        val default = AffineSolver.defaultCalibration(width, height, 0f, 0.02f)
        assertEquals(default.yDy, result.yDy, 0f)
        assertEquals(height / 2f, result.map(0f, 0.02f).y, 1f)
    }

    @Test
    fun defaultCalibrationFollowsTheUnmirroredCameraSigns() {
        val default = AffineSolver.defaultCalibration(width, height, 0f, 0f)
        val centre = default.map(0f, 0f)
        assertEquals(width / 2f, centre.x, 0.5f)
        assertEquals(height / 2f, centre.y, 0.5f)
        // Iris further right in the image (dx up) = looking towards the left of the screen.
        assertTrue(default.map(0.02f, 0f).x < centre.x)
        // Iris lower in the image (dy up) = looking further down the screen.
        assertTrue(default.map(0f, 0.02f).y > centre.y)
        assertTrue(AffineSolver.solve(emptyList(), width, height).quality == CalibrationQuality.DEFAULT)
    }

    @Test
    fun skippingCalibrationCentresOnTheCurrentGaze() {
        val store = MemoryStore()
        val manager = CalibrationManager(store)
        val default = manager.useDefault(width, height, sample(0.03f, -0.01f))
        assertEquals(CalibrationQuality.DEFAULT, default.quality)
        val point = manager.mapToScreen(0.03f, -0.01f)!!
        assertEquals(width / 2f, point.x, 1f)
        assertEquals(height / 2f, point.y, 1f)
        assertNull(store.saved)
        // Works with no face in view too.
        assertNotNull(CalibrationManager(store).useDefault(width, height, null))
    }

    @Test
    fun lookingDownDoesNotCountAsEyesClosed() {
        // Lids narrow to roughly half their open gap when looking at the bottom of the screen.
        val result = GazeEstimator().estimate(face(irisDx = 0f, irisDy = 0.08f, lidGap = 0.13f))!!
        assertFalse(result.eyesClosed)
    }

    @Test
    fun defaultFilterKeepsUpWithSmallEyeMovements() {
        // A glance across half a phone screen is only about 0.04 of dx.
        val estimator = GazeEstimator()
        var t = 0L
        repeat(30) {
            estimator.estimate(face(irisDx = 0f, irisDy = 0f, timestampMs = t))
            t += 33
        }
        var out = 0f
        repeat(8) {
            out = estimator.estimate(face(irisDx = 0.04f, irisDy = 0f, timestampMs = t))!!.dx
            t += 33
        }
        assertTrue("after ~260 ms the filter reached $out of 0.04", out > 0.032f)
    }

    @Test
    fun calibrationWaitsWhileEyesAreNotVisible() {
        val manager = CalibrationManager(MemoryStore())
        var now = 0L
        manager.begin(width, height, now)
        // Ten seconds without a face: still on the first point.
        var step: CalibrationStep? = null
        repeat(300) {
            now += 33
            step = manager.onSample(null, now)
        }
        assertEquals(0, (step as CalibrationStep.InProgress).pointIndex)
    }
}
