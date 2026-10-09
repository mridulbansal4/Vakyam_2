package com.demo.opencv.vision

import com.demo.opencv.vision.CalibrationStabilityGate.Direction
import com.demo.opencv.vision.CalibrationStabilityGate.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class CalibrationStabilityGateTest {

    private fun feed(
        gate: CalibrationStabilityGate,
        fromMs: Long,
        toMs: Long,
        eyesOpen: Boolean = true,
        motionStable: Boolean = true,
        eyesVisible: Boolean = true,
        sample: (Int) -> Triple<Float, Float, Float> // (x, y, phoneYaw) for frame i
    ): Int {
        var captures = 0
        var t = fromMs
        var i = 0
        while (t <= toMs) {
            val (x, y, yaw) = sample(i)
            val pitch = if (yaw.isNaN()) Float.NaN else 0f
            if (gate.update(x, y, eyesOpen, motionStable, eyesVisible, t, pitch, yaw)) captures++
            t += 33L
            i++
        }
        return captures
    }

    private fun feedSteady(
        gate: CalibrationStabilityGate, x: Float, y: Float, fromMs: Long, toMs: Long, motionStable: Boolean = true
    ) = feed(gate, fromMs, toMs, motionStable = motionStable) { Triple(x, y, Float.NaN) }

    @Test
    fun capturesOnceAfterSettleAndHold() {
        val gate = CalibrationStabilityGate()
        gate.startStep(0L)
        assertEquals(1, feedSteady(gate, 0.5f, 0.5f, 0L, 1600L))
    }

    @Test
    fun neverCapturesDuringSettlePeriod() {
        val gate = CalibrationStabilityGate()
        gate.startStep(0L)
        assertEquals(0, feedSteady(gate, 0.5f, 0.5f, 0L, 690L))
    }

    @Test
    fun jitteryGazeNeverCaptures() {
        val gate = CalibrationStabilityGate()
        gate.startStep(0L)
        assertEquals(0, feed(gate, 0L, 4000L) { i -> Triple(if (i % 2 == 0) 0.40f else 0.50f, 0.5f, Float.NaN) })
        assertEquals(Status.NOT_STEADY, gate.status)
    }

    @Test
    fun blinkOrLostEyesResetTheWindow() {
        for (blink in listOf(true, false)) {
            val gate = CalibrationStabilityGate()
            gate.startStep(0L)
            assertEquals(0, feedSteady(gate, 0.5f, 0.5f, 700L, 1100L))
            assertTrue(gate.progress(1100L) > 0f)

            if (blink) gate.update(0.5f, 0.5f, false, true, true, 1133L)
            else gate.update(Float.NaN, Float.NaN, true, true, false, 1133L)
            assertEquals(0f, gate.progress(1133L), 0f)

            assertEquals(0, feedSteady(gate, 0.5f, 0.5f, 1166L, 1700L))
            assertEquals(1, feedSteady(gate, 0.5f, 0.5f, 1733L, 2100L))
        }
    }

    @Test
    fun shortShakePausesWithoutDiscardingSamples() {
        val gate = CalibrationStabilityGate()
        gate.startStep(0L)
        feedSteady(gate, 0.5f, 0.5f, 700L, 1100L)
        feedSteady(gate, 0.5f, 0.5f, 1133L, 1300L, motionStable = false)
        assertEquals(Status.PHONE_MOVING, gate.status)
        assertEquals(0f, gate.progress(1300L), 0f)

        // Window is still full, so only the hold time is needed again
        assertEquals(0, feedSteady(gate, 0.5f, 0.5f, 1333L, 1700L))
        assertEquals(1, feedSteady(gate, 0.5f, 0.5f, 1733L, 1800L))
    }

    @Test
    fun longShakeDiscardsSamples() {
        val gate = CalibrationStabilityGate()
        gate.startStep(0L)
        feedSteady(gate, 0.5f, 0.5f, 700L, 1100L)
        feedSteady(gate, 0.5f, 0.5f, 1133L, 1900L, motionStable = false)

        assertEquals(0, feedSteady(gate, 0.5f, 0.5f, 1933L, 2500L))
        assertEquals(1, feedSteady(gate, 0.5f, 0.5f, 2533L, 2700L))
    }

    @Test
    fun requiresMovementInTheStepDirection() {
        val gate = CalibrationStabilityGate()
        gate.setReference(0.5f, 0.5f)

        gate.startStep(0L, Direction.LEFT)
        assertEquals(0, feedSteady(gate, 0.52f, 0.5f, 0L, 3000L))
        assertEquals(Status.TOO_CLOSE_TO_REFERENCE, gate.status)

        gate.startStep(3000L, Direction.LEFT)
        assertEquals(1, feedSteady(gate, 0.30f, 0.5f, 3000L, 4600L))

        gate.startStep(5000L, Direction.RIGHT)
        assertEquals(0, feedSteady(gate, 0.30f, 0.5f, 5000L, 8000L))

        gate.startStep(8000L, Direction.LEFT_UP)
        assertEquals(0, feedSteady(gate, 0.30f, 0.5f, 8000L, 11000L))
        gate.startStep(11000L, Direction.LEFT_UP)
        assertEquals(1, feedSteady(gate, 0.30f, 0.38f, 11000L, 12600L))
    }

    @Test
    fun sidewaysStepsIgnoreVerticalNoise() {
        val noisyY = { i: Int -> Triple(0.10f, if (i % 2 == 0) 0.30f else 0.80f, Float.NaN) }

        val left = CalibrationStabilityGate()
        left.setReference(0.45f, 0.5f)
        left.startStep(0L, Direction.LEFT)
        assertEquals(1, feed(left, 0L, 1600L, sample = noisyY))

        val straight = CalibrationStabilityGate()
        straight.startStep(0L, Direction.STRAIGHT)
        assertEquals(0, feed(straight, 0L, 4000L, sample = noisyY))
    }

    @Test
    fun oneOutlierFrameDoesNotBlock() {
        val gate = CalibrationStabilityGate()
        gate.startStep(0L)
        val captures = feed(gate, 0L, 1600L) { i -> Triple(if (i == 25) 0.9f else 0.5f, 0.5f, Float.NaN) }
        assertEquals(1, captures)
    }

    @Test
    fun escalationRelaxesDirectionThenDropsIt() {
        val gate = CalibrationStabilityGate()
        gate.setReference(0.5f, 0.5f)
        gate.startStep(0L, Direction.LEFT)
        assertEquals(0, feedSteady(gate, 0.46f, 0.5f, 0L, 3000L))

        gate.escalate()
        assertEquals(1, feedSteady(gate, 0.46f, 0.5f, 3033L, 3600L))

        gate.startStep(4000L, Direction.LEFT)
        assertEquals(0, gate.leniency)
        gate.escalate()
        assertEquals(0, feedSteady(gate, 0.51f, 0.5f, 4000L, 7000L))
        gate.escalate()
        assertEquals(1, feedSteady(gate, 0.51f, 0.5f, 7033L, 7600L))
    }

    @Test
    fun escalationToleratesNoisierGaze() {
        val gate = CalibrationStabilityGate()
        gate.startStep(0L)
        val jitter = { i: Int -> Triple(0.5f + if (i % 2 == 0) 0.06f else 0f, 0.5f, Float.NaN) }
        assertEquals(0, feed(gate, 0L, 3000L, sample = jitter))

        gate.escalate()
        gate.escalate()
        assertEquals(2, gate.leniency)
        assertEquals(1, feed(gate, 3033L, 3600L, sample = jitter))
    }

    @Test
    fun phoneRotationIsCompensatedWithGyroAngles() {
        // Eyes follow the screen as the hand-held phone wobbles: the gaze ratio moves with the yaw.
        val wobble = { i: Int ->
            val yaw = 0.05f * sin(i * 0.9f)
            Triple(0.5f + 1.0f * yaw, 0.5f, yaw)
        }
        val withGyro = CalibrationStabilityGate()
        withGyro.startStep(0L)
        assertEquals(1, feed(withGyro, 0L, 1600L, sample = wobble))
        assertTrue(withGyro.lastMotionCorrection > 0.02f)

        val withoutGyro = CalibrationStabilityGate()
        withoutGyro.startStep(0L)
        assertEquals(0, feed(withoutGyro, 0L, 3000L) { i -> wobble(i).copy(third = Float.NaN) })
    }

    @Test
    fun compensationCannotHideRealEyeMovement() {
        // Gaze jumps around while the phone is perfectly still: nothing to compensate.
        val gate = CalibrationStabilityGate()
        gate.startStep(0L)
        assertEquals(0, feed(gate, 0L, 3000L) { i -> Triple(if (i % 2 == 0) 0.40f else 0.50f, 0.5f, 0f) })
    }

    @Test
    fun reportsWhyItIsWaiting() {
        val gate = CalibrationStabilityGate()
        gate.startStep(0L)
        gate.update(0.5f, 0.5f, true, true, true, 100L)
        assertEquals(Status.SETTLING, gate.status)
        gate.update(Float.NaN, Float.NaN, true, true, false, 800L)
        assertEquals(Status.NO_EYES, gate.status)
        gate.update(0.5f, 0.5f, false, true, true, 833L)
        assertEquals(Status.EYES_CLOSED, gate.status)
        gate.update(0.5f, 0.5f, true, false, true, 866L)
        assertEquals(Status.PHONE_MOVING, gate.status)
    }

    @Test
    fun progressRisesToOneWhileHolding() {
        val gate = CalibrationStabilityGate()
        gate.startStep(0L)
        feedSteady(gate, 0.5f, 0.5f, 700L, 1100L)
        val p = gate.progress(1100L)
        assertTrue(p > 0f && p < 1f)
        assertFalse(gate.status == Status.CAPTURED)
    }
}
