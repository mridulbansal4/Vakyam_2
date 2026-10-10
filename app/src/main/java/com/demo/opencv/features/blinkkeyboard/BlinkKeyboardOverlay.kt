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
    private val tray = color(R.color.vk_tray)
    private val keyFlat = color(R.color.vk_key_flat)
    private val keyFunction = color(R.color.vk_key_function)
    private val keyShadow = color(R.color.vk_key_shadow)
    private val keyAlt = color(R.color.vk_key_alt)
    private val selecting = color(R.color.vk_key_selecting)
    private val selectingSoft = (selecting and 0x00FFFFFF) or (0x59 shl 24)
    private val outline = color(R.color.vk_outline)
    private val warning = color(R.color.vk_warning)

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
            KeyItem("backspace", "Backspace", KeyType.ACTION, KeyActions.BACKSPACE),
            KeyItem("space", "Space", KeyType.ACTION, KeyActions.SPACE),
            KeyItem("speak", "Speak & save", KeyType.ACTION, KeyActions.SPEAK_AND_SAVE),
            KeyItem("cancel", "Cancel", KeyType.ACTION, KeyActions.CANCEL),
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

        // Header: title and scan state on the left, camera state and close on the right.
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(chrome)
            setPadding(dp(20), 0, dp(8), 0)
        }
        header.addView(text("Spell", 20f, onChrome), wrap())
        statusView = text("", 14f, ink).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(dp(12), dp(5), dp(12), dp(5))
        }
        header.addView(statusView, wrap().apply { marginStart = dp(14) })
        header.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        cameraStatusView = text("Starting camera…", 13f, onChrome, medium = false).apply { maxLines = 1 }
        header.addView(cameraStatusView, wrap())
        val closeButton = ImageView(context).apply {
            setImageResource(R.drawable.baseline_close_24)
            setColorFilter(onChrome)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = "Close keyboard"
            setOnClickListener { listener?.onClose() }
        }
        header.addView(closeButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(12) })
        root.addView(header, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))

        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        root.addView(body, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))

        typingTestView = text("", 15f, onChrome).apply {
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(accent, dp(12).toFloat())
            maxLines = 1
            visibility = GONE
        }
        body.addView(typingTestView, matchWrap().apply { bottomMargin = dp(8) })

        // Composer: the spelled text, blink feedback on the right, the blink gauge as a line along the bottom.
        val composer = FrameLayout(context).apply {
            background = rounded(keyFlat, dp(16).toFloat())
            elevation = dp(1).toFloat()
        }
        val composerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), dp(4))
        }
        bufferView = text("", 28f, ink).apply {
            letterSpacing = 0.03f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
        }
        composerRow.addView(bufferView, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val feedback = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        gaugeLabel = text(gaugeText(0), 15f, inkSoft).apply { maxLines = 1 }
        feedback.addView(gaugeLabel, wrap())
        infoView = text("Close eyes ½–1 s to select", 13f, inkMuted, medium = false).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        feedback.addView(infoView, wrap())
        composerRow.addView(feedback, LinearLayout.LayoutParams(dp(300), LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16) })
        composer.addView(composerRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        gaugeView = DwellGaugeView(context)
        composer.addView(gaugeView, LayoutParams(LayoutParams.MATCH_PARENT, dp(4), Gravity.BOTTOM).apply {
            leftMargin = dp(16); rightMargin = dp(16); bottomMargin = dp(6)
        })
        body.addView(composer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(60)))

        // Keyboard tray
        val rowsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(tray, dp(20).toFloat())
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        matrix.forEachIndexed { i, _ ->
            val rowView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }
            rowViews.add(rowView)
            keyViews.add(emptyList())
            rowsContainer.addView(rowView, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
                if (i > 0) topMargin = dp(1)
            })
        }
        body.addView(rowsContainer, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(10)
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
                text("Word suggestions appear here as you spell", 16f, inkMuted, medium = false).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
            )
        }
        // Letter keys share one width across rows, so shorter rows are centred like a real keyboard.
        val inset = if (row.style == RowStyle.LETTERS) (LETTER_COLUMNS - row.items.size) / 2f else 0f
        if (inset > 0) rowView.addView(View(context), LinearLayout.LayoutParams(0, 1, inset))
        keyViews[rowIdx] = row.items.mapIndexed { i, item ->
            if (row.style == RowStyle.SUGGESTIONS && i > 0) {
                rowView.addView(View(context).apply { setBackgroundColor(keyShadow) },
                    LinearLayout.LayoutParams(dp(1), LayoutParams.MATCH_PARENT).apply { topMargin = dp(8); bottomMargin = dp(8) })
            }
            val size = when (row.style) {
                RowStyle.LETTERS -> 26f
                RowStyle.SUGGESTIONS -> 22f
                RowStyle.ACTIONS -> 18f
            }
            text(item.label, size, ink, medium = row.style != RowStyle.SUGGESTIONS).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                if (row.style != RowStyle.SUGGESTIONS) setPadding(0, 0, 0, dp(KEY_SHADOW_DP))
            }.also {
                rowView.addView(it, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, keyWeight(item)).apply {
                    marginStart = dp(3); marginEnd = dp(3)
                })
            }
        }
        if (inset > 0) rowView.addView(View(context), LinearLayout.LayoutParams(0, 1, inset))
    }

    private fun keyWeight(item: KeyItem): Float = when (item.value) {
        KeyActions.SPACE -> 3f
        KeyActions.SPEAK_AND_SAVE -> 2.5f
        KeyActions.BACKSPACE, KeyActions.CANCEL -> 1.5f
        else -> 1f
    }

    private fun render() {
        statusView.text = when (engine.tier) {
            ScanTier.ROW -> "Choosing a row"
            ScanTier.COLUMN -> "Choosing a key"
            ScanTier.PAUSED -> "Paused · blink or tap to resume"
        }
        statusView.background = rounded(
            when (engine.tier) {
                ScanTier.ROW -> keyAlt
                ScanTier.COLUMN -> selecting
                ScanTier.PAUSED -> surfaceTint
            },
            dp(18).toFloat(),
        )
        val rowRadius = dp(14).toFloat()
        matrix.forEachIndexed { r, row ->
            val isActiveRow = r == engine.activeRowIdx
            // The scanned row is tinted amber and ringed in ink, so it reads without relying on colour alone.
            rowViews[r].background = when {
                isActiveRow && engine.tier == ScanTier.ROW -> rounded(selectingSoft, rowRadius, ink, dp(3))
                isActiveRow && engine.tier == ScanTier.COLUMN -> rounded(Color.TRANSPARENT, rowRadius, inkSoft, dp(2))
                else -> null
            }
            keyViews[r].forEachIndexed { c, key ->
                val isActiveKey = isActiveRow && engine.tier == ScanTier.COLUMN && c == engine.activeColIdx
                val value = row.items[c].value
                val isSpeak = value == KeyActions.SPEAK_AND_SAVE
                val isSuggestion = row.style == RowStyle.SUGGESTIONS
                key.background = when {
                    isActiveKey && isSuggestion -> rounded(selecting, dp(10).toFloat(), ink, dp(3))
                    isActiveKey -> keyFace(selecting, stroke = ink)
                    isSuggestion -> null
                    isSpeak -> keyFace(accent)
                    row.style == RowStyle.LETTERS || value == KeyActions.SPACE -> keyFace(keyFlat)
                    else -> keyFace(keyFunction)
                }
                key.setTextColor(if (isSpeak && !isActiveKey) onChrome else ink)
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
        durationMs <= config.maxBlinkDurationMs -> "✓ Open eyes now to select"
        durationMs < config.restClosureMs -> "Too long, won't select"
        else -> "Resting, scanning paused"
    }

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    /** A flat key face resting on a soft shadow a couple of dp below it. */
    private fun keyFace(fill: Int, stroke: Int = Color.TRANSPARENT): Drawable {
        val radius = dp(10).toFloat()
        val face = rounded(fill, radius, stroke, if (stroke == Color.TRANSPARENT) 0 else dp(3))
        return LayerDrawable(arrayOf(rounded(keyShadow, radius), face)).apply {
            setLayerInset(1, 0, 0, 0, dp(KEY_SHADOW_DP))
        }
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int = Color.TRANSPARENT, strokeWidth: Int = 0) =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(fill)
            if (strokeWidth > 0) setStroke(strokeWidth, stroke)
        }

    private fun text(value: String, sizeSp: Float, color: Int, medium: Boolean = true) = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
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
        const val LETTER_COLUMNS = 10
        const val KEY_SHADOW_DP = 2
    }
}
