package com.demo.opencv.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * End-to-end integration test validating the interaction between:
 * 1. NormalizedIrisEstimator (scale-invariant landmark projection)
 * 2. OneEuroFilter (jitter smoothing with zero saccade lag)
 * 3. BallisticGestureDetector (velocity + displacement thresholding)
 * 4. MotionSensorStabilizer (shock circuit breaker)
 * 5. Gesture code mapping: 1=Left, 2=Right, 3=Up, 5=Blink Confirm
 */
class TransitRobustGazeIntegrationTest {

    private lateinit var estimator: NormalizedIrisEstimator
    private lateinit var filterX: OneEuroFilter
    private lateinit var filterY: OneEuroFilter
    private lateinit var detector: BallisticGestureDetector
    private lateinit var jerkProcessor: MotionSensorStabilizer.JerkProcessor

    @Before
    fun setUp() {
        estimator = NormalizedIrisEstimator()
        filterX = OneEuroFilter(1.0, 0.5, 1.0)
        filterY = OneEuroFilter(1.0, 0.5, 1.0)
        detector = BallisticGestureDetector(1.8f, 0.18f, 300L)
        jerkProcessor = MotionSensorStabilizer.JerkProcessor(4.5f, 300L)
    }

    private fun mapGestureToCode(gesture: BallisticGestureDetector.Gesture): Int {
        return when (gesture) {
            BallisticGestureDetector.Gesture.FLICK_LEFT -> 1
            BallisticGestureDetector.Gesture.FLICK_RIGHT -> 2
            BallisticGestureDetector.Gesture.FLICK_UP -> 3
            BallisticGestureDetector.Gesture.BLINK_CONFIRM -> 5
            BallisticGestureDetector.Gesture.NONE -> 0
        }
    }

    @Test
    fun testFullPipelineRightFlickGestureMapping() {
        var t = 0L

        // Resting at center for several frames
        val centerLandmarks = NormalizedIrisEstimator.EyeLandmarks(
            innerCanthus = NormalizedIrisEstimator.Point2D(200f, 100f),
            outerCanthus = NormalizedIrisEstimator.Point2D(100f, 100f),
            upperLid = NormalizedIrisEstimator.Point2D(150f, 85f),
            lowerLid = NormalizedIrisEstimator.Point2D(150f, 115f),
            irisCenter = NormalizedIrisEstimator.Point2D(150f, 100f)
        )

        for (i in 0..5) {
            t += 33L
            val m = estimator.computeMetrics(centerLandmarks)
            val sX = filterX.filter(m.horizontalRatio.toDouble(), t)
            val sY = filterY.filter(m.verticalRatio.toDouble(), t)
            val g = detector.process(sX.toFloat(), sY.toFloat(), m.eyeOpenRatio, t)
            assertEquals(0, mapGestureToCode(g))
        }

        // Fast right saccade (looking right moves iris toward outerCanthus=100 in front camera)
        val rightLandmarks = NormalizedIrisEstimator.EyeLandmarks(
            innerCanthus = NormalizedIrisEstimator.Point2D(200f, 100f),
            outerCanthus = NormalizedIrisEstimator.Point2D(100f, 100f),
            upperLid = NormalizedIrisEstimator.Point2D(150f, 85f),
            lowerLid = NormalizedIrisEstimator.Point2D(150f, 115f),
            irisCenter = NormalizedIrisEstimator.Point2D(115f, 100f) // (200-115)/100 = 0.85
        )

        var detectedGestureCode = 0
        for (i in 0..2) {
            t += 33L
            val m = estimator.computeMetrics(rightLandmarks)
            val sX = filterX.filter(m.horizontalRatio.toDouble(), t)
            val sY = filterY.filter(m.verticalRatio.toDouble(), t)
            val g = detector.process(sX.toFloat(), sY.toFloat(), m.eyeOpenRatio, t)
            val code = mapGestureToCode(g)
            if (code != 0) {
                detectedGestureCode = code
            }
        }

        assertEquals("Right flick should map to gesture code 2", 2, detectedGestureCode)
    }

