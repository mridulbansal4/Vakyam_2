package com.demo.opencv.features.blinkkeyboard

/**
 * Temporal blink discriminator. Feed it one eye state per camera frame; it classifies each
 * closure by how long the eyes stayed shut:
 *  - < involuntaryBlinkMaxMs              -> INVOLUNTARY (normal human blink, ignored)
 *  - < minBlinkDurationMs                 -> AMBIGUOUS (ignored)
 *  - minBlinkDurationMs..maxBlinkDurationMs -> SELECT
 *  - < restClosureMs                      -> TOO_LONG (ignored)
 *  - >= restClosureMs                     -> REST (fires onRestStarted while eyes are still closed)
 */
class BlinkDetector(
    private val config: BlinkConfig,
    private val listener: Listener,
) {
    enum class BlinkKind { INVOLUNTARY, AMBIGUOUS, SELECT, TOO_LONG, REST }

    interface Listener {
        fun onBlink(kind: BlinkKind, durationMs: Long)
        fun onClosureStarted()
        fun onRestStarted()
        /** Current closure length in ms; 0 when the eyes are open. */
        fun onClosureProgress(durationMs: Long)
    }

    private var closedSinceMs = -1L
    private var reopenedAtMs = -1L
    private var restFired = false

    val isEyeClosed: Boolean get() = closedSinceMs >= 0

    fun processEAR(ear: Float, nowMs: Long) = processEyeState(ear < config.earThreshold, nowMs)

    /**
     * A closure only ends once the eyes have stayed open for [BlinkConfig.reopenGraceMs]; its
     * length is measured up to the first open frame.
     */
    fun processEyeState(closed: Boolean, nowMs: Long) {
        if (closed) {
            reopenedAtMs = -1L
            if (closedSinceMs < 0) {
                closedSinceMs = nowMs
                restFired = false
                listener.onClosureStarted()
                listener.onClosureProgress(0L)
                return
            }
            val duration = nowMs - closedSinceMs
            listener.onClosureProgress(duration)
            if (!restFired && duration >= config.restClosureMs) {
                restFired = true
                listener.onRestStarted()
            }
        } else if (closedSinceMs >= 0) {
            if (reopenedAtMs < 0) reopenedAtMs = nowMs
            if (nowMs - reopenedAtMs < config.reopenGraceMs) return
            val duration = reopenedAtMs - closedSinceMs
            closedSinceMs = -1L
            reopenedAtMs = -1L
            listener.onClosureProgress(0L)
            listener.onBlink(classify(duration), duration)
        }
    }

    /** Drops an in-progress closure without classifying it (e.g. the face left the frame). */
    fun reset() {
        if (closedSinceMs >= 0) listener.onClosureProgress(0L)
        closedSinceMs = -1L
        reopenedAtMs = -1L
        restFired = false
    }

    fun classify(durationMs: Long): BlinkKind = when {
        durationMs < config.involuntaryBlinkMaxMs -> BlinkKind.INVOLUNTARY
        durationMs < config.minBlinkDurationMs -> BlinkKind.AMBIGUOUS
        durationMs <= config.maxBlinkDurationMs -> BlinkKind.SELECT
        durationMs < config.restClosureMs -> BlinkKind.TOO_LONG
        else -> BlinkKind.REST
    }
}
