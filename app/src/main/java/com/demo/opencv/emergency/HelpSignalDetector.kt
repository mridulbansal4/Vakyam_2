package com.demo.opencv.emergency

/**
 * Pure: per-frame eye state → the call-for-help gesture, [requiredBlinks] long blinks in a row.
 *
 * It needs only "eyes open or closed", the one signal that still works when gaze direction is
 * unreliable (no calibration, glare, a hot phone running at a low frame rate). A long blink is
 * longer than every selection blink in the app (gaze confirm ends at 600 ms, the blink keyboard's
 * select at 1200 ms), so typing cannot raise the alarm, and a closure past [maxClosureMs] is
 * resting or sleep and is not counted.
 */
class HelpSignalDetector @JvmOverloads constructor(
    val requiredBlinks: Int = 3,
    private val minClosureMs: Long = 1_200L,
    private val maxClosureMs: Long = 4_000L,
    /** All the blinks must end within this long of each other. */
    private val windowMs: Long = 15_000L,
    /** Eye openings shorter than this inside a closure are tracking flicker, not a reopen. */
    private val reopenGraceMs: Long = 250L,
    /** A closure survives the face being lost for this long (closed eyes are harder to detect). */
    private val faceLossToleranceMs: Long = 1_000L,
    /** Told about every finished closure and whether it counted, for tuning on a real face. */
    private val onClosure: ((durationMs: Long, counted: Boolean) -> Unit)? = null,
) {
    enum class Event { NONE, COUNTED, TRIGGERED, EXPIRED }

    private val counted = ArrayDeque<Long>()
    private var closedSinceMs = -1L
    private var reopenedAtMs = -1L
    private var faceLostAtMs = -1L

    /** Long blinks counted towards the signal so far. */
    val count: Int get() = counted.size

    /** How long the eyes have been closed, 0 while they are open. */
    fun closureMs(nowMs: Long): Long = if (closedSinceMs < 0L || reopenedAtMs >= 0L) 0L else nowMs - closedSinceMs

    fun update(faceVisible: Boolean, eyesClosed: Boolean, nowMs: Long): Event {
        val expired = expire(nowMs)
        val longBlink = when {
            !faceVisible -> {
                onFaceLost(nowMs)
                false
            }
            eyesClosed -> {
                faceLostAtMs = -1L
                reopenedAtMs = -1L
                if (closedSinceMs < 0L) closedSinceMs = nowMs
                false
            }
            else -> {
                faceLostAtMs = -1L
                onEyesOpen(nowMs)
            }
        }
        return when {
            longBlink && counted.size >= requiredBlinks -> {
                counted.clear()
                Event.TRIGGERED
            }
            longBlink -> Event.COUNTED
            expired -> Event.EXPIRED
            else -> Event.NONE
        }
    }

    fun reset() {
        counted.clear()
        closedSinceMs = -1L
        reopenedAtMs = -1L
        faceLostAtMs = -1L
    }

    /** True when the last counted blink just aged out, so the "1 of 3" hint should go away. */
    private fun expire(nowMs: Long): Boolean {
        if (counted.isEmpty()) return false
        while (counted.isNotEmpty() && nowMs - counted.first() > windowMs) counted.removeFirst()
        return counted.isEmpty()
    }

    private fun onFaceLost(nowMs: Long) {
        if (closedSinceMs < 0L) return
        if (faceLostAtMs < 0L) faceLostAtMs = nowMs
        if (nowMs - faceLostAtMs > faceLossToleranceMs) {
            closedSinceMs = -1L
            reopenedAtMs = -1L
            faceLostAtMs = -1L
        }
    }

    /** True when a closure just ended and was a long blink. */
    private fun onEyesOpen(nowMs: Long): Boolean {
        if (closedSinceMs < 0L) return false
        if (reopenedAtMs < 0L) reopenedAtMs = nowMs
        if (nowMs - reopenedAtMs < reopenGraceMs) return false
        val duration = reopenedAtMs - closedSinceMs
        closedSinceMs = -1L
        reopenedAtMs = -1L
        val isLongBlink = duration in minClosureMs..maxClosureMs
        onClosure?.invoke(duration, isLongBlink)
        if (isLongBlink) counted.addLast(nowMs)
        return isLongBlink
    }
}

/**
 * Pure: notices when the camera has lost the patient. Without a visible face none of the eye
 * inputs work, including the help signal, and the patient has no way to say so.
 */
class PresenceWatchdog @JvmOverloads constructor(private val absentAfterMs: Long = 20_000L) {
    var absent = false
        private set

    private var lastSeenMs = -1L

    /** Call for every analysed frame. Returns true when [absent] changed. */
    fun onSample(faceVisible: Boolean, nowMs: Long): Boolean {
        if (!faceVisible) return tick(nowMs)
        lastSeenMs = nowMs
        if (!absent) return false
        absent = false
        return true
    }

    /** Call about once a second, so a stalled camera is noticed too. Returns true when [absent] changed. */
    fun tick(nowMs: Long): Boolean {
        // Nothing to lose until the patient has been seen once.
        if (absent || lastSeenMs < 0L || nowMs - lastSeenMs < absentAfterMs) return false
        absent = true
        return true
    }

    fun reset() {
        absent = false
        lastSeenMs = -1L
    }
}
