package com.demo.opencv.features.blinkkeyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanningEngineTest {

    private class ManualScheduler : ScanningEngine.Scheduler {
        var pending: Runnable? = null
        override fun schedule(delayMs: Long, task: Runnable) {
            pending = task
        }

        override fun cancel() {
            pending = null
        }

        fun advance(times: Int = 1) = repeat(times) { pending?.run() }
    }

    private fun row(id: String, vararg labels: String) = KeyboardRow(
        id, id, labels.map { KeyItem(it, it, KeyType.CHAR, it) }, RowStyle.LETTERS,
    )

    private var matrix = listOf(row("suggestions"), row("r1", "a", "b", "c"), row("r2", "d", "e"))
    private val scheduler = ManualScheduler()
    private val activated = mutableListOf<String>()
    private val events = mutableListOf<ScanningEngine.Event>()
    private val engine = ScanningEngine({ matrix }, BlinkConfig(autoSleepLoops = 2), object : ScanningEngine.Listener {
        override fun onStateChanged() {}
        override fun onKeyActivated(item: KeyItem) {
            activated += item.value
        }

        override fun onScanEvent(event: ScanningEngine.Event) {
            events += event
        }
    }, scheduler)

    @Test
    fun emptyRowsAreSkipped() {
        engine.start()
        assertEquals(1, engine.activeRowIdx)
        scheduler.advance()
        assertEquals(2, engine.activeRowIdx)
        scheduler.advance()
        assertEquals(1, engine.activeRowIdx)
    }

    @Test
    fun rowThenKeySelectionTypesCharacterAndReturnsToRowScan() {
        engine.start()
        scheduler.advance() // row 2
        engine.handleSelection()
        assertEquals(ScanTier.COLUMN, engine.tier)
        scheduler.advance() // "e"
        engine.handleSelection()
        assertEquals(listOf("e"), activated)
        assertEquals(ScanTier.ROW, engine.tier)
        assertEquals(1, engine.activeRowIdx)
    }

    @Test
    fun columnScanCancelsAfterTwoUnselectedSweeps() {
        engine.start()
        engine.handleSelection() // latch row 1 (3 keys)
        scheduler.advance(5)
        assertEquals(ScanTier.COLUMN, engine.tier)
        scheduler.advance()
        assertEquals(ScanTier.ROW, engine.tier)
        assertEquals(ScanningEngine.Event.COLUMN_TIMEOUT, events.last())
        assertEquals(activated, emptyList<String>())
    }

    @Test
    fun rowScanAutoSleepsThenResumesOnSelection() {
        engine.start()
        scheduler.advance(3)
        assertEquals(ScanTier.ROW, engine.tier)
        scheduler.advance()
        assertEquals(ScanTier.PAUSED, engine.tier)
        engine.handleSelection()
        assertEquals(ScanTier.ROW, engine.tier)
        assertEquals(ScanningEngine.Event.RESUMED, events.last())
    }

    @Test
    fun selectionUsesPositionCapturedWhenEyesStartedClosing() {
        engine.start()
        engine.handleSelection() // latch row 1
        engine.captureIntent() // eyes close on "a"
        scheduler.advance() // scan moves to "b" before the eyes reopen
        engine.handleSelection()
        assertEquals(listOf("a"), activated)
    }

    @Test
    fun clearedIntentFallsBackToCurrentPosition() {
        engine.start()
        engine.handleSelection()
        engine.captureIntent()
        scheduler.advance()
        engine.clearIntent()
        engine.handleSelection()
        assertEquals(listOf("b"), activated)
    }

    @Test
    fun suggestionsRowBecomesScannableOnceFilled() {
        engine.start()
        matrix = listOf(row("suggestions", "hello"), matrix[1], matrix[2])
        scheduler.advance(2)
        assertEquals(0, engine.activeRowIdx)
    }
}
