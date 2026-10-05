package com.vakyam.app.calibration

import android.content.Context
import androidx.core.content.edit
import com.vakyam.app.gaze.GazeSample
import com.vakyam.app.gaze.ScreenPoint

/** Persistence for the fitted calibration. */
interface CalibrationStore {
    fun load(): AffineCalibration?
    fun save(calibration: AffineCalibration)
    fun clear()
}

class SharedPrefsCalibrationStore(context: Context) : CalibrationStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): AffineCalibration? {
        if (!prefs.contains(KEY_X0)) return null
        return AffineCalibration(
            x0 = prefs.getFloat(KEY_X0, 0f),
            xDx = prefs.getFloat(KEY_X_DX, 0f),
            xDy = prefs.getFloat(KEY_X_DY, 0f),
            y0 = prefs.getFloat(KEY_Y0, 0f),
            yDx = prefs.getFloat(KEY_Y_DX, 0f),
            yDy = prefs.getFloat(KEY_Y_DY, 0f),
            screenWidth = prefs.getInt(KEY_SCREEN_W, 0),
            screenHeight = prefs.getInt(KEY_SCREEN_H, 0),
            rmsErrorPx = prefs.getFloat(KEY_RMS, 0f),
            // Calibrations saved before the quality field existed were all full fits.
            quality = CalibrationQuality.entries.firstOrNull { it.name == prefs.getString(KEY_QUALITY, null) }
                ?: CalibrationQuality.FITTED,
        )
    }

    override fun save(calibration: AffineCalibration) {
        prefs.edit {
            putFloat(KEY_X0, calibration.x0)
            putFloat(KEY_X_DX, calibration.xDx)
            putFloat(KEY_X_DY, calibration.xDy)
            putFloat(KEY_Y0, calibration.y0)
            putFloat(KEY_Y_DX, calibration.yDx)
            putFloat(KEY_Y_DY, calibration.yDy)
            putInt(KEY_SCREEN_W, calibration.screenWidth)
            putInt(KEY_SCREEN_H, calibration.screenHeight)
            putFloat(KEY_RMS, calibration.rmsErrorPx)
            putString(KEY_QUALITY, calibration.quality.name)
        }
    }

    override fun clear() {
        prefs.edit { clear() }
    }

    private companion object {
        const val PREFS_NAME = "vakyam_calibration"
        const val KEY_X0 = "x0"
        const val KEY_X_DX = "x_dx"
        const val KEY_X_DY = "x_dy"
        const val KEY_Y0 = "y0"
        const val KEY_Y_DX = "y_dx"
        const val KEY_Y_DY = "y_dy"
        const val KEY_SCREEN_W = "screen_w"
        const val KEY_SCREEN_H = "screen_h"
        const val KEY_RMS = "rms"
        const val KEY_QUALITY = "quality"
    }
}

enum class CalibrationPhase {
    /** The dot just moved; the eye is travelling to it. Samples are ignored. */
    SETTLING,

    /** The eye is assumed to be on the dot; samples are being recorded. */
    COLLECTING,
}

sealed interface CalibrationStep {
    /**
     * @property targetX target position in root-window pixels.
     * @property phaseProgress 0..1 progress through the current [phase].
     */
    data class InProgress(
        val pointIndex: Int,
        val pointCount: Int,
        val targetX: Float,
        val targetY: Float,
        val phase: CalibrationPhase,
        val phaseProgress: Float,
    ) : CalibrationStep

    /** The run is over. There is always a usable calibration; check its `quality`. */
    data class Completed(val calibration: AffineCalibration) : CalibrationStep
}

