package com.demo.opencv.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionPipelineTest {

    @Test
    fun settingValuesRoundTrip() {
        for (p in VisionPipeline.values()) assertEquals(p, VisionPipeline.fromSetting(p.settingValue))
    }

    @Test
    fun unknownOrMissingSettingFallsBackToTheDefault() {
        assertEquals(VisionPipeline.DEFAULT, VisionPipeline.fromSetting(null))
        assertEquals(VisionPipeline.DEFAULT, VisionPipeline.fromSetting(""))
        assertEquals(VisionPipeline.DEFAULT, VisionPipeline.fromSetting("opencv6"))
    }

    @Test
    fun settingIsReadIgnoringCaseAndSpaces() {
        assertEquals(VisionPipeline.OPENCV5, VisionPipeline.fromSetting(" OpenCV5 "))
    }

    @Test
    fun classicKeepsTheOriginalCalibrationFileNames() {
        // Existing users must not lose their calibration when the app updates
        assertEquals("left_straight", VisionPipeline.CLASSIC.calibrationFileName("left_straight"))
    }

    @Test
    fun eachPipelineHasItsOwnCalibrationFiles() {
        val names = VisionPipeline.values().map { it.calibrationFileName("right_up") }.toSet()
        assertEquals(VisionPipeline.values().size, names.size)
        assertEquals("opencv5_right_up", VisionPipeline.OPENCV5.calibrationFileName("right_up"))
    }

    @Test
    fun onlyOpenCv5UsesTheNewStages() {
        assertTrue(VisionPipeline.OPENCV5.usesOpenCv5Stages)
        assertFalse(VisionPipeline.CLASSIC.usesOpenCv5Stages)
    }

    @Test
    fun classicThresholdIsTheOriginalMseScale() {
        assertEquals(0.020f, VisionPipeline.CLASSIC.matchThreshold(20), 1e-6f)
        assertEquals(0.150f, VisionPipeline.CLASSIC.matchThreshold(150), 1e-6f)
    }

    @Test
    fun openCv5ThresholdIsOneMinusCorrelation() {
        // Default sensitivity 20 accepts a normalised cross-correlation of 0.6 or better
        assertEquals(0.4f, VisionPipeline.OPENCV5.matchThreshold(20), 1e-6f)
        // 1 - correlation never exceeds 2, so the threshold is capped there
        assertEquals(2f, VisionPipeline.OPENCV5.matchThreshold(255), 1e-6f)
    }

    @Test
    fun unsetSensitivityUsesTheDefault() {
        for (p in VisionPipeline.values()) {
            assertEquals(p.matchThreshold(VisionPipeline.DEFAULT_SENSITIVITY), p.matchThreshold(0), 1e-6f)
        }
        assertNotEquals(VisionPipeline.CLASSIC.matchThreshold(0), VisionPipeline.OPENCV5.matchThreshold(0))
    }
}
