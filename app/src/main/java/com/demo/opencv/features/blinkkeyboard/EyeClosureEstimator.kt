package com.demo.opencv.features.blinkkeyboard

import kotlin.math.max

/**
 * Pure: per-frame Eye Aspect Ratio and ML Kit eye-open probability (each the more-open of the two
 * eyes) → eyes closed or not.
 *
 * ML Kit's contour EAR for an open eye varies by face, so the closed-eye threshold adapts to the
 * user's open-eye baseline (never below [BlinkConfig.earThreshold]). The probability alone is not
 * reliable: a closed eye often scores above any fixed cut-off for a frame or two.
 */
class EyeClosureEstimator @JvmOverloads constructor(private val config: BlinkConfig = BlinkConfig()) {

    private var openEyeBaseline = 0f

    var closed = false
        private set

    /** EAR below which the eyes currently count as closed. */
    val threshold: Float
        get() = if (openEyeBaseline > 0f) max(config.earThreshold, openEyeBaseline * 0.6f) else config.earThreshold

    /** [ear] may be NaN when the contour is missing. */
    fun update(ear: Float, openProbability: Float): Boolean {
        if (!ear.isNaN() && openProbability > 0.8f) {
            openEyeBaseline = if (openEyeBaseline == 0f) ear else openEyeBaseline * 0.95f + ear * 0.05f
        }
        val threshold = threshold
        // Hysteresis: reopening needs a clearly open eye, so jitter around the threshold can't
        // split one deliberate closure into several short ones.
        val looksClosed = (!ear.isNaN() && ear < threshold) || openProbability < config.eyeOpenProbabilityThreshold
        val looksOpen = (ear.isNaN() || ear > threshold * REOPEN_EAR_FACTOR) &&
            openProbability > config.eyeOpenProbabilityThreshold + REOPEN_PROBABILITY_MARGIN
        closed = if (closed) !looksOpen else looksClosed
        return closed
    }

    /** The face left the frame: whatever comes back starts open. */
    fun onFaceLost() {
        closed = false
    }

    private companion object {
        const val REOPEN_EAR_FACTOR = 1.15f
        const val REOPEN_PROBABILITY_MARGIN = 0.15f
    }
}
