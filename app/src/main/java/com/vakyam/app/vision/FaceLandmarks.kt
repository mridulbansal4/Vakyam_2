package com.vakyam.app.vision

/**
 * One face's landmarks for one camera frame, decoupled from MediaPipe types so the gaze maths can
 * be unit-tested on the JVM.
 *
 * [x] and [y] are normalised to [0, 1] by the width/height of the *upright* (already rotated)
 * image. Because the image is not square, geometry must be done in pixels: use [px] / [py].
 */
class FaceLandmarks(
    val x: FloatArray,
    val y: FloatArray,
    val imageWidth: Int,
    val imageHeight: Int,
    val timestampMs: Long,
) {
    val size: Int get() = x.size

    fun px(index: Int): Float = x[index] * imageWidth
    fun py(index: Int): Float = y[index] * imageHeight
}
