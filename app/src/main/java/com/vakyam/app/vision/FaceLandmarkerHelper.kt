package com.vakyam.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the MediaPipe [FaceLandmarker] in `LIVE_STREAM` mode.
 *
 * Frames go in through [detectAsync]; results come back on a MediaPipe worker thread through
 * [Listener], never on the main thread.
 */
class FaceLandmarkerHelper(
    context: Context,
    private val listener: Listener,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    interface Listener {
        /** A face was found in the frame stamped [FaceLandmarks.timestampMs]. */
        fun onFace(face: FaceLandmarks)

        /** The frame stamped [timestampMs] contained no face. */
        fun onNoFace(timestampMs: Long)

        fun onError(message: String)
    }

    private val appContext = context.applicationContext
    private val lock = Any()
    private var faceLandmarker: FaceLandmarker? = null
    private var lastTimestampMs = Long.MIN_VALUE

    val isReady: Boolean get() = synchronized(lock) { faceLandmarker != null }

    /**
     * Loads `face_landmarker.task` from assets and creates the graph. Safe to call repeatedly.
     *
     * @return true when the landmarker is ready; on failure [Listener.onError] has been called.
     */
    suspend fun initialize(): Boolean = withContext(defaultDispatcher) {
        synchronized(lock) {
            if (faceLandmarker != null) return@withContext true
            try {
                val baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    // CPU keeps the GPU free for the LLM and avoids vendor delegate issues; the
                    // landmarker runs well under one frame interval on XNNPACK.
                    .setDelegate(Delegate.CPU)
                    .build()
                val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumFaces(1)
                    .setMinFaceDetectionConfidence(MIN_CONFIDENCE)
                    .setMinFacePresenceConfidence(MIN_CONFIDENCE)
                    .setMinTrackingConfidence(MIN_CONFIDENCE)
                    .setOutputFaceBlendshapes(false)
                    .setOutputFacialTransformationMatrixes(false)
                    .setResultListener(::handleResult)
                    .setErrorListener(::handleError)
                    .build()
                faceLandmarker = FaceLandmarker.createFromOptions(appContext, options)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Face landmarker failed to initialise", e)
                listener.onError(
                    "Could not load the face tracking model ($MODEL_ASSET). " +
                        "Make sure it is bundled in app/src/main/assets. (${e.message})",
                )
                false
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "MediaPipe native library missing", e)
                listener.onError("Face tracking is not supported on this device ABI.")
                false
            }
        }
    }

    /**
     * Queues [uprightBitmap] for detection. Must be called off the main thread. Frames are dropped
     * silently until [initialize] has completed.
     *
     * [timestampMs] must come from a monotonic clock; MediaPipe rejects non-increasing timestamps,
     * so a duplicate or older one is skipped rather than passed on.
     */
    fun detectAsync(uprightBitmap: Bitmap, timestampMs: Long) {
        synchronized(lock) {
            val landmarker = faceLandmarker ?: return
            if (timestampMs <= lastTimestampMs) return
            lastTimestampMs = timestampMs
            try {
                val mpImage = BitmapImageBuilder(uprightBitmap).build()
                landmarker.detectAsync(mpImage, timestampMs)
            } catch (e: RuntimeException) {
                Log.w(TAG, "detectAsync failed for frame $timestampMs", e)
            }
        }
    }

    fun close() {
        synchronized(lock) {
            faceLandmarker?.close()
            faceLandmarker = null
        }
    }

    private fun handleResult(result: FaceLandmarkerResult, input: MPImage) {
        val timestampMs = result.timestampMs()
        val faces = result.faceLandmarks()
        if (faces.isEmpty()) {
            listener.onNoFace(timestampMs)
            return
        }
        val landmarks = faces[0]
        val x = FloatArray(landmarks.size)
        val y = FloatArray(landmarks.size)
        for (i in landmarks.indices) {
            val landmark = landmarks[i]
            x[i] = landmark.x()
            y[i] = landmark.y()
        }
        listener.onFace(FaceLandmarks(x, y, input.width, input.height, timestampMs))
    }

    private fun handleError(error: RuntimeException) {
        Log.e(TAG, "Face landmarker error", error)
        listener.onError(error.message ?: "Unknown face tracking error")
    }

    companion object {
        private const val TAG = "FaceLandmarkerHelper"
        const val MODEL_ASSET = "face_landmarker.task"
        private const val MIN_CONFIDENCE = 0.5f
    }
}
