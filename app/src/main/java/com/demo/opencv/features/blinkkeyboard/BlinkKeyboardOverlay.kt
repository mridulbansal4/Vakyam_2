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
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.demo.opencv.R
import com.demo.opencv.metrics.TypingTest
import com.demo.opencv.personal.PersonalLexicon
import java.util.Locale
import kotlin.math.min

/**
 * Eye-blink scanning keyboard, styled like the text entry screen. Feed camera results through [onEyeSample] or use
 * [triggerManualSelect] (screen tap / Space) as a switch; spelled text is spoken through
 * [speaker], learned by the shared [PersonalLexicon] and reported via [Listener.onWordCompleted].
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
        /** The patient picked the HELP key. */
        fun onHelpRequested()
    }

    fun interface Speaker {
        fun speak(text: String)
    }

    var listener: Listener? = null
    var speaker: Speaker? = null

    private val canvas = color(R.color.vk_canvas)
    private val surfaceTint = color(R.color.vk_surface_tint)
    private val track = color(R.color.vk_track)
    private val chrome = color(R.color.vk_chrome)
    private val onChrome = color(R.color.vk_on_chrome)
    private val accent = color(R.color.vk_accent)
    private val ink = color(R.color.vk_ink)
    private val inkSoft = color(R.color.vk_ink_soft)
    private val inkMuted = color(R.color.vk_ink_muted)
    private val keyAlt = color(R.color.vk_key_alt)
    private val selecting = color(R.color.vk_key_selecting)
    private val outline = color(R.color.vk_outline)
    private val warning = color(R.color.vk_warning)
    private val emergencyRed = color(R.color.vk_emergency)

    private val audio = AudioFeedbackService()
    private val lexicon = PersonalLexicon.get(context)
    private val predictor = WordPredictionService(context, lexicon)
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
            KeyItem("backspace", "BACKSPACE", KeyType.ACTION, KeyActions.BACKSPACE),
            KeyItem("space", "SPACE", KeyType.ACTION, KeyActions.SPACE),
            KeyItem("speak", "SPEAK & SAVE", KeyType.ACTION, KeyActions.SPEAK_AND_SAVE),
            KeyItem("cancel", "CANCEL", KeyType.ACTION, KeyActions.CANCEL),
            KeyItem("help", "HELP", KeyType.ACTION, KeyActions.HELP),
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

    private val typingTestListener = TypingTest.Listener { post { renderTypingTest() } }

    private val statusView: TextView
    private val typingTestView: TextView
    private val cameraStatusView: TextView
    private val bufferView: TextView
    private val gaugeView: DwellGaugeView
    private val gaugeLabel: TextView
    private val infoView: TextView
    private val rowViews = mutableListOf<LinearLayout>()
    private val keyViews = mutableListOf<List<TextView>>()

    init {
        setBackgroundColor(canvas)
        isSoundEffectsEnabled = false
        setOnClickListener { triggerManualSelect() }

        val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addView(root, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // Same dark bar as the main screen's navigation row
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(chrome)
            setPadding(dp(12), dp(4), dp(4), dp(4))
        }
        header.addView(text("SPELL WITH EYES", 20f, onChrome, bold = true), wrap())
        statusView = text("", 16f, ink, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(14), dp(4), dp(14), dp(4))
        }
        header.addView(statusView, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(16); marginEnd = dp(16)
        })
        cameraStatusView = text("Starting camera…", 13f, onChrome)
        header.addView(cameraStatusView, wrap())
        val closeButton = ImageView(context).apply {
            setImageResource(R.drawable.baseline_close_24)
            setBackgroundResource(R.drawable.gaze_option_bg_2)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            contentDescription = "Close keyboard"
            setOnClickListener { listener?.onClose() }
        }
        header.addView(closeButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(12) })
        root.addView(header, matchWrap())

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(8))
        }
        root.addView(body, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        typingTestView = text("", 16f, onChrome, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = rounded(accent, dp(12).toFloat())
            maxLines = 1
            visibility = GONE
        }
        body.addView(typingTestView, matchWrap().apply { bottomMargin = dp(6) })

        bufferView = text("", 26f, ink).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.text_rounded_dark)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            maxLines = 1
        }
        body.addView(bufferView, matchWrap())

        val gaugeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        gaugeView = DwellGaugeView(context)
        gaugeRow.addView(gaugeView, LinearLayout.LayoutParams(0, dp(12), 1f))
        gaugeLabel = text(gaugeText(0), 14f, inkSoft, bold = true)
        gaugeRow.addView(gaugeLabel, LinearLayout.LayoutParams(dp(250), LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(12)
        })
        body.addView(gaugeRow, matchWrap().apply { topMargin = dp(6) })

        infoView = text(
            "Natural blinks are ignored. Close eyes ½–1 s to select, over 2 s to rest, 2 s three times to call for help. Tap or Space is a switch.",
            13f, inkSoft,
        ).apply { maxLines = 1 }
        body.addView(infoView, matchWrap().apply { topMargin = dp(2) })

        val rowsContainer = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        matrix.forEach { _ ->
            val rowView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            rowViews.add(rowView)
            keyViews.add(emptyList())
            rowsContainer.addView(rowView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(3)
            })
        }
        body.addView(rowsContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(4)
        })

        matrix.indices.forEach(::rebuildRowViews)
        onBufferChanged()
    }

    fun start() {
        engine.start()
        TypingTest.addListener(typingTestListener)
        renderTypingTest()
    }

    fun stop() {
        TypingTest.removeListener(typingTestListener)
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
        TypingTest.onSelection()
        engine.handleSelection()
        return true
    }

    fun onEyeSample(sample: EyeAspectRatioAnalyzer.EyeSample) {
        if (!engine.isRunning) return
        if (!sample.faceFound) {
            detector.reset()
            cameraStatusView.text = "No face. Tap or press Space"
            cameraStatusView.alpha = 1f
            return
        }
        detector.processEyeState(sample.eyesClosed, sample.timestampMs)
        val ear = if (sample.ear.isNaN()) "--" else String.format(Locale.US, "%.2f", sample.ear)
        cameraStatusView.text = String.format(
            Locale.US, "Face ✓  EAR %s / %.2f  open %.2f", ear, sample.earThreshold, sample.eyeOpenProbability,
        )
        cameraStatusView.alpha = if (sample.eyesClosed) 1f else QUIET_ALPHA
    }

    fun setCameraStatus(message: String) {
        cameraStatusView.text = message
        cameraStatusView.alpha = 1f
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
                KeyActions.BACKSPACE -> {
                    TypingTest.onCorrection()
                    if (buffer.isNotEmpty()) buffer.setLength(buffer.length - 1)
                }
                KeyActions.SPACE -> if (buffer.isNotEmpty() && buffer.last() != ' ') buffer.append(' ')
                KeyActions.SPEAK_AND_SAVE -> {
                    commit()
                    return
                }
                KeyActions.CANCEL -> {
                    listener?.onClose()
                    return
                }
                KeyActions.HELP -> {
                    listener?.onHelpRequested()
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
        lexicon.recordUtterance(words, text)
        TypingTest.onSpoken(text, TypingTest.MODE_BLINK)
        audio.playWordSaved()
        infoView.text = "Spoke and saved “$text”"
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
        val earlierWords = buffer.trim().split(' ').dropLast(if (prefix.isEmpty()) 0 else 1)
        val previousWord = earlierWords.lastOrNull()
        matrix = buildMatrix(predictor.predict(prefix, previousWord, typedWords = earlierWords.filter { it.isNotBlank() }))
        rebuildRowViews(0)
        bufferView.text = if (buffer.isEmpty()) "Start spelling…▌" else buffer.toString().uppercase() + "▌"
        bufferView.setTextColor(if (buffer.isEmpty()) inkMuted else ink)
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
                text("Predictions appear here as you type", 16f, inkSoft).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
            )
        }
        keyViews[rowIdx] = row.items.map { item ->
            val size = when (row.style) {
                RowStyle.LETTERS -> 24f
                RowStyle.SUGGESTIONS -> 20f
                RowStyle.ACTIONS -> 18f
            }
            text(item.label, size, ink, bold = true).apply {
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
            ScanTier.ROW -> "ROW SCAN · blink to pick the highlighted row"
            ScanTier.COLUMN -> "KEY SCAN · blink to pick the highlighted key"
            ScanTier.PAUSED -> "PAUSED · blink ½ s or tap to resume"
        }
        statusView.background = rounded(
            when (engine.tier) {
                ScanTier.ROW -> keyAlt
                ScanTier.COLUMN -> selecting
                ScanTier.PAUSED -> surfaceTint
            },
            dp(12).toFloat(),
        )
        val radius = dp(14).toFloat()
        matrix.forEachIndexed { r, row ->
            val isActiveRow = r == engine.activeRowIdx
            rowViews[r].background = when {
                isActiveRow && engine.tier == ScanTier.ROW -> rounded(selecting, radius, ink, dp(3))
                isActiveRow && engine.tier == ScanTier.COLUMN -> rounded(Color.TRANSPARENT, radius, accent, dp(2))
                row.style == RowStyle.SUGGESTIONS -> rounded(surfaceTint, dp(12).toFloat())
                else -> null
            }
            keyViews[r].forEachIndexed { c, key ->
                val isActiveKey = isActiveRow && engine.tier == ScanTier.COLUMN && c == engine.activeColIdx
                val isHelp = row.items[c].value == KeyActions.HELP
                key.background = when {
                    isActiveKey -> keyDrawable(R.drawable.gaze_option_bg_selecting)
                    isHelp -> rounded(emergencyRed, radius, outline, dp(2))
                    row.style == RowStyle.LETTERS -> keyDrawable(R.drawable.gaze_option_bg_1)
                    else -> keyDrawable(R.drawable.gaze_option_bg_2)
                }
                key.setTextColor(if (isHelp && !isActiveKey) onChrome else ink)
            }
        }
    }

    private fun renderTypingTest() {
        val trial = TypingTest.trial
        val result = TypingTest.lastResult
        typingTestView.text = when {
            trial != null -> "TYPING TEST · spell this, then SPEAK & SAVE: ${trial.target}"
            result != null -> "TEST DONE · ${result.summary()}"
            else -> ""
        }
        typingTestView.visibility = if (trial == null && result == null) GONE else VISIBLE
    }

    private fun gaugeText(durationMs: Long): String = when {
        durationMs <= 0 -> "Eyes open"
        durationMs < config.involuntaryBlinkMaxMs -> "Natural blink…"
        durationMs < config.minBlinkDurationMs -> "Keep closed…"
        durationMs <= config.maxBlinkDurationMs -> "✓ Open eyes now to SELECT"
        durationMs < config.restClosureMs -> "Too long, won't select"
        else -> "Resting, scanning paused"
    }

    /** The shared key shapes (sage / sand / amber with the soft outline) used by text entry. */
    private fun keyDrawable(id: Int): Drawable? = ContextCompat.getDrawable(context, id)

    private fun color(id: Int) = ContextCompat.getColor(context, id)

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
        typeface = Typeface.create("sans-serif-medium", if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun wrap() = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    private fun matchWrap() = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private inner class DwellGaugeView(context: Context) : View(context) {
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
            paint.color = track
            canvas.drawRoundRect(rect, radius, radius, paint)
            if (durationMs <= 0) return
            val fraction = min(1f, durationMs.toFloat() / config.minBlinkDurationMs)
            paint.color = when {
                durationMs < config.involuntaryBlinkMaxMs -> outline
                durationMs < config.minBlinkDurationMs -> accent
                durationMs <= config.maxBlinkDurationMs -> selecting
                durationMs < config.restClosureMs -> warning
                else -> inkMuted
            }
            rect.right = width * fraction
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    private companion object {
        /** Opacity of the camera read-out while nothing needs attention. */
        const val QUIET_ALPHA = 0.7f
    }
}
