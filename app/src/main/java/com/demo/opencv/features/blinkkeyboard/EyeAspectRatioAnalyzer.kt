package com.demo.opencv.features.blinkkeyboard

import android.graphics.PointF
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.hypot
import kotlin.math.max

/**
 * CameraX analyzer that runs ML Kit face contours on each frame and reports the Eye Aspect Ratio
 * (the more-open of the two eyes). Results are delivered on the main thread.
 *
 * ML Kit's contour EAR for an open eye varies by face, so the closed-eye threshold adapts to the
 * user's open-eye baseline (never below [BlinkConfig.earThreshold]). ML Kit's eye-open probability
 * is fused in as a second closure signal.
 */
class EyeAspectRatioAnalyzer(
    private val config: BlinkConfig,
    private val onSample: (EyeSample) -> Unit,
) : ImageAnalysis.Analyzer {

    data class EyeSample(
        val faceFound: Boolean,
        val ear: Float,
        val earThreshold: Float,
        val eyeOpenProbability: Float,
        val eyesClosed: Boolean,
        val timestampMs: Long,
    )

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .build()
    )

    private var openEyeBaseline = 0f
    private var eyesClosed = false

    @OptIn(markerClass = [ExperimentalGetImage::class])
    override fun analyze(image: ImageProxy) {
        val mediaImage = image.image
        if (mediaImage == null) {
            image.close()
            return
        }
        val timestamp = SystemClock.elapsedRealtime()
        val input = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
        detector.process(input)
            .addOnSuccessListener { faces -> onSample(toSample(faces, timestamp)) }
            .addOnFailureListener { onSample(noFace(timestamp)) }
            .addOnCompleteListener { image.close() }
    }

    fun close() = detector.close()

    private fun toSample(faces: List<Face>, timestamp: Long): EyeSample {
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
            ?: return noFace(timestamp)

        val ears = listOf(FaceContour.LEFT_EYE, FaceContour.RIGHT_EYE)
            .mapNotNull { face.getContour(it)?.points }
            .map { computeEAR(it) }
            .filter { !it.isNaN() }
        // Taking the more-open eye means both eyes must close, which rejects one-eye tracking glitches.
        val ear = ears.maxOrNull() ?: Float.NaN

        val probs = listOfNotNull(face.leftEyeOpenProbability, face.rightEyeOpenProbability)
        val openProb = probs.maxOrNull() ?: 1f

        if (!ear.isNaN() && openProb > 0.8f) {
            openEyeBaseline = if (openEyeBaseline == 0f) ear else openEyeBaseline * 0.95f + ear * 0.05f
        }
        val threshold = if (openEyeBaseline > 0f) max(config.earThreshold, openEyeBaseline * 0.6f) else config.earThreshold

        // Hysteresis: reopening needs a clearly open eye, so jitter around the threshold can't
        // split one deliberate closure into several short ones.
        val looksClosed = (!ear.isNaN() && ear < threshold) || openProb < config.eyeOpenProbabilityThreshold
        val looksOpen = (ear.isNaN() || ear > threshold * REOPEN_EAR_FACTOR) &&
            openProb > config.eyeOpenProbabilityThreshold + REOPEN_PROBABILITY_MARGIN
        eyesClosed = if (eyesClosed) !looksOpen else looksClosed
        return EyeSample(true, ear, threshold, openProb, eyesClosed, timestamp)
    }

    private fun noFace(timestamp: Long): EyeSample {
        eyesClosed = false
        return EyeSample(false, Float.NaN, config.earThreshold, 1f, false, timestamp)
    }

    companion object {
        private const val REOPEN_EAR_FACTOR = 1.15f
        private const val REOPEN_PROBABILITY_MARGIN = 0.15f

        /**
         * EAR = (|p2 - p6| + |p3 - p5|) / (2 |p1 - p4|) over ML Kit's 16-point eye contour, which
         * starts at one corner (index 0), runs along the upper lid to the other corner (index 8)
         * and back along the lower lid, so the lower-lid point under upper index k is 16 - k.
         */
        fun computeEAR(points: List<PointF>): Float {
            if (points.size < 16) return Float.NaN
            val width = dist(points[0], points[8])
            if (width <= 0f) return Float.NaN
            return (dist(points[3], points[13]) + dist(points[5], points[11])) / (2f * width)
        }

        private fun dist(a: PointF, b: PointF) = hypot(a.x - b.x, a.y - b.y)
    }
}
