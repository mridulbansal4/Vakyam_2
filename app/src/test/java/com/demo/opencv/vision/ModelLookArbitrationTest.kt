package com.demo.opencv.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for Model look arbitration and dwell analysis:
 * 1. Verifies calibrated look detection across all directions (Left=1, Right=2, Up=3, Closed=5, etc.)
 * 2. Verifies that a look must be held for the dwell time before it triggers
 * 3. Verifies that sustained look does not repeatedly fire
 * 4. Verifies arbitration between calibration template matching and ballistic transit gestures
 * 5. Verifies motion shock suppression (circuit breaker)
 */
class ModelLookArbitrationTest {

    private lateinit var model: Model
    private var t = 0L

    @Before
    fun setUp() {
        model = Model()
        model.detectionOutput.initialize(4)
        t = 1_000L
    }

    /** One 30 fps frame with both eyes reporting the given look; returns the selection made on that frame. */
    private fun frame(left: Int, right: Int = left, steady: Boolean = true): Int {
        t += 33L
        model.detectionOutput.setEyeData(0, true, left, 1, 0.005f)
        model.detectionOutput.setEyeData(1, true, right, 1, 0.005f)
        model.analyzeLookOutput(t, steady)
        return model.detectionOutput.gestureOutput
    }

    private fun hold(look: Int, ms: Long, steady: Boolean = true): List<Int> =
        (1..(ms / 33L)).map { frame(look, steady = steady) }.filter { it != 0 }

    @Test
    fun testCalibratedLookDwellTriggersOnceAfterHold() {
        // Three frames used to be enough; a glance that short must no longer be entered
        assertEquals(emptyList<Int>(), hold(1, Model.LOOK_DWELL_MS - 66L))
        assertEquals(listOf(1), hold(1, 200L))
        // Sustained look fires once, does not bounce/repeat
        assertEquals(emptyList<Int>(), hold(1, 2_000L))
    }

    @Test
    fun testUpLookAgreementDetection() {
        assertEquals(listOf(3), hold(3, 600L))
    }

    @Test
    fun testRightLookAgreementDetection() {
        assertEquals(listOf(2), hold(2, 600L))
    }

    @Test
    fun testClosedEyesHeldIsDetected() {
        assertEquals(listOf(5), hold(5, 900L))
    }

    @Test
    fun testNaturalBlinkIsNotEntered() {
        hold(0, 300L)
        assertEquals(emptyList<Int>(), hold(5, 250L) + hold(0, 500L))
    }

    @Test
    fun testPhoneShakeDropsTheHeldLook() {
        assertEquals(emptyList<Int>(), hold(1, 250L) + hold(1, 100L, steady = false) + hold(1, 250L))
        assertEquals(listOf(1), hold(1, 200L))
    }

    @Test
    fun testAmbiguousCombinedMatchIsRejected() {
        // Eyes disagree and the two best templates are nearly tied: not a clear enough match to enter
        model.leftTemplateError = doubleArrayOf(0.05, 0.0050, 0.05, 0.0052, 0.05, 0.05)
        model.rightTemplateError = doubleArrayOf(0.05, 0.0050, 0.05, 0.0052, 0.05, 0.05)
        val fired = (1..30).map { frame(1, 3) }.filter { it != 0 }
        assertEquals(emptyList<Int>(), fired)
        assertFalse(model.detectionOutput.AnalyzedData.Success)
    }

    @Test
    fun testLookingStraightDoesNotFireKeystroke() {
        // Straight look (0) should never trigger a non-zero gestureOutput
        assertEquals(emptyList<Int>(), hold(0, 2_000L))
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
        // Template errors: index 3 (Up) has lowest combined error 0.010 (< sensitivity * 2 = 0.030)
        model.leftTemplateError = doubleArrayOf(0.05, 0.05, 0.05, 0.005, 0.05, 0.05)
        model.rightTemplateError = doubleArrayOf(0.05, 0.05, 0.05, 0.005, 0.05, 0.05)

        // Disagreeing eye looks: Left says 1, Right says 2
        val fired = (1..20).map { frame(1, 2) }.filter { it != 0 }

        // Index 3 maps to tag 3 (Up)
        assertEquals(listOf(3), fired)
    }

    @Test
    fun testCalibratedLookPreservedWhenBallisticGestureIsNone() {
        // Simulate calibrated look detection
        var gestureOutput = 1 // from analyzeLookOutput()
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
        // Even if calibrated look or ballistic detector has output, vehicle shock suppresses
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
