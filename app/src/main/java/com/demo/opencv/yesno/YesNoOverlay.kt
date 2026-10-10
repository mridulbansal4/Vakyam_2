package com.demo.opencv.yesno

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.demo.opencv.R

/**
 * The Yes / No screen: a huge YES on the left and NO on the right, answered by looking at one of
 * them, with the question and a "back to typing" target (look up) between them. The chosen word is
 * then shown alone with a thick outline, so the result never depends on colour alone. Every target
 * can also be tapped.
 */
@SuppressLint("ViewConstructor")
class YesNoOverlay(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onWordTapped(answer: Answer)
        fun onBackTapped()
    }

    private val canvasColor = color(R.color.vk_canvas)
    private val ink = color(R.color.vk_ink)
    private val inkSoft = color(R.color.vk_ink_soft)
    private val outline = color(R.color.vk_outline)
    private val yesFace = color(R.color.vk_key)
    private val noFace = color(R.color.vk_key_alt)
    private val backFace = color(R.color.vk_surface_tint)
    private val selected = color(R.color.vk_key_selected)

    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = medium
    }
    private val questionPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = medium }
    // The same eye badges as the Type and Quick chat tiles: the pupil shows where to look.
    private val lookLeft = AppCompatResources.getDrawable(context, R.drawable.ic_gaze_left)
    private val lookRight = AppCompatResources.getDrawable(context, R.drawable.ic_gaze_right)
    private val lookUp = AppCompatResources.getDrawable(context, R.drawable.ic_gaze_up)

    // Read again each time the screen opens: the language can change in Settings.
    private var yesLabel = ""
    private var noLabel = ""
    private var backLabel = ""
    private var noAnswerLabel = ""
    private var question = ""
    private var questionLayout: StaticLayout? = null

    private val yesRect = RectF()
    private val noRect = RectF()
    private val backRect = RectF()
    private val scratch = RectF()

    private var result: Answer? = null
    private var ended = false

    init {
        isClickable = true // nothing underneath is pressed while a question is being answered
        visibility = GONE
    }

    fun show(askedQuestion: String) {
        yesLabel = context.getString(R.string.YesNo_Yes)
        noLabel = context.getString(R.string.YesNo_No)
        backLabel = context.getString(R.string.YesNo_Back)
        noAnswerLabel = context.getString(R.string.YesNo_NoAnswer)
        question = askedQuestion.trim().replaceFirstChar { it.uppercase() }
        questionLayout = null
        result = null
        ended = false
        visibility = VISIBLE
        invalidate()
    }

    fun hide() {
        visibility = GONE
    }

    /** The chosen word is shown alone; null shows that nobody answered. */
    fun showResult(answer: Answer?) {
        result = answer
        ended = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        questionLayout = null
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(canvasColor)
        val w = width.toFloat()
        val h = height.toFloat()
        val margin = dp(16f)
        val gap = dp(12f)

        if (ended) {
            val answer = result
            if (answer == null) {
                text.color = inkSoft
                text.textSize = sp(28f)
                canvas.drawText(noAnswerLabel, w / 2f, baseline(h / 2f), text)
            } else {
                scratch.set(w * 0.2f, margin, w * 0.8f, h - margin)
                drawBox(canvas, scratch, if (answer == Answer.YES) yesLabel else noLabel, selected, chosen = true)
            }
            return
        }

        // The answers sit as far apart as the screen allows: the wider the look, the surer the detection.
        val boxWidth = w * 0.34f
        yesRect.set(margin, margin, margin + boxWidth, h - margin)
        noRect.set(w - margin - boxWidth, margin, w - margin, h - margin)
        drawBox(canvas, yesRect, yesLabel, yesFace, chosen = false, look = lookLeft)
        drawBox(canvas, noRect, noLabel, noFace, chosen = false, look = lookRight)

        backRect.set(yesRect.right + gap, margin, noRect.left - gap, margin + dp(72f))
        fill.color = backFace
        canvas.drawRoundRect(backRect, dp(20f), dp(20f), fill)
        strokeRect(canvas, backRect, outline, dp(1.5f))
        text.color = ink
        text.textSize = sp(18f)
        val eye = dp(36f)
        val labelWidth = text.measureText(backLabel)
        val start = backRect.centerX() - (eye + gap + labelWidth) / 2f
        drawEye(canvas, lookUp, start + eye / 2f, backRect.centerY(), eye)
        canvas.drawText(backLabel, start + eye + gap + labelWidth / 2f, baseline(backRect.centerY()), text)

        val layout = questionLayout ?: buildQuestionLayout((backRect.width() - gap).toInt()).also { questionLayout = it }
        val room = h - margin - backRect.bottom
        canvas.save()
        canvas.translate(backRect.left + gap / 2f, backRect.bottom + ((room - layout.height) / 2f).coerceAtLeast(gap))
        layout.draw(canvas)
        canvas.restore()
    }

    private fun buildQuestionLayout(widthPx: Int): StaticLayout {
        questionPaint.color = inkSoft
        questionPaint.textSize = sp(22f)
        return StaticLayout.Builder.obtain(question, 0, question.length, questionPaint, widthPx.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.15f)
            .setMaxLines(5)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
    }

    private fun drawBox(canvas: Canvas, rect: RectF, label: String, face: Int, chosen: Boolean, look: Drawable? = null) {
        fill.color = face
        canvas.drawRoundRect(rect, dp(20f), dp(20f), fill)
        strokeRect(canvas, rect, if (chosen) ink else outline, dp(if (chosen) 4f else 1.5f))
        text.color = ink
        text.textSize = (rect.height() * 0.28f).coerceAtMost(sp(96f))
        canvas.drawText(label, rect.centerX(), baseline(rect.centerY()), text)
        if (look != null) drawEye(canvas, look, rect.centerX(), rect.top + rect.height() * 0.2f, dp(64f))
    }

    private fun drawEye(canvas: Canvas, eye: Drawable?, centerX: Float, centerY: Float, size: Float) {
        eye ?: return
        val half = (size / 2f).toInt()
        eye.setBounds(centerX.toInt() - half, centerY.toInt() - half, centerX.toInt() + half, centerY.toInt() + half)
        eye.draw(canvas)
    }

    private fun strokeRect(canvas: Canvas, rect: RectF, color: Int, width: Float) {
        stroke.color = color
        stroke.strokeWidth = width
        scratch.set(rect)
        scratch.inset(width / 2f, width / 2f)
        canvas.drawRoundRect(scratch, dp(20f), dp(20f), stroke)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && !ended) {
            when {
                backRect.contains(event.x, event.y) -> listener.onBackTapped()
                yesRect.contains(event.x, event.y) -> listener.onWordTapped(Answer.YES)
                noRect.contains(event.x, event.y) -> listener.onWordTapped(Answer.NO)
            }
        }
        return true
    }

    /** The baseline that centres the current text size on [centerY]. */
    private fun baseline(centerY: Float) = centerY - (text.descent() + text.ascent()) / 2f

    private fun color(id: Int) = ContextCompat.getColor(context, id)
    private fun dp(value: Float) = value * resources.displayMetrics.density
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
}
