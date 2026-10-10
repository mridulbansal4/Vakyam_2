package com.demo.opencv.vision

/**
 * Turns the per-frame gaze classification into deliberate selections.
 *
 * A gaze only becomes a selection when it is held for a minimum time, so natural blinks, glances
 * and the eye passing through another direction on its way back to centre are never entered:
 * - Time based, not frame based: the camera runs anywhere from 10 to 30 fps (thermal governor,
 *   low light), so a fixed frame count would mean a different hold time on every device.
 * - One contradicting or unreadable frame does not restart the hold; two in a row do, and so does
 *   a classification that keeps flickering (more than one contradicting frame in five).
 * - After a selection the same gaze cannot fire again until the user has looked away, so a
 *   flickering classification cannot enter the same option twice.
 * - Closed eyes need a longer hold than a direction, well above the length of a natural blink.
 */
class GazeDwellSelector @JvmOverloads constructor(
    private val dwellMs: Long = 300L,
    private val closedDwellMs: Long = 450L, // natural blinks last 100-400 ms
    private val minFrames: Int = 3,
    private val dropoutMs: Long = 250L,     // longest gap the held gaze may go unseen
    private val lockoutMs: Long = 300L,     // return saccade and screen refresh after a selection
    private val rearmNeutralFrames: Int = 3,
    private val rearmAbsenceMs: Long = 600L // re-arm without a straight reading (e.g. not calibrated)
) {

    companion object {
        const val UNRELIABLE = -1 // no trustworthy classification for this frame
        const val STRAIGHT = 0
        const val CLOSED = 5
    }

    private var candidate = UNRELIABLE
    private var candidateStartMs = 0L
    private var candidateSeenMs = 0L
    private var candidateFrames = 0
    private var contradictions = 0
    private var challenger = UNRELIABLE
    private var challengerStartMs = 0L

    // The gaze that fired last; it stays blocked until the user looks away from it
    private var blockedType = UNRELIABLE
    private var blockedSeenMs = 0L
    private var neutralFrames = 0

    private var lastFireMs = -1L
    private var lastFrameMs = -1L

    fun reset() {
        cancel()
        blockedType = UNRELIABLE
        blockedSeenMs = 0L
        neutralFrames = 0
        lastFireMs = -1L
        lastFrameMs = -1L
    }

    /** Drops the gaze currently being held, e.g. when the phone is shaken. */
    fun cancel() {
        candidate = UNRELIABLE
        candidateFrames = 0
        contradictions = 0
        challenger = UNRELIABLE
    }

    /**
     * @param gazeType gaze tag of this frame (0 straight, 1 left, 2 right, 3 up, 5 closed, 6 left-up,
     *                 7 right-up) or [UNRELIABLE]
     * @return the selected gaze tag on the frame the hold completes, otherwise 0
     */
    fun update(gazeType: Int, nowMs: Long): Int {
        if (lastFrameMs >= 0L && nowMs < lastFrameMs) reset() // clock went backwards
        lastFrameMs = nowMs

        if (blockedType != UNRELIABLE) {
            if (gazeType == blockedType) {
                blockedSeenMs = nowMs
                neutralFrames = 0
            } else {
                if (gazeType == STRAIGHT) neutralFrames++ else if (gazeType != UNRELIABLE) neutralFrames = 0
                if (neutralFrames >= rearmNeutralFrames || nowMs - blockedSeenMs >= rearmAbsenceMs) {
                    blockedType = UNRELIABLE
                }
            }
        }

        if (lastFireMs >= 0L && nowMs - lastFireMs < lockoutMs) {
            cancel()
            return 0
        }

        if (candidate != UNRELIABLE && nowMs - candidateSeenMs > dropoutMs) cancel()
        if (gazeType == UNRELIABLE) return 0

        when (gazeType) {
            candidate -> {
                candidateFrames++
                candidateSeenMs = nowMs
                challenger = UNRELIABLE
            }
            challenger -> startCandidate(gazeType, challengerStartMs, nowMs, 2)
            else -> {
                if (candidate != UNRELIABLE) contradictions++
                if (candidate == UNRELIABLE || contradictions * 4 > candidateFrames) {
                    startCandidate(gazeType, nowMs, nowMs, 1)
                } else {
                    challenger = gazeType
                    challengerStartMs = nowMs
                }
            }
        }

        if (gazeType != candidate || candidate == STRAIGHT || candidate == blockedType) return 0
        val requiredMs = if (candidate == CLOSED) closedDwellMs else dwellMs
        if (candidateFrames < minFrames || nowMs - candidateStartMs < requiredMs) return 0

        val selected = candidate
        lastFireMs = nowMs
        blockedType = selected
        blockedSeenMs = nowMs
        neutralFrames = 0
        cancel()
        return selected
    }

    private fun startCandidate(gazeType: Int, startMs: Long, nowMs: Long, frames: Int) {
        candidate = gazeType
        candidateStartMs = startMs
        candidateSeenMs = nowMs
        candidateFrames = frames
        contradictions = 0
        challenger = UNRELIABLE
    }
}
