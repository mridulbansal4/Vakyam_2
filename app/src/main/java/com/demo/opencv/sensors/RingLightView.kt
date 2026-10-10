package com.demo.opencv.sensors

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Soft white frame around the screen edge that lights the user's face for the front camera. */
class RingLightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val solid = Paint().apply { color = RING_COLOR }
    private val topFade = Paint()
    private val bottomFade = Paint()
    private val leftFade = Paint()
    private val rightFade = Paint()
    private var band = 0f
    private var soft = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        band = min(w, h) * BAND_FRACTION
        soft = band * SOFT_FRACTION
        val wf = w.toFloat()
        val hf = h.toFloat()
        topFade.shader = fade(0f, band, 0f, band + soft)
        bottomFade.shader = fade(0f, hf - band, 0f, hf - band - soft)
        leftFade.shader = fade(band, 0f, band + soft, 0f)
        rightFade.shader = fade(wf - band, 0f, wf - band - soft, 0f)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (band <= 0f) return
        canvas.drawRect(0f, 0f, w, band, solid)
        canvas.drawRect(0f, h - band, w, h, solid)
        canvas.drawRect(0f, band, band, h - band, solid)
        canvas.drawRect(w - band, band, w, h - band, solid)
        canvas.drawRect(band, band, w - band, band + soft, topFade)
        canvas.drawRect(band, h - band - soft, w - band, h - band, bottomFade)
        canvas.drawRect(band, band, band + soft, h - band, leftFade)
        canvas.drawRect(w - band - soft, band, w - band, h - band, rightFade)
    }

    private fun fade(x0: Float, y0: Float, x1: Float, y1: Float) =
        LinearGradient(x0, y0, x1, y1, RING_COLOR, RING_COLOR and 0x00FFFFFF, Shader.TileMode.CLAMP)

    companion object {
        private const val RING_COLOR = 0xFFFFFAF0.toInt() // soft white, not blue-white
        private const val BAND_FRACTION = 0.12f
        private const val SOFT_FRACTION = 0.6f
    }
}
