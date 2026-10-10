package com.demo.opencv.vision

/**
 * Checks an eye image before it is stored as a calibration template, using the same measure the
 * gaze classifier uses (mean squared difference after removing each image's mean brightness).
 *
 * The iris-position gate alone is not enough: the iris is hard to locate when the eye looks up or
 * into a corner, so a step could be captured while the user was still looking straight ahead. A
 * template that looks like an earlier one makes that gaze impossible to tell apart, so it is
 * rejected here instead, however the step was triggered.
 *
 * Images are grey pixels in 0..1, all of the same size.
 */
object CalibrationTemplateCheck {

    /** Two templates closer than this (average of both eyes) cannot be told apart reliably. */
    const val MIN_TEMPLATE_DISTANCE = 0.003f

    /** A frame further than this from the frame before it is a blink or a jumped eye crop. */
    const val MAX_FRAME_CHANGE = 0.005f

    @JvmStatic
    fun distance(a: FloatArray?, b: FloatArray?): Float {
        if (a == null || b == null || a.isEmpty() || a.size != b.size) return Float.POSITIVE_INFINITY
        val meanA = a.average().toFloat()
        val meanB = b.average().toFloat()
        var sum = 0.0
        for (i in a.indices) {
            val d = (a[i] - meanA) - (b[i] - meanB)
            sum += d * d
        }
        return (sum / a.size).toFloat()
    }

    /** Average distance of the two eyes; an eye with nothing to compare against is ignored. */
    @JvmStatic
    fun pairDistance(leftA: FloatArray?, rightA: FloatArray?, leftB: FloatArray?, rightB: FloatArray?): Float {
        val left = distance(leftA, leftB)
        val right = distance(rightA, rightB)
        return when {
            left.isInfinite() -> right
            right.isInfinite() -> left
            else -> (left + right) / 2f
        }
    }

    /**
     * Index of the earlier step this candidate is too similar to, or -1 when it is distinct from
     * all of them. Steps not captured yet (null) are skipped.
     */
    @JvmStatic
    fun indistinctFrom(
        left: FloatArray?, right: FloatArray?,
        earlierLeft: Array<FloatArray?>, earlierRight: Array<FloatArray?>, steps: Int
    ): Int {
        for (i in 0 until minOf(steps, earlierLeft.size, earlierRight.size)) {
            if (earlierLeft[i] == null && earlierRight[i] == null) continue
            if (pairDistance(left, right, earlierLeft[i], earlierRight[i]) < MIN_TEMPLATE_DISTANCE) return i
        }
        return -1
    }

    /** True when the eye looked the same in the previous frame, i.e. this frame is not a blink or a glitch. */
    @JvmStatic
    fun isSettled(left: FloatArray?, right: FloatArray?, previousLeft: FloatArray?, previousRight: FloatArray?): Boolean =
        pairDistance(left, right, previousLeft, previousRight) < MAX_FRAME_CHANGE
}
