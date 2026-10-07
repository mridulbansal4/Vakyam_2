package com.demo.opencv.vision

import kotlin.math.hypot

/**
 * Geometric, scale-invariant eye ratio estimator.
 *
 * Instead of relying on raw pixel template matching (which fails under distance changes,
 * lighting gradients, and vibrations), this computes dimensionless normalized coordinate ratios
 * of the iris center relative to anatomical eye landmarks (canthi and eyelids).
 *
 * Mathematically:
 * - horizontalRatio = projection of (iris - innerCanthus) along eye corner baseline / baseline length
 * - verticalRatio = projection of (iris - upperLid) along vertical eyelid aperture / aperture height
 *
 * Since any distance change (e.g. handheld movement from 30cm to 50cm) scales the anatomical
 * bounding landmarks and iris displacement by the exact same focal magnification factor,
 * the resulting ratios are mathematically scale- and distance-invariant.
 */
class NormalizedIrisEstimator {

    data class Point2D(val x: Float, val y: Float)

    data class EyeLandmarks(
        val innerCanthus: Point2D,
        val outerCanthus: Point2D,
        val upperLid: Point2D,
        val lowerLid: Point2D,
        val irisCenter: Point2D
    )

    data class GazeMetrics(
        val horizontalRatio: Float, // 0.0 (far left relative to eye corners) to 1.0 (far right), ~0.5 is centered
        val verticalRatio: Float,   // 0.0 (looking up) to 1.0 (looking down), ~0.5 is centered
        val eyeOpenRatio: Float,    // aperture height / eye width (blink detection)
        val eyeWidth: Float,
        val eyeHeight: Float
    )

    /**
     * Extracts rotation-invariant anatomical landmarks from a continuous eye contour point list.
     *
     * 1. Canthi Detection: Finds the pair of contour points with the maximum Euclidean distance.
     *    Because the palpebral fissure has an aspect ratio of ~3:1 (width:height), the maximum
     *    chord across the contour boundary is mathematically guaranteed to be the line connecting
     *    the two canthi (eye corners), invariant to head tilt or camera rotation.
     * 2. Coordinate System: The corner with larger X (screen right, user's left) is designated as the
     *    baseline origin (ratio 0.0). The corner with smaller X (screen left, user's right) is designated
     *    as the baseline destination (ratio 1.0).
     *    This ensures looking right moves the iris toward ratio 1.0 and looking left moves toward 0.0.
     * 3. Rotation-Invariant Eyelid Detection: The baseline normal vector n = (-uy, ux) (pointing toward
     *    the forehead / upper lid) rotates synchronously with head roll tilt.
     *    The contour point maximizing projection along n is the upper eyelid peak.
     *    The contour point minimizing projection along n is the lower eyelid trough.
     */
    @JvmOverloads
    fun extractLandmarksFromContour(
        points: List<Point2D>,
        irisCenter: Point2D? = null
    ): EyeLandmarks {
        if (points.isEmpty()) {
            val fallback = irisCenter ?: Point2D(0f, 0f)
            return EyeLandmarks(fallback, fallback, fallback, fallback, fallback)
        }
        if (points.size < 4) {
            val p0 = points[0]
            val iris = irisCenter ?: p0
            return EyeLandmarks(p0, p0, p0, p0, iris)
        }

        // 1. Find the two points with maximum Euclidean distance -> canthi (eye corners)
        var maxDistSq = -1.0f
        var cornerA = points[0]
        var cornerB = points[0]

        val n = points.size
        for (i in 0 until n) {
            val pi = points[i]
            for (j in i + 1 until n) {
                val pj = points[j]
                val dx = pi.x - pj.x
                val dy = pi.y - pj.y
                val d2 = dx * dx + dy * dy
                if (d2 > maxDistSq) {
                    maxDistSq = d2
                    cornerA = pi
                    cornerB = pj
                }
            }
        }

        // Baseline origin (innerCanthus / screen right): larger X
        // Baseline destination (outerCanthus / screen left): smaller X
        val origin: Point2D
        val target: Point2D
        if (cornerA.x >= cornerB.x) {
            origin = cornerA
            target = cornerB
        } else {
            origin = cornerB
            target = cornerA
        }

        val bx = target.x - origin.x
        val by = target.y - origin.y
        val bLen = hypot(bx, by).coerceAtLeast(1e-4f)

        // Unit baseline vector
        val ux = bx / bLen
        val uy = by / bLen

        // Upward normal vector (perpendicular to baseline, pointing toward upper lid / forehead)
        // In image coordinates (+Y downwards): with ux < 0, n = (-uy, ux) points upwards
        val nx = -uy
        val ny = ux

        val midX = (origin.x + target.x) / 2.0f
        val midY = (origin.y + target.y) / 2.0f

        var maxProj = -Float.MAX_VALUE
        var minProj = Float.MAX_VALUE
        var upperLid = origin
        var lowerLid = origin

        for (p in points) {
            val px = p.x - midX
            val py = p.y - midY
            val proj = px * nx + py * ny
            if (proj > maxProj) {
                maxProj = proj
                upperLid = p
            }
            if (proj < minProj) {
                minProj = proj
                lowerLid = p
            }
        }

        val effectiveIris = irisCenter ?: Point2D(midX, midY)

        return EyeLandmarks(
            innerCanthus = origin,
            outerCanthus = target,
            upperLid = upperLid,
            lowerLid = lowerLid,
            irisCenter = effectiveIris
        )
    }

    fun computeMetrics(landmarks: EyeLandmarks): GazeMetrics {
        val dx = landmarks.outerCanthus.x - landmarks.innerCanthus.x
        val dy = landmarks.outerCanthus.y - landmarks.innerCanthus.y
        val eyeWidth = hypot(dx, dy).coerceAtLeast(1e-4f)

        val lhx = landmarks.lowerLid.x - landmarks.upperLid.x
        val lhy = landmarks.lowerLid.y - landmarks.upperLid.y
        val eyeHeight = hypot(lhx, lhy).coerceAtLeast(1e-4f)

        // Vector projection of iris onto the eye-corner baseline
        val rx = landmarks.irisCenter.x - landmarks.innerCanthus.x
        val ry = landmarks.irisCenter.y - landmarks.innerCanthus.y
        val dotHorizontal = rx * dx + ry * dy
        val hRatio = (dotHorizontal / (eyeWidth * eyeWidth)).coerceIn(0.0f, 1.0f)

        // Vector projection of iris onto the eyelid aperture axis
        val rvx = landmarks.irisCenter.x - landmarks.upperLid.x
        val rvy = landmarks.irisCenter.y - landmarks.upperLid.y
        val dotVertical = rvx * lhx + rvy * lhy
        val vRatio = (dotVertical / (eyeHeight * eyeHeight)).coerceIn(0.0f, 1.0f)

        val openRatio = eyeHeight / eyeWidth

        return GazeMetrics(
            horizontalRatio = hRatio,
            verticalRatio = vRatio,
            eyeOpenRatio = openRatio,
            eyeWidth = eyeWidth,
            eyeHeight = eyeHeight
        )
    }
}