/**
 * Guided 5-point calibration (centre, then the four corners) and owner of the active
 * [AffineCalibration].
 *
 * The flow is driven entirely by [onSample], one call per camera frame, so it advances with the
 * vision pipeline. A run cannot fail and cannot get stuck: once a face is in view every point
 * finishes within [pointTimeoutMs], and whatever was measured is turned into the best calibration
 * the data supports (see [AffineSolver.solve]). It only waits while there is no face at all.
 *
 * Thread-safe: [begin] is called from the main thread, [onSample] and [mapToScreen] from the
 * vision thread.
 */
class CalibrationManager(
    private val store: CalibrationStore,
    private val settleMs: Long = 1000,
    private val collectMs: Long = 1200,
    private val minSamplesPerPoint: Int = 12,
    private val pointTimeoutMs: Long = 8000,
) {
    private var screenWidth = 0
    private var screenHeight = 0

    @Volatile
    var calibration: AffineCalibration? = null
        private set

    private var running = false
    private var pointIndex = 0
    private var phase = CalibrationPhase.SETTLING
    private var phaseStartMs = 0L
    private var lastValidMs = 0L
    private var lastFrameMs = 0L

    /** Time spent on the current point with a face in view, across restarts of the point. */
    private var pointFaceMs = 0L
    private val pointDx = ArrayList<Float>()
    private val pointDy = ArrayList<Float>()
    private val collected = ArrayList<CalibrationSample>()

    /**
     * Restores the persisted calibration if it was fitted for this exact screen size.
     *
     * @return true if a calibration is now active.
     */
    @Synchronized
    fun restoreSaved(screenWidth: Int, screenHeight: Int): Boolean {
        val saved = store.load()
        if (saved == null || saved.screenWidth != screenWidth || saved.screenHeight != screenHeight) {
            return false
        }
        this.screenWidth = screenWidth
        this.screenHeight = screenHeight
        calibration = saved
        return true
    }

    /**
     * Activates the built-in default calibration without running the 5-point flow, treating
     * [reference] (the most recent gaze sample, if any) as "looking at the middle of the screen".
     * It is not persisted, so it never replaces a saved calibration.
     */
    @Synchronized
    fun useDefault(screenWidth: Int, screenHeight: Int, reference: GazeSample?): AffineCalibration {
        require(screenWidth > 0 && screenHeight > 0) { "Screen size must be known before calibrating" }
        this.screenWidth = screenWidth
        this.screenHeight = screenHeight
        running = false
        val usable = reference?.takeUnless { it.eyesClosed }
        return AffineSolver.defaultCalibration(screenWidth, screenHeight, usable?.dx ?: 0f, usable?.dy ?: 0f)
            .also { calibration = it }
    }

    /** Starts a new calibration run for a screen of the given size (root-window pixels). */
    @Synchronized
    fun begin(screenWidth: Int, screenHeight: Int, nowMs: Long): CalibrationStep.InProgress {
        require(screenWidth > 0 && screenHeight > 0) { "Screen size must be known before calibrating" }
        this.screenWidth = screenWidth
        this.screenHeight = screenHeight
        running = true
        collected.clear()
        lastFrameMs = nowMs
        startPoint(0, nowMs)
        return progress(nowMs)
    }

    /** Aborts a run in progress; the previously active calibration (if any) stays in effect. */
    @Synchronized
    fun cancel() {
        running = false
    }

    /**
     * Feeds one frame into the running calibration.
     *
     * @param sample the frame's gaze sample, or null if no face was found.
     * @return the updated step, or null if no calibration is running.
     */
    @Synchronized
    fun onSample(sample: GazeSample?, nowMs: Long): CalibrationStep? {
        if (!running) return null

        // Only time with a face in view counts towards the timeout: an empty chair waits, a user
        // whose eyes cannot be read at this point is moved on.
        if (sample != null) pointFaceMs += (nowMs - lastFrameMs).coerceIn(0L, MAX_FRAME_GAP_MS)
        lastFrameMs = nowMs
        if (pointFaceMs >= pointTimeoutMs) {
            return completePoint(nowMs, requiredSamples = MIN_SAMPLES_ON_TIMEOUT)
        }

        val valid = sample != null && !sample.eyesClosed
        if (valid) {
            lastValidMs = nowMs
        } else if (nowMs - lastValidMs > TRACKING_LOST_MS) {
            // Eyes not visible: restart this point's timing once they are back, so a blink costs
            // nothing but looking away never records garbage.
            restartPoint(nowMs)
            return progress(nowMs)
        }

        when (phase) {
            CalibrationPhase.SETTLING -> if (nowMs - phaseStartMs >= settleMs) {
                phase = CalibrationPhase.COLLECTING
                phaseStartMs = nowMs
            }

            CalibrationPhase.COLLECTING -> {
                if (sample != null && valid) {
                    pointDx += sample.rawDx
                    pointDy += sample.rawDy
                }
                if (nowMs - phaseStartMs >= collectMs && pointDx.size >= minSamplesPerPoint) {
                    return completePoint(nowMs, requiredSamples = minSamplesPerPoint)
                }
            }
        }
        return progress(nowMs)
    }

    /** Maps filtered gaze offsets to the screen, or null if there is no calibration yet. */
    fun mapToScreen(dx: Float, dy: Float): ScreenPoint? = calibration?.map(dx, dy)

    /**
     * Closes the current point, recording its observation if it has at least [requiredSamples]
     * samples (a timed-out point with too few is simply left out of the fit), then moves to the
     * next point or finishes the run.
     */
    private fun completePoint(nowMs: Long, requiredSamples: Int): CalibrationStep {
        if (pointDx.size >= requiredSamples) {
            val target = TARGETS[pointIndex]
            // The median ignores the stray samples of a mid-point blink or glance.
            collected += CalibrationSample(
                dx = median(pointDx),
                dy = median(pointDy),
                screenX = target.first * screenWidth,
                screenY = target.second * screenHeight,
            )
        }
        if (pointIndex == TARGETS.lastIndex) return finish()
        startPoint(pointIndex + 1, nowMs)
        return progress(nowMs)
    }

    private fun startPoint(index: Int, nowMs: Long) {
        pointIndex = index
        pointFaceMs = 0L
        restartPoint(nowMs)
    }

    private fun restartPoint(nowMs: Long) {
        phase = CalibrationPhase.SETTLING
        phaseStartMs = nowMs
        lastValidMs = nowMs
        pointDx.clear()
        pointDy.clear()
    }

    private fun progress(nowMs: Long): CalibrationStep.InProgress {
        val target = TARGETS[pointIndex]
        val duration = if (phase == CalibrationPhase.SETTLING) settleMs else collectMs
        return CalibrationStep.InProgress(
            pointIndex = pointIndex,
            pointCount = TARGETS.size,
            targetX = target.first * screenWidth,
            targetY = target.second * screenHeight,
            phase = phase,
            phaseProgress = ((nowMs - phaseStartMs).toFloat() / duration).coerceIn(0f, 1f),
        )
    }

    private fun finish(): CalibrationStep {
        running = false
        val result = AffineSolver.solve(collected, screenWidth, screenHeight)
        calibration = result
        // A pure default says nothing about this user, so it must not overwrite a real
        // calibration saved earlier.
        if (result.quality != CalibrationQuality.DEFAULT) store.save(result)
        return CalibrationStep.Completed(result)
    }

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2f
    }

    companion object {
        /** Targets as fractions of the screen: centre, top-left, top-right, bottom-left, bottom-right. */
        val TARGETS: List<Pair<Float, Float>> = listOf(
            0.5f to 0.5f,
            0.12f to 0.10f,
            0.88f to 0.10f,
            0.12f to 0.90f,
            0.88f to 0.90f,
        )

        private const val TRACKING_LOST_MS = 400L
        private const val MAX_FRAME_GAP_MS = 100L
        private const val MIN_SAMPLES_ON_TIMEOUT = 3
    }
}
