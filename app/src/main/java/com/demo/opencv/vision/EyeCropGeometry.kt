package com.demo.opencv.vision

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where to cut one eye out of the frame for the OpenCV 5 pipeline, as a single similarity transform
 * (rotate + scale + shift) that `Imgproc.warpAffine` applies in one pass.
 *
 * The crop is anchored on the eyelid contour, not on the iris: the iris moves with the look, and a crop that
 * followed it would make every direction look the same. The crop is rotated by the head's roll so a tilted head
 * produces the same image as an upright one, and its scale comes from the eye's width alone, so opening or
 * narrowing the lids does not zoom the crop.
 *
 * [rollRad] is the angle of the line from the image-left eye to the image-right eye (image y points down).
 */
class EyeCropGeometry private constructor(
    val centerX: Double,
    val centerY: Double,
    val rollRad: Double,
    /** Output pixels per frame pixel. */
    val scale: Double,
    val outWidth: Int,
    val outHeight: Int,
) {
    private val cos = cos(rollRad)
    private val sin = sin(rollRad)

    /**
     * The 2x3 matrix (row-major a, b, tx, c, d, ty) mapping source pixels to crop pixels, for a source image whose
     * top-left corner sits at ([originX], [originY]) in the frame, such as a face region cut from it.
     */
    fun forwardMatrix(originX: Double = 0.0, originY: Double = 0.0): DoubleArray {
        val a = scale * cos
        val b = scale * sin
        val c = -scale * sin
        val d = scale * cos
        val sx = originX - centerX
        val sy = originY - centerY
        return doubleArrayOf(a, b, a * sx + b * sy + outWidth / 2.0, c, d, c * sx + d * sy + outHeight / 2.0)
    }

    /** Frame coordinates of crop pixel ([x], [y]); used to put the iris found in the crop back into the frame. */
    fun toFrame(x: Double, y: Double): DoubleArray {
        val dx = (x - outWidth / 2.0) / scale
        val dy = (y - outHeight / 2.0) / scale
        return doubleArrayOf(centerX + cos * dx - sin * dy, centerY + sin * dx + cos * dy)
    }

    /** Crop coordinates of frame point ([x], [y]). */
    fun toCrop(x: Double, y: Double): DoubleArray {
        val m = forwardMatrix()
        return doubleArrayOf(m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5])
    }

    companion object {
        /** Space kept round the eye on each side, as a share of its width (classic adds 3 px at ~60 px eye width). */
        const val PADDING = 0.15
        /** Eyes narrower than this in the frame carry too little detail to crop. */
        const val MIN_EYE_WIDTH_PX = 8.0
        /** Beyond this roll the frame-rotation search, not the crop, should fix the orientation. */
        val MAX_ROLL_RAD = Math.toRadians(45.0)

        /**
         * The crop for an eye contour given as interleaved frame coordinates (x0, y0, x1, y1, ...), or null when
         * the contour is too small, the roll is implausible, or the eye's centre lies outside the frame.
         */
        @JvmStatic
        fun fromContour(
            xy: FloatArray, rollRad: Double, outWidth: Int, outHeight: Int, frameWidth: Int, frameHeight: Int,
        ): EyeCropGeometry? {
            if (xy.size < 6 || xy.size % 2 != 0 || rollRad.isNaN() || kotlin.math.abs(rollRad) > MAX_ROLL_RAD) return null
            val cos = cos(rollRad)
            val sin = sin(rollRad)
            // Extents of the contour in the head's own axes (the frame rotated by -roll)
            var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE
            var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
            for (i in xy.indices step 2) {
                val x = xy[i].toDouble()
                val y = xy[i + 1].toDouble()
                val u = cos * x + sin * y
                val v = -sin * x + cos * y
                if (u < minU) minU = u; if (u > maxU) maxU = u
                if (v < minV) minV = v; if (v > maxV) maxV = v
            }
            val eyeWidth = maxU - minU
            if (eyeWidth < MIN_EYE_WIDTH_PX) return null
            val midU = (minU + maxU) / 2
            val midV = (minV + maxV) / 2
            val cx = cos * midU - sin * midV // back to frame axes
            val cy = sin * midU + cos * midV
            if (cx < 0 || cy < 0 || cx >= frameWidth || cy >= frameHeight) return null
            val cropWidth = eyeWidth * (1 + 2 * PADDING)
            return EyeCropGeometry(cx, cy, rollRad, outWidth / cropWidth, outWidth, outHeight)
        }

        /** Roll of the line between two eye centres, whichever order they are given in. */
        @JvmStatic
        fun rollFromEyeCenters(ax: Double, ay: Double, bx: Double, by: Double): Double =
            if (ax <= bx) atan2(by - ay, bx - ax) else atan2(ay - by, ax - bx)

        /** Mean of interleaved (x, y) points as [x, y], or null when empty. */
        @JvmStatic
        fun centroid(xy: FloatArray?): DoubleArray? {
            if (xy == null || xy.size < 2) return null
            var sx = 0.0; var sy = 0.0
            val n = xy.size / 2
            for (i in 0 until n) { sx += xy[2 * i]; sy += xy[2 * i + 1] }
            return doubleArrayOf(sx / n, sy / n)
        }
    }
}
