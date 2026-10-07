package com.demo.opencv.vision

import kotlin.math.abs

/**
 * Ballistic Saccadic Gesture Detector for Mobile AAC.
 *
 * In transit (wheelchair or moving car), holding a steady dwell cursor on small screen buttons
 * is fatiguing and prone to accidental triggers caused by vehicle vibration.
 *
 * This detector identifies intentional, rapid eye movements (saccades):
 * - FLICK_RIGHT: Select / Next letter branch
 * - FLICK_LEFT: Delete / Back
 * - FLICK_UP: Expand sentence with Gemma / Speak
 * - BLINK_CONFIRM: Explicit confirmation / Pause
 *
 * Biomechanical justification:
 * Voluntary saccades exceed 300 deg/s in velocity and complete within 30-80 ms.
 * Road vibrations cause lower-amplitude oscillatory noise that never satisfies both
 * the velocity threshold and displacement boundary of a voluntary saccade.
 */
class BallisticGestureDetector @JvmOverloads constructor(
    private val velocityThreshold: Float = 1.8f,    // Normalized units / second
    private val displacementThreshold: Float = 0.18f, // Min displacement from baseline (0.5)
    private val lockoutDurationMs: Long = 400L       // Cooldown after gesture to prevent double-firing
) {

    enum class Gesture {
        NONE,
        FLICK_LEFT,
        FLICK_RIGHT,
        FLICK_UP,
        BLINK_CONFIRM
    }

    private var prevRatioX = 0.5f
    private var prevRatioY = 0.5f
    private var prevTimestampMs = -1L
    private var lastGestureTimeMs = -1L
    private var blinkStartMs = -1L

    // Decoupled horizontal and vertical ballistic velocity burst memory (valid within 150ms saccade initiation window)
    private var lastBurstVx = 0f
    private var lastBurstVxTimeMs = -1L
    private var lastBurstVy = 0f
    private var lastBurstVyTimeMs = -1L

    fun reset() {
        prevRatioX = 0.5f
        prevRatioY = 0.5f
        prevTimestampMs = -1L
        lastGestureTimeMs = -1L
        blinkStartMs = -1L
        lastBurstVx = 0f
        lastBurstVxTimeMs = -1L
        lastBurstVy = 0f
        lastBurstVyTimeMs = -1L
    }

    fun process(
        filteredRatioX: Float,
        filteredRatioY: Float,
        eyeOpenRatio: Float,
        timestampMs: Long
    ): Gesture {
        // Handle blink state (eye open ratio below threshold)
        if (eyeOpenRatio < 0.12f) {
            lastBurstVx = 0f
            lastBurstVxTimeMs = -1L
            lastBurstVy = 0f
            lastBurstVyTimeMs = -1L
            if (blinkStartMs < 0L) {
                blinkStartMs = timestampMs
            }
            prevRatioX = filteredRatioX
            prevRatioY = filteredRatioY
            prevTimestampMs = timestampMs
            // Saccades are physically impossible while eyelids are closed; prevent spurious triggers
            return Gesture.NONE
        } else {
            if (blinkStartMs >= 0L) {
                val blinkDuration = timestampMs - blinkStartMs
                blinkStartMs = -1L
                lastBurstVx = 0f
                lastBurstVxTimeMs = -1L
                lastBurstVy = 0f
                lastBurstVyTimeMs = -1L
                prevRatioX = filteredRatioX
                prevRatioY = filteredRatioY
                prevTimestampMs = timestampMs
                // Intentional deliberate blink: 250ms - 600ms (involuntary reflex blinks are ~100-150ms)
                if (blinkDuration in 250..600 && (lastGestureTimeMs < 0L || timestampMs - lastGestureTimeMs > lockoutDurationMs)) {
                    lastGestureTimeMs = timestampMs
                    return Gesture.BLINK_CONFIRM
                }
                return Gesture.NONE
            }
        }

        // Initialize baseline or reset if frame timestamp gap exceeds 200ms (GC pause / frame stall)
        if (prevTimestampMs < 0L || timestampMs <= prevTimestampMs || timestampMs - prevTimestampMs > 200L) {
            prevRatioX = filteredRatioX
            prevRatioY = filteredRatioY
            prevTimestampMs = timestampMs
            lastBurstVx = 0f
            lastBurstVxTimeMs = -1L
            lastBurstVy = 0f
            lastBurstVyTimeMs = -1L
            return Gesture.NONE
        }

        // Lockout check to prevent re-triggering during eye return-saccade
        if (lastGestureTimeMs >= 0L && timestampMs - lastGestureTimeMs < lockoutDurationMs) {
            prevRatioX = filteredRatioX
            prevRatioY = filteredRatioY
            prevTimestampMs = timestampMs
            lastBurstVx = 0f
            lastBurstVxTimeMs = -1L
            lastBurstVy = 0f
            lastBurstVyTimeMs = -1L
            return Gesture.NONE
        }

        val dt = ((timestampMs - prevTimestampMs) / 1000.0f).coerceIn(0.005f, 0.100f)

        val vx = (filteredRatioX - prevRatioX) / dt
        val vy = (filteredRatioY - prevRatioY) / dt

        val dxFromCenter = filteredRatioX - 0.5f
        val dyFromCenter = filteredRatioY - 0.5f

        prevRatioX = filteredRatioX
        prevRatioY = filteredRatioY
        prevTimestampMs = timestampMs

        // Decoupled velocity burst tracking across frame pacing fluctuations
        if (abs(vx) > velocityThreshold) {
            lastBurstVx = vx
            lastBurstVxTimeMs = timestampMs
        }
        if (vy < -velocityThreshold) {
            lastBurstVy = vy
            lastBurstVyTimeMs = timestampMs
        }

        val hasBurstX = lastBurstVxTimeMs > 0L && (timestampMs - lastBurstVxTimeMs) <= 150L
        val effectiveVx = if (hasBurstX) lastBurstVx else vx

        val hasBurstY = lastBurstVyTimeMs > 0L && (timestampMs - lastBurstVyTimeMs) <= 150L
        val effectiveVy = if (hasBurstY) lastBurstVy else vy

        // Detect high-velocity saccades exceeding displacement bounds
        return when {
            // Flick Right: high positive velocity and iris pushed past right margin
            effectiveVx > velocityThreshold && dxFromCenter > displacementThreshold -> {
                lastGestureTimeMs = timestampMs
                lastBurstVx = 0f
                lastBurstVxTimeMs = -1L
                lastBurstVy = 0f
                lastBurstVyTimeMs = -1L
                Gesture.FLICK_RIGHT
            }
            // Flick Left: high negative velocity and iris pushed past left margin
            effectiveVx < -velocityThreshold && dxFromCenter < -displacementThreshold -> {
                lastGestureTimeMs = timestampMs
                lastBurstVx = 0f
                lastBurstVxTimeMs = -1L
                lastBurstVy = 0f
                lastBurstVyTimeMs = -1L
                Gesture.FLICK_LEFT
            }
            // Flick Up: high negative vertical velocity (upward) and iris pushed past upper margin
            effectiveVy < -velocityThreshold && (dyFromCenter < -displacementThreshold || filteredRatioY < 0.28f) -> {
                lastGestureTimeMs = timestampMs
                lastBurstVx = 0f
                lastBurstVxTimeMs = -1L
                lastBurstVy = 0f
                lastBurstVyTimeMs = -1L
                Gesture.FLICK_UP
            }
            else -> Gesture.NONE
        }
    }
}
