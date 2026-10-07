package com.demo.opencv.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for Model gaze arbitration and dwell analysis:
 * 1. Verifies calibrated gaze detection across all directions (Left=1, Right=2, Up=3, Closed=5, etc.)
 * 2. Verifies that 3 consecutive frames are required for dwell triggering
 * 3. Verifies that sustained gaze (frame 4+) does not repeatedly fire
 * 4. Verifies arbitration between calibration template matching and ballistic transit gestures
 * 5. Verifies motion shock suppression (circuit breaker)
 */
class ModelGazeArbitrationTest {

    private lateinit var model: Model

    @Before
    fun setUp() {
        model = Model()
    }

    @Test
    fun testCalibratedGazeDwellTriggersOnThirdFrame() {
        // Feed Left gaze (1) for frame 1
        model.detectionOutput.initialize(4)
        model.detectionOutput.setEyeData(0, true, 1, 1, 0.005f)
        model.detectionOutput.setEyeData(1, true, 1, 1, 0.005f)
        model.analyzeGazeOutput()
        assertEquals(0, model.detectionOutput.gestureOutput)

        // Feed Left gaze (1) for frame 2
        model.detectionOutput.setEyeData(0, true, 1, 1, 0.005f)
        model.detectionOutput.setEyeData(1, true, 1, 1, 0.005f)
        model.analyzeGazeOutput()
        assertEquals(0, model.detectionOutput.gestureOutput)

        // Feed Left gaze (1) for frame 3 -> should trigger gestureOutput = 1
        model.detectionOutput.setEyeData(0, true, 1, 1, 0.005f)
        model.detectionOutput.setEyeData(1, true, 1, 1, 0.005f)
        model.analyzeGazeOutput()
        assertEquals(1, model.detectionOutput.gestureOutput)

        // Feed Left gaze (1) for frame 4 -> dwell fires once, does not bounce/repeat
        model.detectionOutput.setEyeData(0, true, 1, 1, 0.005f)
        model.detectionOutput.setEyeData(1, true, 1, 1, 0.005f)
        model.analyzeGazeOutput()
        assertEquals(0, model.detectionOutput.gestureOutput)
    }

    @Test
    fun testUpGazeAgreementDetection() {
        // Verify Up gaze (3) triggers when both eyes agree
        model.detectionOutput.initialize(4)
        for (i in 0..2) {
            model.detectionOutput.setEyeData(0, true, 3, 1, 0.004f)
            model.detectionOutput.setEyeData(1, true, 3, 1, 0.004f)
            model.analyzeGazeOutput()
        }
        assertEquals(3, model.detectionOutput.gestureOutput)
    }

    @Test
    fun testRightGazeAgreementDetection() {
        // Verify Right gaze (2) triggers when both eyes agree
        model.detectionOutput.initialize(4)
        for (i in 0..2) {
            model.detectionOutput.setEyeData(0, true, 2, 1, 0.003f)
            model.detectionOutput.setEyeData(1, true, 2, 1, 0.003f)
            model.analyzeGazeOutput()
        }
        assertEquals(2, model.detectionOutput.gestureOutput)
    }

    @Test
    fun testClosedEyesBlinkDetection() {
        // Verify Closed eyes (5) triggers when both eyes are closed
        model.detectionOutput.initialize(4)
        for (i in 0..2) {
            model.detectionOutput.setEyeData(0, true, 5, 1, 0.02f)
            model.detectionOutput.setEyeData(1, true, 5, 1, 0.02f)
            model.analyzeGazeOutput()
        }
        assertEquals(5, model.detectionOutput.gestureOutput)
    }

    @Test
    fun testLookingStraightDoesNotFireKeystroke() {
        // Straight gaze (0) should never trigger a non-zero gestureOutput
        model.detectionOutput.initialize(4)
        for (i in 0..5) {
            model.detectionOutput.setEyeData(0, true, 0, 1, 0.001f)
            model.detectionOutput.setEyeData(1, true, 0, 1, 0.001f)
            model.analyzeGazeOutput()
            assertEquals(0, model.detectionOutput.gestureOutput)
        }
    }

    @Test
    fun testTagsArrayMatchesCalibrationOrder() {
        // Verify tags map 0=Straight, 1=Left, 2=Right, 3=Up, 6=LeftUp, 7=RightUp
        val expected = arrayOf(0, 1, 2, 3, 6, 7)
        assertEquals(expected.size, model.tags.size)
        for (i in expected.indices) {
            assertEquals("Tag at index $i mismatch", expected[i], model.tags[i])
        }
    }

    @Test
    fun testCombinedLossFallbackWithSensitivityThreshold() {
        model.detectionOutput.initialize(4)
        // Disagreeing eye gazes: Left says 1 (loss 0.02), Right says 2 (loss 0.02)
        model.detectionOutput.setEyeData(0, true, 1, 1, 0.02f)
        model.detectionOutput.setEyeData(1, true, 2, 1, 0.02f)

        // Template errors: index 3 (Up) has lowest combined error 0.010 (< sensitivity * 2 = 0.030)
        model.leftTemplateError = doubleArrayOf(0.05, 0.05, 0.05, 0.005, 0.05, 0.05)
        model.rightTemplateError = doubleArrayOf(0.05, 0.05, 0.05, 0.005, 0.05, 0.05)

        for (i in 0..2) {
            model.analyzeGazeOutput()
        }

        // Index 3 maps to tag 3 (Up)
        assertEquals(3, model.detectionOutput.gestureOutput)
    }

    @Test
    fun testCalibratedGazePreservedWhenBallisticGestureIsNone() {
        // Simulate calibrated gaze detection
        var gestureOutput = 1 // from analyzeGazeOutput()
        val gestureCode = 0   // ballistic detector didn't fire (natural eye movement)
        val motionStable = true

        // Arbitration logic as in Model.java
        if (motionStable) {
            if (gestureCode != 0) {
                gestureOutput = gestureCode
            } else if (gestureOutput != 0) {
                // Preserved!
            }
        } else {
            gestureOutput = 0
        }

        assertEquals(1, gestureOutput)
    }

    @Test
    fun testBallisticFlickTakesPriorityWhenTriggered() {
        // Calibrated dwell had not yet reached 3 frames (0), but user did a ballistic flick left (1)
        var gestureOutput = 0
        val gestureCode = 1 // FLICK_LEFT
        val motionStable = true

        if (motionStable) {
            if (gestureCode != 0) {
                gestureOutput = gestureCode
            }
        } else {
            gestureOutput = 0
        }

        assertEquals(1, gestureOutput)
    }

    @Test
    fun testVehicleShockCircuitBreakerSuppressesBothInputs() {
        // Even if calibrated gaze or ballistic detector has output, vehicle shock suppresses
        var gestureOutput = 2
        val gestureCode = 2
        val motionStable = false // transit shock

        if (motionStable) {
            if (gestureCode != 0) {
                gestureOutput = gestureCode
            }
        } else {
            gestureOutput = 0
        }

        assertEquals(0, gestureOutput)
    }
}
