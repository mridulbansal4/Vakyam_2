package com.vakyam.app.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

/**
 * Front-camera frame source built on CameraX `ImageAnalysis`.
 *
 * Only an analysis use case is bound (no preview surface), which keeps the camera pipeline's
 * buffer footprint minimal. Each frame is converted to an upright ARGB bitmap and handed to
 * [onFrame] on a background executor; the [ImageProxy] is always closed afterwards.
 */
class CameraSource(
    context: Context,
    private val onFrame: (uprightBitmap: Bitmap, timestampMs: Long) -> Unit,
    private val onError: (message: String) -> Unit,
) {

    private val appContext = context.applicationContext

    // One frame at a time, off the main thread. KEEP_ONLY_LATEST drops frames while this is busy.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val analysisExecutor = Dispatchers.Default.limitedParallelism(1).asExecutor()

    private var cameraProvider: ProcessCameraProvider? = null
    private var packedBuffer: Bitmap? = null
    private val rotationMatrix = Matrix()

    /**
     * Binds the front camera to [lifecycleOwner]; CameraX then opens and closes the camera with
     * that lifecycle. Requires the CAMERA permission. Must be called on the main thread.
     */
    fun start(lifecycleOwner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(appContext)
        future.addListener(
            {
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    bind(provider, lifecycleOwner)
                } catch (e: Exception) {
                    Log.e(TAG, "Camera start failed", e)
                    onError("Could not start the front camera: ${e.message}")
                }
            },
            ContextCompat.getMainExecutor(appContext),
        )
    }

    /** Releases the camera. Must be called on the main thread. */
    fun stop() {
        cameraProvider?.unbindAll()
    }

    private fun bind(provider: ProcessCameraProvider, lifecycleOwner: LifecycleOwner) {
        if (!provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            onError("This device has no front camera.")
            return
        }
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    ANALYSIS_SIZE,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
        analysis.setAnalyzer(analysisExecutor, ::analyze)

        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
    }

    private fun analyze(image: ImageProxy) {
        try {
            val timestampMs = SystemClock.uptimeMillis()
            val upright = toUprightBitmap(image)
            onFrame(upright, timestampMs)
        } catch (e: Exception) {
            // A single bad frame must never stall the stream; log and move on to the next one.
            Log.w(TAG, "Dropped a frame", e)
        } finally {
            // Without this CameraX stops delivering frames and the pipeline freezes.
            image.close()
        }
    }

    /**
     * Copies the RGBA frame into a bitmap rotated by the sensor-to-display offset, so the face is
     * upright for the landmarker regardless of how the sensor is mounted (270° for the front
     * camera of a portrait phone). The image is deliberately not mirrored: the calibration fit
     * absorbs the left/right sign, and unmirrored landmarks match MediaPipe's documented indices.
     */
    private fun toUprightBitmap(image: ImageProxy): Bitmap {
        val plane = image.planes[0]
        val source: Bitmap = if (plane.rowStride == image.width * plane.pixelStride) {
            val buffer = packedBuffer?.takeIf { it.width == image.width && it.height == image.height }
                ?: Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                    .also { packedBuffer = it }
            plane.buffer.rewind()
            buffer.copyPixelsFromBuffer(plane.buffer)
            buffer
        } else {
            // Rows are padded; let CameraX do the stride-aware copy.
            image.toBitmap()
        }
        rotationMatrix.reset()
        rotationMatrix.postRotate(image.imageInfo.rotationDegrees.toFloat())
        return Bitmap.createBitmap(source, 0, 0, image.width, image.height, rotationMatrix, false)
    }

    companion object {
        private const val TAG = "CameraSource"

        // The landmarker crops the face and resizes it to its own small input, so VGA is enough at
        // phone-holding distance and keeps per-frame bitmap churn low.
        private val ANALYSIS_SIZE = Size(640, 480)
    }
}
