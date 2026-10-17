package com.demo.opencv.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class EyeCropGeometryTest {

    private val w = 44
    private val h = 18

    /** An almond-shaped eye contour: [width] wide, [height] tall, centred at (cx, cy), rotated by [roll]. */
    private fun eye(cx: Double, cy: Double, width: Double, height: Double, roll: Double = 0.0): FloatArray {
        val pts = ArrayList<Float>()
        for (i in 0 until 16) {
            val t = 2 * Math.PI * i / 16
            val u = width / 2 * cos(t)
            val v = height / 2 * sin(t)
            pts += (cx + u * cos(roll) - v * sin(roll)).toFloat()
            pts += (cy + u * sin(roll) + v * cos(roll)).toFloat()
        }
        return pts.toFloatArray()
    }

    private fun geometry(xy: FloatArray, roll: Double = 0.0): EyeCropGeometry {
        val g = EyeCropGeometry.fromContour(xy, roll, w, h, 640, 480)
        assertNotNull(g)
        return g!!
    }

    @Test
    fun uprightEyeIsCentredAndScaledToItsWidth() {
        val g = geometry(eye(300.0, 200.0, 60.0, 24.0))
        assertEquals(300.0, g.centerX, 1e-6)
        assertEquals(200.0, g.centerY, 1e-6)
        assertEquals(w / (60.0 * (1 + 2 * EyeCropGeometry.PADDING)), g.scale, 1e-9)
        val c = g.toCrop(300.0, 200.0)
        assertEquals(w / 2.0, c[0], 1e-9)
        assertEquals(h / 2.0, c[1], 1e-9)
    }

    @Test
    fun theEyeCornersLandInsideThePadding() {
        val g = geometry(eye(300.0, 200.0, 60.0, 24.0))
        val inner = g.toCrop(270.0, 200.0)
        val outer = g.toCrop(330.0, 200.0)
        val pad = w * EyeCropGeometry.PADDING / (1 + 2 * EyeCropGeometry.PADDING)
        assertEquals(pad, inner[0], 1e-9)
        assertEquals(w - pad, outer[0], 1e-9)
    }

    @Test
    fun openingTheLidsDoesNotZoomTheCrop() {
        // Scale comes from eye width alone; a wide-open eye looking up must not be magnified
        val narrow = geometry(eye(300.0, 200.0, 60.0, 14.0))
        val open = geometry(eye(300.0, 200.0, 60.0, 30.0))
        assertEquals(narrow.scale, open.scale, 1e-9)
    }

    @Test
    fun aTiltedHeadGivesTheSameCropAsAnUprightOne() {
        val roll = Math.toRadians(20.0)
        val upright = geometry(eye(300.0, 200.0, 60.0, 24.0))
        val tilted = geometry(eye(300.0, 200.0, 60.0, 24.0, roll), roll)
        // Contours are floats, as ML Kit's are, so agreement is to ~1e-5 px rather than double precision
        val tol = 1e-4
        assertEquals(upright.scale, tilted.scale, tol)
        // The point at the eye's outer corner, in the head's own axes, maps to the same crop pixel
        val cornerUpright = upright.toCrop(330.0, 200.0)
        val cornerTilted = tilted.toCrop(300.0 + 30 * cos(roll), 200.0 + 30 * sin(roll))
        assertEquals(cornerUpright[0], cornerTilted[0], tol)
        assertEquals(cornerUpright[1], cornerTilted[1], tol)
    }

    @Test
    fun toFrameUndoesToCrop() {
        val g = geometry(eye(310.0, 190.0, 55.0, 20.0, 0.3), 0.3)
        for ((x, y) in listOf(0.0 to 0.0, 22.0 to 9.0, 43.0 to 17.0, 10.5 to 3.25)) {
            val f = g.toFrame(x, y)
            val back = g.toCrop(f[0], f[1])
            assertEquals(x, back[0], 1e-9)
            assertEquals(y, back[1], 1e-9)
        }
    }

    @Test
    fun forwardMatrixAccountsForASubImageOrigin() {
        // warpAffine runs on the CLAHE face region, not the whole frame
        val g = geometry(eye(300.0, 200.0, 60.0, 24.0, 0.2), 0.2)
        val ox = 180.0
        val oy = 90.0
        val m = g.forwardMatrix(ox, oy)
        val local = doubleArrayOf(300.0 - ox, 200.0 - oy)
        val viaRegion = doubleArrayOf(
            m[0] * local[0] + m[1] * local[1] + m[2],
            m[3] * local[0] + m[4] * local[1] + m[5],
        )
        val viaFrame = g.toCrop(300.0, 200.0)
        assertEquals(viaFrame[0], viaRegion[0], 1e-9)
        assertEquals(viaFrame[1], viaRegion[1], 1e-9)
    }

    @Test
    fun rejectsContoursThatCannotBeCropped() {
        assertNull(EyeCropGeometry.fromContour(floatArrayOf(1f, 2f), 0.0, w, h, 640, 480)) // too few points
        assertNull(EyeCropGeometry.fromContour(floatArrayOf(1f, 2f, 3f, 4f, 5f), 0.0, w, h, 640, 480)) // odd length
        assertNull(EyeCropGeometry.fromContour(eye(300.0, 200.0, 5.0, 2.0), 0.0, w, h, 640, 480)) // too small
        assertNull(EyeCropGeometry.fromContour(eye(700.0, 200.0, 60.0, 24.0), 0.0, w, h, 640, 480)) // outside
        assertNull(EyeCropGeometry.fromContour(eye(300.0, 200.0, 60.0, 24.0), Math.toRadians(60.0), w, h, 640, 480))
        assertNull(EyeCropGeometry.fromContour(eye(300.0, 200.0, 60.0, 24.0), Double.NaN, w, h, 640, 480))
    }

    @Test
    fun rollIsTheSameWhicheverEyeComesFirst() {
        val a = EyeCropGeometry.rollFromEyeCenters(100.0, 200.0, 160.0, 210.0)
        val b = EyeCropGeometry.rollFromEyeCenters(160.0, 210.0, 100.0, 200.0)
        assertEquals(a, b, 1e-12)
        assertEquals(Math.atan2(10.0, 60.0), a, 1e-12)
        assertEquals(0.0, EyeCropGeometry.rollFromEyeCenters(100.0, 200.0, 160.0, 200.0), 1e-12)
    }

    @Test
    fun centroidOfInterleavedPoints() {
        val c = EyeCropGeometry.centroid(floatArrayOf(0f, 0f, 4f, 0f, 4f, 2f, 0f, 2f))!!
        assertEquals(2.0, c[0], 1e-9)
        assertEquals(1.0, c[1], 1e-9)
        assertNull(EyeCropGeometry.centroid(null))
        assertNull(EyeCropGeometry.centroid(FloatArray(0)))
    }
}
