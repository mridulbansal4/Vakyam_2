package com.demo.opencv.vision

/**
 * Which eye-classification pipeline runs.
 *
 * - [CLASSIC] is the original path: ML Kit finds the face and eyes, eye crops are axis-aligned, and templates
 *   are compared with a hand-written mean-removed squared error. It is kept so the OpenCV 5 stages can be
 *   benchmarked against it on the same phone.
 * - [OPENCV5] adds YuNet face detection, roll-aligned CLAHE eye crops, `matchTemplate` scoring and the face
 *   quality gate.
 *
 * The two produce different eye crops, so each keeps its own calibration images.
 */
enum class VisionPipeline(val settingValue: String, val label: String) {
    CLASSIC("classic", "Classic"),
    OPENCV5("opencv5", "OpenCV 5");

    val usesOpenCv5Stages: Boolean get() = this == OPENCV5

    /** Calibration image file for this pipeline. Classic keeps the original names, so existing calibrations still load. */
    fun calibrationFileName(base: String): String = if (this == CLASSIC) base else "${settingValue}_$base"

    /**
     * The largest template error that still counts as a match, for the sensitivity setting (1-255, default 20).
     * Classic errors are mean-removed squared errors (~0.005-0.05); OpenCV 5 errors are 1 - normalised
     * cross-correlation (0 = identical, 1 = unrelated), so the same setting is scaled differently.
     */
    fun matchThreshold(sensitivitySetting: Int): Float {
        val setting = if (sensitivitySetting > 0) sensitivitySetting else DEFAULT_SENSITIVITY
        return if (usesOpenCv5Stages) minOf(setting / 50f, 2f) else setting / 1000f
    }

    companion object {
        /** Classic until the OpenCV 5 thresholds have been tuned on a phone. */
        @JvmField val DEFAULT = CLASSIC
        const val DEFAULT_SENSITIVITY = 20

        @JvmStatic
        fun fromSetting(value: String?): VisionPipeline =
            values().firstOrNull { it.settingValue == value?.trim()?.lowercase() } ?: DEFAULT
    }
}
