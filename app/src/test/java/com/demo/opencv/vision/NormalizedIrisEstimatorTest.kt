package com.demo.opencv.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizedIrisEstimatorTest {

    private val estimator = NormalizedIrisEstimator()

    @Test
    fun testScaleInvarianceAtDifferentDistances() {
        // Base landmark points (e.g. at 30cm distance)
        val baseLandmarks = NormalizedIrisEstimator.EyeLandmarks(
            innerCanthus = NormalizedIrisEstimator.Point2D(100f, 100f),
            outerCanthus = NormalizedIrisEstimator.Point2D(200f, 100f),
            upperLid = NormalizedIrisEstimator.Point2D(150f, 80f),
            lowerLid = NormalizedIrisEstimator.Point2D(150f, 120f),
            irisCenter = NormalizedIrisEstimator.Point2D(170f, 100f) // Looking slightly right
        )

        // Scaled landmark points by 2.0x (simulating moving closer, e.g. at 15cm distance)
        val scale = 2.0f
        val scaledLandmarks = NormalizedIrisEstimator.EyeLandmarks(
            innerCanthus = NormalizedIrisEstimator.Point2D(baseLandmarks.innerCanthus.x * scale, baseLandmarks.innerCanthus.y * scale),
            outerCanthus = NormalizedIrisEstimator.Point2D(baseLandmarks.outerCanthus.x * scale, baseLandmarks.outerCanthus.y * scale),
            upperLid = NormalizedIrisEstimator.Point2D(baseLandmarks.upperLid.x * scale, baseLandmarks.upperLid.y * scale),
            lowerLid = NormalizedIrisEstimator.Point2D(baseLandmarks.lowerLid.x * scale, baseLandmarks.lowerLid.y * scale),
            irisCenter = NormalizedIrisEstimator.Point2D(baseLandmarks.irisCenter.x * scale, baseLandmarks.irisCenter.y * scale)
        )

        val baseMetrics = estimator.computeMetrics(baseLandmarks)
        val scaledMetrics = estimator.computeMetrics(scaledLandmarks)

        // Assert that horizontal and vertical ratios remain identical despite 2x camera distance change
        assertEquals(baseMetrics.horizontalRatio, scaledMetrics.horizontalRatio, 0.0001f)
        assertEquals(baseMetrics.verticalRatio, scaledMetrics.verticalRatio, 0.0001f)
        assertEquals(baseMetrics.eyeOpenRatio, scaledMetrics.eyeOpenRatio, 0.0001f)

        // Ratio should be 0.70 (since inner=100, outer=200, iris=170 -> (170-100)/(200-100) = 0.7)
        assertEquals(0.7f, baseMetrics.horizontalRatio, 0.001f)
        assertEquals(0.5f, baseMetrics.verticalRatio, 0.001f)
    }

    @Test
    fun testHeadTiltInvariance() {
        // Rotated eye corners (e.g. head tilted by 45 degrees)
        // dx = 100 * cos(45) = 70.71, dy = 100 * sin(45) = 70.71
        val tiltedLandmarks = NormalizedIrisEstimator.EyeLandmarks(
            innerCanthus = NormalizedIrisEstimator.Point2D(0f, 0f),
            outerCanthus = NormalizedIrisEstimator.Point2D(70.71f, 70.71f),
            upperLid = NormalizedIrisEstimator.Point2D(35.35f - 14.14f, 35.35f + 14.14f),
            lowerLid = NormalizedIrisEstimator.Point2D(35.35f + 14.14f, 35.35f - 14.14f),
            irisCenter = NormalizedIrisEstimator.Point2D(35.355f, 35.355f) // Exactly centered iris
        )

        val metrics = estimator.computeMetrics(tiltedLandmarks)
        // Centered iris should evaluate to 0.5 regardless of head tilt
        assertEquals(0.5f, metrics.horizontalRatio, 0.01f)
    }

    @Test
    fun testBlinkDetection() {
        val closedEyeLandmarks = NormalizedIrisEstimator.EyeLandmarks(
            innerCanthus = NormalizedIrisEstimator.Point2D(100f, 100f),
            outerCanthus = NormalizedIrisEstimator.Point2D(200f, 100f),
            upperLid = NormalizedIrisEstimator.Point2D(150f, 99f),
            lowerLid = NormalizedIrisEstimator.Point2D(150f, 101f), // Only 2 pixels open
            irisCenter = NormalizedIrisEstimator.Point2D(150f, 100f)
        )

        val metrics = estimator.computeMetrics(closedEyeLandmarks)
        assertTrue("Eye should be detected as closed/blink", metrics.eyeOpenRatio < 0.10f)
    }
}
