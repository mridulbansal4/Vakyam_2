package com.vakyam.app.dwell

import com.vakyam.app.gaze.ScreenPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Axis-aligned bounds of a gaze target in root-window pixels. */
data class GazeRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float, inflate: Float = 0f): Boolean =
        x >= left - inflate && x <= right + inflate && y >= top - inflate && y <= bottom + inflate
}

/** Which target the gaze is resting on and how far its dwell timer has run (0..1). */
data class DwellState(val targetId: String? = null, val progress: Float = 0f)

/**
 * Dwell-to-select: a target fires once the gaze has rested on it for its dwell duration.
 *
 * Three mechanisms keep noisy gaze from causing missed or accidental selections:
 * - **Hysteresis**: the target currently being dwelled on is tested with bounds inflated by
 *   [hysteresisPx], so jitter at a tile edge does not bounce the gaze to its neighbour.
 * - **Grace**: leaving the target (or losing the eyes in a blink) for up to [graceMs] does not
 *   reset the timer.
 * - **Cooldown**: after a selection nothing can be dwelled on for [cooldownMs]. The UI usually
 *   changes under the gaze after a selection; without this the tile that appears there would start
 *   filling immediately (the "Midas touch" problem).
 *
 * Thread-safe: targets are registered from the main thread, [onGaze] runs on the vision thread.
 */
class DwellDetector(
    private val defaultDwellMs: Long = 700,
    private val graceMs: Long = 180,
    private val cooldownMs: Long = 600,
    private val hysteresisPx: Float = 28f,
) {
    private class Target(val rect: GazeRect, val dwellMs: Long)

    private val targets = HashMap<String, Target>()
    private var currentId: String? = null
    private var dwellStartMs = 0L
    private var lastInsideMs = 0L
    private var cooldownUntilMs = 0L

    private val _state = MutableStateFlow(DwellState())
    val state: StateFlow<DwellState> = _state.asStateFlow()

    @Synchronized
    fun setTarget(id: String, rect: GazeRect, dwellMs: Long? = null) {
        targets[id] = Target(rect, dwellMs ?: defaultDwellMs)
    }

    @Synchronized
    fun removeTarget(id: String) {
        targets.remove(id)
        if (currentId == id) clearCurrent()
    }

    /** Drops any dwell in progress and starts a cooldown from [nowMs]. */
    @Synchronized
    fun reset(nowMs: Long) {
        clearCurrent()
        cooldownUntilMs = nowMs + cooldownMs
    }

    /**
     * Feeds the latest gaze position ([point] is null while the eyes are not tracked).
     *
     * @return the id of the target that was just selected, or null.
     */
    @Synchronized
    fun onGaze(point: ScreenPoint?, nowMs: Long): String? {
        if (nowMs < cooldownUntilMs) {
            clearCurrent()
            return null
        }

        val hitId = point?.let { hitTest(it) }
        when {
            hitId == null -> {
                if (currentId != null && nowMs - lastInsideMs > graceMs) clearCurrent()
                if (currentId == null) return null
            }

            hitId != currentId -> {
                currentId = hitId
                dwellStartMs = nowMs
                lastInsideMs = nowMs
            }

            else -> lastInsideMs = nowMs
        }

        val id = currentId ?: return null
        val target = targets[id] ?: run {
            clearCurrent()
            return null
        }
        val progress = ((nowMs - dwellStartMs).toFloat() / target.dwellMs).coerceIn(0f, 1f)
        if (progress >= 1f) {
            clearCurrent()
            cooldownUntilMs = nowMs + cooldownMs
            return id
        }
        _state.value = DwellState(id, progress)
        return null
    }

    private fun hitTest(point: ScreenPoint): String? {
        val current = currentId
        if (current != null && targets[current]?.rect?.contains(point.x, point.y, hysteresisPx) == true) {
            return current
        }
        // Targets should not overlap, but if they do the one centred nearest the gaze wins.
        var bestId: String? = null
        var bestDistance = Float.MAX_VALUE
        for ((id, target) in targets) {
            if (!target.rect.contains(point.x, point.y)) continue
            val ddx = target.rect.centerX - point.x
            val ddy = target.rect.centerY - point.y
            val distance = ddx * ddx + ddy * ddy
            if (distance < bestDistance) {
                bestDistance = distance
                bestId = id
            }
        }
        return bestId
    }

    private fun clearCurrent() {
        currentId = null
        if (_state.value.targetId != null) _state.value = DwellState()
    }
}