    @Test
    fun testFullPipelineLeftFlickGestureMapping() {
        var t = 0L

        // Resting at center
        val centerLandmarks = NormalizedIrisEstimator.EyeLandmarks(
            innerCanthus = NormalizedIrisEstimator.Point2D(200f, 100f),
            outerCanthus = NormalizedIrisEstimator.Point2D(100f, 100f),
            upperLid = NormalizedIrisEstimator.Point2D(150f, 85f),
            lowerLid = NormalizedIrisEstimator.Point2D(150f, 115f),
            irisCenter = NormalizedIrisEstimator.Point2D(150f, 100f)
        )

        for (i in 0..5) {
            t += 33L
            val m = estimator.computeMetrics(centerLandmarks)
            val sX = filterX.filter(m.horizontalRatio.toDouble(), t)
            val sY = filterY.filter(m.verticalRatio.toDouble(), t)
            detector.process(sX.toFloat(), sY.toFloat(), m.eyeOpenRatio, t)
        }

        // Fast left saccade (looking left moves iris toward innerCanthus=200)
        val leftLandmarks = NormalizedIrisEstimator.EyeLandmarks(
            innerCanthus = NormalizedIrisEstimator.Point2D(200f, 100f),
            outerCanthus = NormalizedIrisEstimator.Point2D(100f, 100f),
            upperLid = NormalizedIrisEstimator.Point2D(150f, 85f),
            lowerLid = NormalizedIrisEstimator.Point2D(150f, 115f),
            irisCenter = NormalizedIrisEstimator.Point2D(185f, 100f) // (200-185)/100 = 0.15
        )

        var detectedGestureCode = 0
        for (i in 0..2) {
            t += 33L
            val m = estimator.computeMetrics(leftLandmarks)
            val sX = filterX.filter(m.horizontalRatio.toDouble(), t)
            val sY = filterY.filter(m.verticalRatio.toDouble(), t)
            val g = detector.process(sX.toFloat(), sY.toFloat(), m.eyeOpenRatio, t)
            val code = mapGestureToCode(g)
            if (code != 0) {
                detectedGestureCode = code
            }
        }

        assertEquals("Left flick should map to gesture code 1", 1, detectedGestureCode)
    }

    @Test
    fun testFullPipelineUpFlickGestureMapping() {
        var t = 0L

        // Resting at center
        val centerLandmarks = NormalizedIrisEstimator.EyeLandmarks(
            innerCanthus = NormalizedIrisEstimator.Point2D(200f, 100f),
            outerCanthus = NormalizedIrisEstimator.Point2D(100f, 100f),
            upperLid = NormalizedIrisEstimator.Point2D(150f, 85f),
            lowerLid = NormalizedIrisEstimator.Point2D(150f, 115f),
            irisCenter = NormalizedIrisEstimator.Point2D(150f, 100f)
        )

        for (i in 0..5) {
            t += 33L
            val m = estimator.computeMetrics(centerLandmarks)
            val sX = filterX.filter(m.horizontalRatio.toDouble(), t)
            val sY = filterY.filter(m.verticalRatio.toDouble(), t)
            detector.process(sX.toFloat(), sY.toFloat(), m.eyeOpenRatio, t)
        }

        // Fast upward saccade (looking up moves iris toward upperLid=85)
        val upLandmarks = NormalizedIrisEstimator.EyeLandmarks(
            innerCanthus = NormalizedIrisEstimator.Point2D(200f, 100f),
            outerCanthus = NormalizedIrisEstimator.Point2D(100f, 100f),
            upperLid = NormalizedIrisEstimator.Point2D(150f, 85f),
            lowerLid = NormalizedIrisEstimator.Point2D(150f, 115f),
            irisCenter = NormalizedIrisEstimator.Point2D(150f, 88f) // (88-85)/30 = 0.10
        )

        var detectedGestureCode = 0
        for (i in 0..2) {
            t += 33L
            val m = estimator.computeMetrics(upLandmarks)
            val sX = filterX.filter(m.horizontalRatio.toDouble(), t)
            val sY = filterY.filter(m.verticalRatio.toDouble(), t)
            val g = detector.process(sX.toFloat(), sY.toFloat(), m.eyeOpenRatio, t)
            val code = mapGestureToCode(g)
            if (code != 0) {
                detectedGestureCode = code
            }
        }

        assertEquals("Up flick should map to gesture code 3", 3, detectedGestureCode)
    }

    @Test
    fun testFullPipelineBlinkConfirmGestureMapping() {
        var t = 1000L

        // Eyes open
        detector.process(0.50f, 0.50f, 0.30f, t)

        // Deliberate blink (closed for 300ms)
        t += 50L
        detector.process(0.50f, 0.50f, 0.05f, t)

        t += 300L
        detector.process(0.50f, 0.50f, 0.05f, t)

        // Eyes reopen
        t += 50L
        val g = detector.process(0.50f, 0.50f, 0.30f, t)

        assertEquals("Blink confirm should map to gesture code 5", 5, mapGestureToCode(g))
    }

