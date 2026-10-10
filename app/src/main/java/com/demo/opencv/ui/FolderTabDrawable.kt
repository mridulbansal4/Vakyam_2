package com.demo.opencv.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable

/**
 * Folder-style top bar tab: rounded top corners and concave feet that flare out at the bottom.
 * The selected tab takes the page colour so it reads as joined to the screen below it.
 * Neighbouring tabs overlap, so the text needs side padding wider than the part a neighbour covers.
 * The [edgeColor] outline (left open along the bottom) keeps overlapping tabs of the same colour apart.
 */
class FolderTabDrawable(
    private val selectedColor: Int,
    private val idleColor: Int,
    edgeColor: Int,
    edgeWidth: Float,
    private val flare: Float,
    private val radius: Float
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = idleColor }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = edgeColor
        style = Paint.Style.STROKE
        strokeWidth = edgeWidth
    }
    private val outline = Path()
    private val path = Path()

    override fun onBoundsChange(bounds: Rect) {
        val l = bounds.left.toFloat()
        val r = bounds.right.toFloat()
        val t = bounds.top.toFloat()
        val b = bounds.bottom.toFloat()
        val f = minOf(flare, (r - l) / 4f, (b - t) / 2f)
        val c = minOf(radius, (r - l) / 2f - f, b - t - f)
        outline.reset()
        outline.moveTo(l, b)
        outline.quadTo(l + f, b, l + f, b - f)
        outline.lineTo(l + f, t + c)
        outline.quadTo(l + f, t, l + f + c, t)
        outline.lineTo(r - f - c, t)
        outline.quadTo(r - f, t, r - f, t + c)
        outline.lineTo(r - f, b - f)
        outline.quadTo(r - f, b, r, b)
        path.set(outline)
        path.close()
    }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val color = if (state.contains(android.R.attr.state_selected)) selectedColor else idleColor
        if (paint.color == color) return false
        paint.color = color
        return true
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, paint)
        canvas.drawPath(outline, edge)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        edge.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        edge.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
