package com.demo.opencv.features.blinkkeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.min

/**
 * High-contrast eye-blink scanning keyboard. Feed camera results through [onEyeSample] or use
 * [triggerManualSelect] (screen tap / Space) as a switch; spelled text is spoken through
 * [speaker], saved to [CustomVocabularyService] and reported via [Listener.onWordCompleted].
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class BlinkKeyboardOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    val config: BlinkConfig = BlinkConfig(),
) : FrameLayout(context, attrs) {

    interface Listener {
        fun onWordCompleted(word: String)
        fun onClose()
    }

    fun interface Speaker {
        fun speak(text: String)
    }

    var listener: Listener? = null
    var speaker: Speaker? = null

    private val audio = AudioFeedbackService()
    private val vocabulary = CustomVocabularyService(context)
    private val predictor = WordPredictionService(context, vocabulary)
    private val buffer = StringBuilder()
    private var lastSelectionMs = 0L

    private val letterRows = listOf("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM").mapIndexed { i, letters ->
        KeyboardRow(
            id = "letters_${i + 1}",
            label = "Letters",
            items = letters.map { KeyItem("key_$it", it.toString(), KeyType.CHAR, it.lowercaseChar().toString()) },
            style = RowStyle.LETTERS,
        )
    }
    private val actionRow = KeyboardRow(
        id = "actions",
        label = "Actions",
        items = listOf(
            KeyItem("backspace", "⌫ BACKSPACE", KeyType.ACTION, KeyActions.BACKSPACE),
            KeyItem("space", "␣ SPACE", KeyType.ACTION, KeyActions.SPACE),
            KeyItem("speak", "🔊 SPEAK & SAVE", KeyType.ACTION, KeyActions.SPEAK_AND_SAVE),
            KeyItem("cancel", "✖ CANCEL", KeyType.ACTION, KeyActions.CANCEL),
        ),
        style = RowStyle.ACTIONS,
    )
    private var matrix: List<KeyboardRow> = buildMatrix(emptyList())

    private val engine = ScanningEngine({ matrix }, config, object : ScanningEngine.Listener {
        override fun onStateChanged() = render()
        override fun onKeyActivated(item: KeyItem) = activate(item)
        override fun onScanEvent(event: ScanningEngine.Event) = when (event) {
            ScanningEngine.Event.TICK -> audio.playTick()
            ScanningEngine.Event.ROW_LATCHED,
            ScanningEngine.Event.KEY_SELECTED,
            ScanningEngine.Event.RESUMED -> audio.playSelectChime()
            ScanningEngine.Event.COLUMN_TIMEOUT -> audio.playCancel()
            ScanningEngine.Event.PAUSED -> audio.playRest()
        }
    })

    private val detector = BlinkDetector(config, object : BlinkDetector.Listener {
        override fun onBlink(kind: BlinkDetector.BlinkKind, durationMs: Long) {
            infoView.text = when (kind) {
                BlinkDetector.BlinkKind.INVOLUNTARY -> "Natural blink ignored ($durationMs ms)"
                BlinkDetector.BlinkKind.AMBIGUOUS -> "Blink too short to select ($durationMs ms)"
                BlinkDetector.BlinkKind.SELECT -> "Selected by blink ($durationMs ms)"
                BlinkDetector.BlinkKind.TOO_LONG -> "Blink too long to select ($durationMs ms)"
                BlinkDetector.BlinkKind.REST -> "Rested ${durationMs / 1000.0} s. Blink to resume"
            }
            if (kind != BlinkDetector.BlinkKind.SELECT) {
                engine.clearIntent()
            } else if (!select()) {
                infoView.text = "Blink ignored, too soon after last selection"
            }
        }

        override fun onClosureStarted() = engine.captureIntent()

        override fun onRestStarted() = engine.pause()

        override fun onClosureProgress(durationMs: Long) {
            gaugeView.durationMs = durationMs
            gaugeLabel.text = gaugeText(durationMs)
        }
    })

    private val statusView: TextView
    private val cameraStatusView: TextView
    private val bufferView: TextView
    private val gaugeView: DwellGaugeView
    private val gaugeLabel: TextView
    private val infoView: TextView
    private val rowViews = mutableListOf<LinearLayout>()
    private val keyViews = mutableListOf<List<TextView>>()

    init {
        setBackgroundColor(BG)
        isSoundEffectsEnabled = false
        setOnClickListener { triggerManualSelect() }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(text("👁 Spell with Eyes", 20f, CYAN, bold = true), wrap())
        statusView = text("", 16f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        header.addView(statusView, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(16); marginEnd = dp(16)
        })
        cameraStatusView = text("Starting camera…", 13f, MUTED)
        header.addView(cameraStatusView, wrap())
        val closeButton = text("✕", 22f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFF334155.toInt(), dp(8).toFloat())
            contentDescription = "Close keyboard"
            setOnClickListener { listener?.onClose() }
        }
        header.addView(closeButton, LinearLayout.LayoutParams(dp(44), dp(40)).apply { marginStart = dp(12) })
        root.addView(header, matchWrap())

        bufferView = text("", 30f, Color.WHITE, bold = true).apply {
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(4))
            background = rounded(0xFF020617.toInt(), dp(10).toFloat(), 0xFF334155.toInt(), dp(2))
            maxLines = 1
        }
        root.addView(bufferView, matchWrap().apply { topMargin = dp(6) })

        val gaugeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        gaugeView = DwellGaugeView(context, config)
        gaugeRow.addView(gaugeView, LinearLayout.LayoutParams(0, dp(14), 1f))
        gaugeLabel = text(gaugeText(0), 14f, MUTED)
        gaugeRow.addView(gaugeLabel, LinearLayout.LayoutParams(dp(250), LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(12)
        })
        root.addView(gaugeRow, matchWrap().apply { topMargin = dp(6) })

        infoView = text(
            "Natural blinks are ignored. Close eyes ½–1 s to select, over 2 s to rest. Tap screen or press Space as a switch.",
            13f, MUTED,
        ).apply { maxLines = 1 }
        root.addView(infoView, matchWrap().apply { topMargin = dp(2) })

        val rowsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        matrix.forEach { _ ->
            val rowView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(5), dp(5), dp(5), dp(5))
            }
            rowViews.add(rowView)
            keyViews.add(emptyList())
            rowsContainer.addView(rowView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(3)
            })
        }
        root.addView(rowsContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(4)
        })

        matrix.indices.forEach(::rebuildRowViews)
        onBufferChanged()
    }

    fun start() {
        engine.start()
    }

    fun stop() {
        engine.stop()
        detector.reset()
    }

    fun release() {
        stop()
        audio.release()
    }

    /** Switch-access fallback (screen tap, Space bar) that behaves like an intentional blink. */
    fun triggerManualSelect() {
        engine.clearIntent()
        if (select()) infoView.text = "Selected by switch"
    }

    private fun select(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastSelectionMs < config.selectionCooldownMs) {
            engine.clearIntent()
            return false
        }
        lastSelectionMs = now
        engine.handleSelection()
        return true
    }

    fun onEyeSample(sample: EyeAspectRatioAnalyzer.EyeSample) {
        if (!engine.isRunning) return
        if (!sample.faceFound) {
            detector.reset()
            cameraStatusView.text = "No face. Tap or press Space"
            cameraStatusView.setTextColor(AMBER)
            return
        }
        detector.processEyeState(sample.eyesClosed, sample.timestampMs)
        val ear = if (sample.ear.isNaN()) "--" else String.format(Locale.US, "%.2f", sample.ear)
        cameraStatusView.text = String.format(
            Locale.US, "Face ✓  EAR %s / %.2f  open %.2f", ear, sample.earThreshold, sample.eyeOpenProbability,
        )
        cameraStatusView.setTextColor(if (sample.eyesClosed) GREEN else MUTED)
    }

    fun setCameraStatus(message: String) {
        cameraStatusView.text = message
        cameraStatusView.setTextColor(AMBER)
    }

    private fun activate(item: KeyItem) {
        when (item.type) {
            KeyType.CHAR -> buffer.append(item.value)
            KeyType.SUGGESTION -> {
                buffer.setLength(buffer.lastIndexOf(" ") + 1)
                buffer.append(item.value)
                commit()
                return
            }
            KeyType.ACTION -> when (item.value) {
                KeyActions.BACKSPACE -> if (buffer.isNotEmpty()) buffer.setLength(buffer.length - 1)
                KeyActions.SPACE -> if (buffer.isNotEmpty() && buffer.last() != ' ') buffer.append(' ')
                KeyActions.SPEAK_AND_SAVE -> {
                    commit()
                    return
                }
                KeyActions.CANCEL -> {
                    listener?.onClose()
                    return
                }
            }
        }
        onBufferChanged()
    }

    private fun commit() {
        val words = buffer.split(' ').filter { it.isNotBlank() }.map(::normalizeWord)
        if (words.isEmpty()) {
            audio.playCancel()
            infoView.text = "Nothing to speak yet"
            return
        }
        val text = words.joinToString(" ")
        speaker?.speak(text)
        words.forEach(vocabulary::saveWord)
        audio.playWordSaved()
        infoView.text = "🔊 Spoke and saved “$text”"
        buffer.setLength(0)
        onBufferChanged()
        listener?.onWordCompleted(text)
    }

    /** Keeps saved capitalisation; dictionary words stay lowercase; unknown words become proper nouns. */
    private fun normalizeWord(word: String): String = when {
        word.any(Char::isUpperCase) -> word
        predictor.isDictionaryWord(word) -> word
        else -> word.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    private fun onBufferChanged() {
        val prefix = buffer.substring(buffer.lastIndexOf(" ") + 1)
        matrix = buildMatrix(predictor.predict(prefix, 3))
        rebuildRowViews(0)
        bufferView.text = if (buffer.isEmpty()) "Start spelling…▌" else buffer.toString().uppercase() + "▌"
        bufferView.setTextColor(if (buffer.isEmpty()) MUTED else Color.WHITE)
        render()
    }

    private fun buildMatrix(suggestions: List<String>): List<KeyboardRow> {
        val suggestionRow = KeyboardRow(
            id = "suggestions",
            label = "Predictions",
            items = suggestions.mapIndexed { i, w -> KeyItem("suggestion_$i", w, KeyType.SUGGESTION, w) },
            style = RowStyle.SUGGESTIONS,
        )
        return listOf(suggestionRow) + letterRows + actionRow
    }

    private fun rebuildRowViews(rowIdx: Int) {
        val row = matrix[rowIdx]
        val rowView = rowViews[rowIdx]
        rowView.removeAllViews()
        if (row.items.isEmpty() && row.style == RowStyle.SUGGESTIONS) {
            rowView.addView(
                text("Predictions appear here as you type", 16f, 0xFF7C3AED.toInt()).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
            )
        }
        keyViews[rowIdx] = row.items.map { item ->
            val size = when (row.style) {
                RowStyle.LETTERS -> 24f
                RowStyle.SUGGESTIONS -> 22f
                RowStyle.ACTIONS -> 18f
            }
            text(item.label, size, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                maxLines = 1
            }.also {
                rowView.addView(it, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = dp(3); marginEnd = dp(3)
                })
            }
        }
    }

    private fun render() {
        statusView.text = when (engine.tier) {
            ScanTier.ROW -> "ROW SCAN · blink to pick the glowing row"
            ScanTier.COLUMN -> "KEY SCAN · blink to pick the glowing key"
            ScanTier.PAUSED -> "PAUSED · blink ½ s or tap to resume"
        }
        statusView.background = rounded(
            when (engine.tier) {
                ScanTier.ROW -> 0xFF155E75.toInt()
                ScanTier.COLUMN -> 0xFF92400E.toInt()
                ScanTier.PAUSED -> 0xFF581C87.toInt()
            },
            dp(16).toFloat(),
        )
        val radius = dp(10).toFloat()
        matrix.forEachIndexed { r, row ->
            val isActiveRow = r == engine.activeRowIdx
            rowViews[r].background = when {
                isActiveRow && engine.tier == ScanTier.ROW -> glow(0xFF0B3440.toInt(), radius)
                isActiveRow && engine.tier == ScanTier.COLUMN -> rounded(0xFF0F2A33.toInt(), radius, CYAN, dp(2))
                row.style == RowStyle.SUGGESTIONS -> rounded(0xFF1A0B2E.toInt(), radius, 0xFF6B21A8.toInt(), dp(1))
                else -> rounded(Color.TRANSPARENT, radius, 0xFF1E293B.toInt(), dp(1))
            }
            keyViews[r].forEachIndexed { c, key ->
                val isActiveKey = isActiveRow && engine.tier == ScanTier.COLUMN && c == engine.activeColIdx
                val item = row.items[c]
                key.background = if (isActiveKey) glow(0xFF164E63.toInt(), dp(8).toFloat()) else keyBackground(row.style)
                key.setTextColor(
                    when {
                        isActiveKey -> Color.WHITE
                        row.style == RowStyle.SUGGESTIONS -> 0xFFF3E8FF.toInt()
                        item.value == KeyActions.CANCEL -> 0xFFFCA5A5.toInt()
                        row.style == RowStyle.ACTIONS -> 0xFFFDE68A.toInt()
                        else -> 0xFFF8FAFC.toInt()
                    }
                )
            }
        }
    }

    private fun gaugeText(durationMs: Long): String = when {
        durationMs <= 0 -> "Eyes open"
        durationMs < config.involuntaryBlinkMaxMs -> "Natural blink…"
        durationMs < config.minBlinkDurationMs -> "Keep closed…"
        durationMs <= config.maxBlinkDurationMs -> "✓ Open eyes now to SELECT"
        durationMs < config.restClosureMs -> "Too long, won't select"
        else -> "Resting, scanning paused"
    }

    private fun keyBackground(style: RowStyle): Drawable = when (style) {
        RowStyle.SUGGESTIONS -> rounded(0xFF2E1065.toInt(), dp(8).toFloat(), 0xFFA855F7.toInt(), dp(2))
        RowStyle.ACTIONS -> rounded(0xFF1C1917.toInt(), dp(8).toFloat(), 0xFFB45309.toInt(), dp(1))
        RowStyle.LETTERS -> rounded(0xFF1E293B.toInt(), dp(8).toFloat(), 0xFF334155.toInt(), dp(1))
    }

    /** Approximates `box-shadow: 0 0 20px #06B6D4, inset 0 0 10px #06B6D4; border-color: #22D3EE`. */
    private fun glow(fill: Int, radius: Float): Drawable {
        val outer = rounded(Color.TRANSPARENT, radius, 0x3306B6D4, dp(4))
        val middle = rounded(Color.TRANSPARENT, radius, 0x7706B6D4, dp(3))
        val inner = rounded(fill, radius, CYAN, dp(3))
        val innerGlow = rounded(Color.TRANSPARENT, radius, 0x5506B6D4, dp(4))
        return LayerDrawable(arrayOf(outer, middle, inner, innerGlow)).apply {
            setLayerInset(1, dp(3), dp(3), dp(3), dp(3))
            setLayerInset(2, dp(5), dp(5), dp(5), dp(5))
            setLayerInset(3, dp(8), dp(8), dp(8), dp(8))
        }
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int = Color.TRANSPARENT, strokeWidth: Int = 0) =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(fill)
            if (strokeWidth > 0) setStroke(strokeWidth, stroke)
        }

    private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun wrap() = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    private fun matchWrap() = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private class DwellGaugeView(context: Context, private val config: BlinkConfig) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        var durationMs = 0L
            set(value) {
                field = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            val radius = height / 2f
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            paint.color = 0xFF1E293B.toInt()
            canvas.drawRoundRect(rect, radius, radius, paint)
            if (durationMs <= 0) return
            val fraction = min(1f, durationMs.toFloat() / config.minBlinkDurationMs)
            paint.color = when {
                durationMs < config.involuntaryBlinkMaxMs -> MUTED
                durationMs < config.minBlinkDurationMs -> CYAN
                durationMs <= config.maxBlinkDurationMs -> GREEN
                durationMs < config.restClosureMs -> AMBER
                else -> 0xFFA855F7.toInt()
            }
            rect.right = width * fraction
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    private companion object {
        const val BG = 0xFF0F172A.toInt()
        const val CYAN = 0xFF22D3EE.toInt()
        const val GREEN = 0xFF22C55E.toInt()
        const val AMBER = 0xFFF59E0B.toInt()
        const val MUTED = 0xFF94A3B8.toInt()
    }
}
