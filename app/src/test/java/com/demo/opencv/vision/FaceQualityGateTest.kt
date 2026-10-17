package com.demo.opencv.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class FaceQualityGateTest {

    /** A face that passes every check. */
    private val good = FaceQuality(
        faceWidthPx = 200f, sharpness = 900f, faceLuma = 120f, frameLuma = 110f, clippedFraction = 0.01f,
        yawDeg = 5f, pitchDeg = -4f, rollDeg = 2f, faceScore = 0.9f,
    )

    @Test
    fun goodFaceHasNoIssues() {
        assertTrue(FaceQualityGate.issuesOf(good).isEmpty())
    }

    @Test
    fun eachMeasurementRaisesItsOwnIssue() {
        val cases = mapOf(
            good.copy(yawDeg = -40f) to QualityIssue.TURNED_AWAY,
            good.copy(faceWidthPx = 60f) to QualityIssue.TOO_FAR,
            good.copy(faceLuma = 30f, frameLuma = 30f) to QualityIssue.TOO_DARK,
            good.copy(faceLuma = 70f, frameLuma = 170f) to QualityIssue.BACKLIT,
            good.copy(clippedFraction = 0.3f) to QualityIssue.OVEREXPOSED,
            good.copy(sharpness = 80f) to QualityIssue.BLURRY,
            good.copy(pitchDeg = 38f) to QualityIssue.TILTED,
        )
        for ((quality, issue) in cases) assertEquals(setOf(issue), FaceQualityGate.issuesOf(quality))
    }

    @Test
    fun aBrightBackgroundBehindAWellLitFaceIsNotBacklit() {
        assertTrue(FaceQualityGate.issuesOf(good.copy(faceLuma = 130f, frameLuma = 200f)).isEmpty())
    }

    @Test
    fun unmeasuredValuesNeverRaiseIssues() {
        // A face found by ML Kit has no YuNet pose; a missing reading must not lock anyone out
        assertTrue(FaceQualityGate.issuesOf(FaceQuality()).isEmpty())
        assertTrue(FaceQualityGate.issuesOf(good.copy(yawDeg = Float.NaN, pitchDeg = Float.NaN)).isEmpty())
    }

    @Test
    fun anIssueIsHeldBeforeItIsReported() {
        val gate = FaceQualityGate(holdMs = 400)
        val dark = good.copy(faceLuma = 30f, frameLuma = 30f)
        gate.update(dark, 0)
        assertEquals(setOf(QualityIssue.TOO_DARK), gate.current)
        assertTrue(gate.active.isEmpty())
        assertFalse(gate.okForCalibration) // calibration uses the raw frame, no hold
        gate.update(dark, 399)
        assertTrue(gate.active.isEmpty())
        gate.update(dark, 400)
        assertEquals(setOf(QualityIssue.TOO_DARK), gate.active)
        assertEquals(QualityIssue.TOO_DARK, gate.primary)
    }

    @Test
    fun oneGoodFrameRestartsTheHold() {
        val gate = FaceQualityGate(holdMs = 400)
        val blurry = good.copy(sharpness = 50f)
        gate.update(blurry, 0)
        gate.update(good, 200)
        gate.update(blurry, 300)
        gate.update(blurry, 600)
        assertTrue(gate.active.isEmpty())
        gate.update(blurry, 700)
        assertEquals(setOf(QualityIssue.BLURRY), gate.active)
    }

    @Test
    fun recoveryIsImmediate() {
        val gate = FaceQualityGate(holdMs = 400)
        val turned = good.copy(yawDeg = 50f)
        gate.update(turned, 0)
        gate.update(turned, 500)
        assertTrue(gate.blocksInput)
        gate.update(good, 520)
        assertFalse(gate.blocksInput)
        assertNull(gate.primary)
        assertTrue(gate.okForCalibration)
    }

    @Test
    fun onlyTurningAwayBlocksInput() {
        // A dim room or a soft image is a reason to fix the setup, never a reason to silence the patient
        val blocking = QualityIssue.values().filter { it.blocksInput }
        assertEquals(listOf(QualityIssue.TURNED_AWAY), blocking)
        val gate = FaceQualityGate(holdMs = 0)
        gate.update(good.copy(faceLuma = 20f, frameLuma = 20f, sharpness = 10f, faceWidthPx = 40f), 0)
        assertEquals(3, gate.active.size)
        assertFalse(gate.blocksInput)
    }

    @Test
    fun primaryIssueFollowsTheFixOrder() {
        val gate = FaceQualityGate(holdMs = 0)
        gate.update(good.copy(sharpness = 10f, faceWidthPx = 40f, yawDeg = 45f), 0)
        assertEquals(QualityIssue.TURNED_AWAY, gate.primary)
        gate.update(good.copy(sharpness = 10f, faceWidthPx = 40f), 1)
        assertEquals(QualityIssue.TOO_FAR, gate.primary)
    }

    @Test
    fun noFaceClearsEverything() {
        val gate = FaceQualityGate(holdMs = 0)
        gate.update(good.copy(yawDeg = 60f), 0)
        assertTrue(gate.blocksInput)
        gate.update(null, 1)
        assertTrue(gate.active.isEmpty())
        assertTrue(gate.current.isEmpty())
    }

    @Test
    fun resetForgetsHeldIssues() {
        val gate = FaceQualityGate(holdMs = 400)
        val dark = good.copy(faceLuma = 30f, frameLuma = 30f)
        gate.update(dark, 0)
        gate.reset()
        gate.update(dark, 500)
        assertTrue(gate.active.isEmpty()) // the hold started again at 500
    }

    @Test
    fun eulerAnglesOfTheIdentityAreZero() {
        val angles = HeadPose.eulerDegrees(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        for (a in angles) assertEquals(0.0, a, 1e-9)
    }

    @Test
    fun eulerAnglesRecoverASingleRotation() {
        val t = Math.toRadians(25.0)
        val c = cos(t)
        val s = sin(t)
        // Rotation about y (yaw), about x (pitch) and about z (roll), each on its own
        val yaw = HeadPose.eulerDegrees(doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c))
        assertEquals(25.0, yaw[0], 1e-9); assertEquals(0.0, yaw[1], 1e-9); assertEquals(0.0, yaw[2], 1e-9)
        val pitch = HeadPose.eulerDegrees(doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c))
        assertEquals(0.0, pitch[0], 1e-9); assertEquals(25.0, pitch[1], 1e-9); assertEquals(0.0, pitch[2], 1e-9)
        val roll = HeadPose.eulerDegrees(doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0))
        assertEquals(0.0, roll[0], 1e-9); assertEquals(0.0, roll[1], 1e-9); assertEquals(25.0, roll[2], 1e-9)
    }
}