    @Test
    fun testTransitShockSuppressesGestures() {
        var tNs = 1_000_000_000L
        var tMs = 1000L

        // Baseline steady motion
        assertTrue(jerkProcessor.update(0.0f, 0.0f, 9.8f, tNs, tMs))

        // Pothole shock detected by accelerometer jerk processor: 9 m/s^2 change in 20ms -> jerk = 450 m/s^3 >> 4.5
        tNs += 20_000_000L
        tMs += 20L
        val isStable = jerkProcessor.update(0.0f, 9.0f, 9.8f, tNs, tMs)
        assertFalse("High jerk should trigger shock circuit breaker", isStable)

        // Even if user's head bob caused a rapid gaze ratio change during the shock
        val g = detector.process(0.85f, 0.50f, 0.30f, tMs)
        val rawCode = mapGestureToCode(g)

        // Model layer suppression logic:
        val effectiveGesture = if (jerkProcessor.isStable(tMs)) rawCode else 0
        assertEquals("Gesture must be suppressed to 0 during vehicle shock window", 0, effectiveGesture)
    }

    @Test
    fun testBlinkConfirmFallbackWhenEyeContoursNullDuringClosure() {
        var t = 2000L

        // Subject open eyes (open probability = 1.0f -> ratio ~ 0.30f)
        var gesture = detector.process(0.50f, 0.50f, 0.30f, t)
        assertEquals(0, mapGestureToCode(gesture))

        // Eyes closed: ML Kit contours become null, but classification detects eye closure (openProb <= 0.15)
        // Model fallback path feeds ratio 0.05f to gesture detector
        t += 50L
        gesture = detector.process(0.50f, 0.50f, 0.05f, t)
        assertEquals(0, mapGestureToCode(gesture))

        t += 350L // Deliberate confirm blink (~400ms total closure)
        gesture = detector.process(0.50f, 0.50f, 0.05f, t)
        assertEquals(0, mapGestureToCode(gesture))

        // Eyes reopen: contours reappear, openRatio restores to 0.30f
        t += 50L
        gesture = detector.process(0.50f, 0.50f, 0.30f, t)
        assertEquals("Fallback blink path must trigger gesture code 5 upon eye reopening", 5, mapGestureToCode(gesture))
    }

    @Test
    fun testRightEyeFallbackTracksGazeEquallyAccurately() {
        var t = 0L

        // User's left eye is clipped by frame border, so Model falls back to right eye
        // For right eye in selfie camera: outerCanthus=minX (towards subject's right), innerCanthus=maxX (towards nose)
        val centerRightEye = NormalizedIrisEstimator.EyeLandmarks(
            innerCanthus = NormalizedIrisEstimator.Point2D(250f, 100f),
            outerCanthus = NormalizedIrisEstimator.Point2D(150f, 100f),
            upperLid = NormalizedIrisEstimator.Point2D(200f, 85f),
            lowerLid = NormalizedIrisEstimator.Point2D(200f, 115f),
            irisCenter = NormalizedIrisEstimator.Point2D(200f, 100f)
        )

        for (i in 0..5) {
            t += 33L
            val m = estimator.computeMetrics(centerRightEye)
            val sX = filterX.filter(m.horizontalRatio.toDouble(), t)
            val sY = filterY.filter(m.verticalRatio.toDouble(), t)
            detector.process(sX.toFloat(), sY.toFloat(), m.eyeOpenRatio, t)
        }

        // Subject flicks right -> iris shifts towards outer canthus (minX = 150f)
        val rightFlickLandmarks = NormalizedIrisEstimator.EyeLandmarks(
            innerCanthus = NormalizedIrisEstimator.Point2D(250f, 100f),
            outerCanthus = NormalizedIrisEstimator.Point2D(150f, 100f),
            upperLid = NormalizedIrisEstimator.Point2D(200f, 85f),
            lowerLid = NormalizedIrisEstimator.Point2D(200f, 115f),
            irisCenter = NormalizedIrisEstimator.Point2D(165f, 100f)
        )

        var detectedCode = 0
        for (i in 0..2) {
            t += 33L
            val m = estimator.computeMetrics(rightFlickLandmarks)
            val sX = filterX.filter(m.horizontalRatio.toDouble(), t)
            val sY = filterY.filter(m.verticalRatio.toDouble(), t)
            val g = detector.process(sX.toFloat(), sY.toFloat(), m.eyeOpenRatio, t)
            val code = mapGestureToCode(g)
            if (code != 0) detectedCode = code
        }

        assertEquals("Right flick with right-eye fallback should map to gesture code 2", 2, detectedCode)
    }
}
