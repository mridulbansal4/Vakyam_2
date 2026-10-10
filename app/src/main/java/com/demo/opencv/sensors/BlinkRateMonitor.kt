package com.demo.opencv.sensors

/**
 * Pure: per-frame eye state → blinks per minute, and whether it is time to suggest a rest.
 *
 * People blink far less while they concentrate on a screen, and that is what dries and tires the
 * eyes. A rest is due once the rate has stayed under [lowBlinksPerMin] for a whole [windowMs] of
 * the patient actually watching. Closing the eyes for [restClosureMs] is the rest: it clears the
 * reminder and starts a fresh window.
 */
class BlinkRateMonitor @JvmOverloads constructor(
    private val windowMs: Long = 60_000L,
    private val lowBlinksPerMin: Float = 6f,
    /** A closure this long is a rest, not a blink. */
    private val restClosureMs: Long = 2_000L,
    /** The reminder goes away by itself after this long if the patient does not rest. */
    private val showForMs: Long = 30_000L,
    /** No second reminder for this long, however low the rate stays. */
    private val cooldownMs: Long = 10 * 60_000L,
    /** The face may be lost for this long (closed eyes are harder to detect) before watching starts over. */
    private val faceLossToleranceMs: Long = 3_000L,
) {
    var restDue = false
        private set

    private val blinks = ArrayDeque<Long>()
    private var watchingSinceMs = -1L
    private var lastSeenMs = -1L
    private var closedSinceMs = -1L
    private var rested = false
    private var dueSinceMs = -1L
    private var quietUntilMs = 0L

    /** NaN until the patient has been watched for a full window. */
    fun blinksPerMin(nowMs: Long): Float {
        if (watchingSinceMs < 0L || nowMs - watchingSinceMs < windowMs) return Float.NaN
        while (blinks.isNotEmpty() && nowMs - blinks.first() > windowMs) blinks.removeFirst()
        return blinks.size * 60_000f / windowMs
    }

    /** Call for every analysed frame. Returns true when [restDue] changed. */
    fun update(faceVisible: Boolean, eyesClosed: Boolean, nowMs: Long): Boolean {
        val wasDue = restDue
        if (!faceVisible) {
            if (lastSeenMs >= 0L && nowMs - lastSeenMs > faceLossToleranceMs) stopWatching()
            expire(nowMs)
            return restDue != wasDue
        }
        if (lastSeenMs >= 0L && nowMs - lastSeenMs > faceLossToleranceMs) stopWatching() // the camera stalled
        lastSeenMs = nowMs
        if (watchingSinceMs < 0L) watchingSinceMs = nowMs

        if (eyesClosed) {
            if (closedSinceMs < 0L) closedSinceMs = nowMs
            if (!rested && nowMs - closedSinceMs >= restClosureMs) onRest(nowMs)
        } else if (closedSinceMs >= 0L) {
            if (!rested) blinks.addLast(nowMs)
            if (rested) watchingSinceMs = nowMs // the window after a rest starts when the eyes reopen
            closedSinceMs = -1L
            rested = false
        }

        expire(nowMs)
        if (!restDue && nowMs >= quietUntilMs) {
            val rate = blinksPerMin(nowMs)
            if (!rate.isNaN() && rate < lowBlinksPerMin) {
                restDue = true
                dueSinceMs = nowMs
            }
        }
        return restDue != wasDue
    }

    /** Call about once a second, so the reminder still times out while no frames arrive. Returns true when [restDue] changed. */
    fun tick(nowMs: Long): Boolean = expire(nowMs)

    /** The screen was left: the next session starts a fresh window. The cooldown is kept. */
    fun reset() {
        stopWatching()
        restDue = false
        dueSinceMs = -1L
    }

    private fun onRest(nowMs: Long) {
        rested = true
        blinks.clear()
        if (restDue) dismiss(nowMs)
    }

    private fun expire(nowMs: Long): Boolean {
        if (!restDue || nowMs - dueSinceMs < showForMs) return false
        dismiss(nowMs)
        return true
    }

    private fun dismiss(nowMs: Long) {
        restDue = false
        dueSinceMs = -1L
        quietUntilMs = nowMs + cooldownMs
    }

    private fun stopWatching() {
        blinks.clear()
        watchingSinceMs = -1L
        lastSeenMs = -1L
        closedSinceMs = -1L
        rested = false
    }
}
