package com.demo.opencv.sensors

/**
 * Thread-safe. Called from the camera analyzer for every delivered frame.
 * Credit schedule (nextDue += interval) so 24/20 fps come out exactly from a 30 fps source.
 */
class FrameRateGovernor(initialFps: Int = SOURCE_FPS) {

    @Volatile
    var targetFps: Int = initialFps.coerceIn(MIN_FPS, SOURCE_FPS)
        set(value) { field = value.coerceIn(MIN_FPS, SOURCE_FPS) }

    private var nextDueMs = Double.NaN

    @Synchronized
    fun shouldProcess(nowMs: Long): Boolean {
        val fps = targetFps
        if (fps >= SOURCE_FPS) {
            nextDueMs = Double.NaN
            return true
        }
        val interval = 1000.0 / fps
        val now = nowMs.toDouble()
        if (!nextDueMs.isNaN() && now + TOLERANCE_MS < nextDueMs) return false
        nextDueMs = if (nextDueMs.isNaN() || now - nextDueMs > interval) now + interval else nextDueMs + interval
        return true
    }

    companion object {
        const val SOURCE_FPS = 30   // MainActivity.bindCamera TARGET_FRAME_RATE
        const val MIN_FPS = 10      // BallisticGestureDetector resets on gaps > 200 ms
        private const val TOLERANCE_MS = 5.0
    }
}
