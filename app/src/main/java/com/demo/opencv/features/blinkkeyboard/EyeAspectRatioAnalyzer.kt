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

/**
 * CameraX analyzer that runs ML Kit face contours on each frame and reports the Eye Aspect Ratio
 * (the more-open of the two eyes). Results are delivered on the main thread. Open or closed is
 * decided by [EyeClosureEstimator].
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

    private val closure = EyeClosureEstimator(config)

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

        val eyesClosed = closure.update(ear, openProb)
        return EyeSample(true, ear, closure.threshold, openProb, eyesClosed, timestamp)
    }

    private fun noFace(timestamp: Long): EyeSample {
        closure.onFaceLost()
        return EyeSample(false, Float.NaN, config.earThreshold, 1f, false, timestamp)
    }

    companion object {
        /**
         * EAR = (|p2 - p6| + |p3 - p5|) / (2 |p1 - p4|) over ML Kit's 16-point eye contour, which
         * starts at one corner (index 0), runs along the upper lid to the other corner (index 8)
         * and back along the lower lid, so the lower-lid point under upper index k is 16 - k.
         */
        @JvmStatic
        fun computeEAR(points: List<PointF>): Float {
            if (points.size < 16) return Float.NaN
            val width = dist(points[0], points[8])
            if (width <= 0f) return Float.NaN
            return (dist(points[3], points[13]) + dist(points[5], points[11])) / (2f * width)
        }

        private fun dist(a: PointF, b: PointF) = hypot(a.x - b.x, a.y - b.y)
    }
}
