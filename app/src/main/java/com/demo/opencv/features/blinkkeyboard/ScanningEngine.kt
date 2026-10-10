package com.demo.opencv.features.blinkkeyboard

import android.os.Handler
import android.os.Looper

/**
 * Two-tier row/column scanner. Rows with no items are skipped. In COLUMN tier, the scan falls
 * back to ROW after [BlinkConfig.columnSweepsBeforeCancel] unselected sweeps; in ROW tier it
 * pauses after [BlinkConfig.autoSleepLoops] unselected loops. Any selection while paused resumes.
 */
class ScanningEngine(
    private val getMatrix: () -> List<KeyboardRow>,
    private val config: BlinkConfig,
    private val listener: Listener,
    private val scheduler: Scheduler = HandlerScheduler(),
) {
    enum class Event { TICK, ROW_LATCHED, KEY_SELECTED, COLUMN_TIMEOUT, PAUSED, RESUMED }

    interface Listener {
        fun onStateChanged()
        fun onKeyActivated(item: KeyItem)
        fun onScanEvent(event: Event)
    }

    interface Scheduler {
        fun schedule(delayMs: Long, task: Runnable)
        fun cancel()
    }

    class HandlerScheduler : Scheduler {
        private val handler = Handler(Looper.getMainLooper())
        private var pending: Runnable? = null

        override fun schedule(delayMs: Long, task: Runnable) {
            cancel()
            pending = task
            handler.postDelayed(task, delayMs)
        }

        override fun cancel() {
            pending?.let { handler.removeCallbacks(it) }
            pending = null
        }
    }

    var tier = ScanTier.PAUSED
        private set
    var activeRowIdx = 0
        private set
    var activeColIdx = 0
        private set
    var isRunning = false
        private set

    private var rowLoops = 0
    private var columnSweeps = 0
    private var intent: Triple<ScanTier, Int, Int>? = null

    fun start() {
        isRunning = true
        enterRowScan()
        listener.onStateChanged()
    }

    fun stop() {
        isRunning = false
        intent = null
        scheduler.cancel()
    }

    fun pause() {
        if (!isRunning || tier == ScanTier.PAUSED) return
        tier = ScanTier.PAUSED
        scheduler.cancel()
        listener.onScanEvent(Event.PAUSED)
        listener.onStateChanged()
    }

    /**
     * Remembers the highlighted position when the eyes start closing, so a selection that
     * completes after the scan has moved on still picks what the user was looking at.
     */
    fun captureIntent() {
        if (isRunning) intent = Triple(tier, activeRowIdx, activeColIdx)
    }

    fun clearIntent() {
        intent = null
    }

    fun handleSelection() {
        if (!isRunning) return
        intent?.let { (capturedTier, row, col) ->
            tier = capturedTier
            activeRowIdx = row
            activeColIdx = col
        }
        intent = null
        val matrix = getMatrix()
        when (tier) {
            ScanTier.PAUSED -> {
                enterRowScan()
                listener.onScanEvent(Event.RESUMED)
            }
            ScanTier.ROW -> {
                tier = ScanTier.COLUMN
                activeColIdx = 0
                columnSweeps = 0
                listener.onScanEvent(Event.ROW_LATCHED)
                scheduleNext()
            }
            ScanTier.COLUMN -> {
                val item = matrix.getOrNull(activeRowIdx)?.items?.getOrNull(activeColIdx)
                if (item != null) {
                    listener.onScanEvent(Event.KEY_SELECTED)
                    listener.onKeyActivated(item)
                }
                if (isRunning) enterRowScan()
            }
        }
        if (isRunning) listener.onStateChanged()
    }

    private fun enterRowScan() {
        tier = ScanTier.ROW
        activeRowIdx = nextNonEmptyRow(getMatrix(), -1)
        activeColIdx = 0
        rowLoops = 0
        scheduleNext()
    }

    private fun scheduleNext() {
        if (!isRunning) return
        scheduler.schedule(config.scanIntervalMs, Runnable { tick() })
    }

    private fun tick() {
        if (!isRunning) return
        val matrix = getMatrix()
        when (tier) {
            ScanTier.ROW -> {
                val next = nextNonEmptyRow(matrix, activeRowIdx)
                if (next <= activeRowIdx) {
                    rowLoops++
                    if (rowLoops >= config.autoSleepLoops) {
                        pause()
                        return
                    }
                }
                activeRowIdx = next
            }
            ScanTier.COLUMN -> {
                val items = matrix.getOrNull(activeRowIdx)?.items.orEmpty()
                if (items.isEmpty()) {
                    enterRowScan()
                    listener.onStateChanged()
                    return
                }
                var next = activeColIdx + 1
                if (next >= items.size) {
                    columnSweeps++
                    if (columnSweeps >= config.columnSweepsBeforeCancel) {
                        tier = ScanTier.ROW
                        activeColIdx = 0
                        rowLoops = 0
                        listener.onScanEvent(Event.COLUMN_TIMEOUT)
                        listener.onStateChanged()
                        scheduleNext()
                        return
                    }
                    next = 0
                }
                activeColIdx = next
            }
            ScanTier.PAUSED -> return
        }
        listener.onScanEvent(Event.TICK)
        listener.onStateChanged()
        scheduleNext()
    }

    private fun nextNonEmptyRow(matrix: List<KeyboardRow>, from: Int): Int {
        if (matrix.isEmpty()) return 0
        for (step in 1..matrix.size) {
            val idx = Math.floorMod(from + step, matrix.size)
            if (matrix[idx].items.isNotEmpty()) return idx
        }
        return 0
    }
}
