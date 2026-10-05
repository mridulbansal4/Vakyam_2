package com.vakyam.app.ui

import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vakyam.app.calibration.CalibrationPhase
import com.vakyam.app.calibration.CalibrationStep
import com.vakyam.app.ui.theme.VakyamColors

/**
 * Screen for both calibration states: the countdown prompt (CALIBRATION_REQUIRED) and the moving
 * target dot (CALIBRATING_5_POINT). It must fill the Compose root with no padding, because target
 * positions are in root-window pixels.
 */
@Composable
fun CalibrationScreen(
    prompt: CalibrationPrompt?,
    step: CalibrationStep.InProgress?,
    face: FaceStatus,
    onCalibrateNow: () -> Unit,
    onUseSaved: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(VakyamColors.Background)) {
        if (step != null) {
            CalibrationTargets(step = step, face = face)
        } else {
            CalibrationPromptContent(prompt, face, onCalibrateNow, onUseSaved, onSkip)
        }
    }
}

@Composable
private fun CalibrationPromptContent(
    prompt: CalibrationPrompt?,
    face: FaceStatus,
    onCalibrateNow: () -> Unit,
    onUseSaved: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Eye calibration", color = VakyamColors.OnDark, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (prompt?.resumingSaved == true) {
                "Using your saved calibration."
            } else {
                "Step 1: line up your head and check that your eyes are detected, below.\n" +
                    "Step 2: a yellow dot will appear in 5 places. Keep your head still and follow it with your eyes."
            },
            color = VakyamColors.OnDarkMuted,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
            HeadAlignmentGuide(face)
            EyeTrackingIndicator(face, modifier = Modifier.width(150.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = prompt?.secondsLeft?.toString() ?: "…",
            color = VakyamColors.Amber,
            fontSize = 80.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = when {
                prompt == null -> "Preparing…"
                prompt.resumingSaved -> "Opening the board"
                face.detected -> "Calibration starts by itself"
                else -> "Waiting until your face is in view"
            },
            color = VakyamColors.OnDarkMuted,
            fontSize = 16.sp,
        )
        Spacer(Modifier.height(24.dp))
        // Touch controls are for a caregiver; the user's own path is the automatic countdown.
        Button(onClick = onCalibrateNow, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Calibrate now", fontSize = 18.sp)
        }
        if (prompt?.savedAvailable == true) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onUseSaved, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Use saved calibration", fontSize = 18.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Skip to Board (Use Default Calibration)", fontSize = 16.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Tip: on the board, close your eyes for 2 seconds and open them to calibrate again.",
            color = VakyamColors.OnDarkMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}

private enum class HeadPlacement(val hint: String) {
    NONE("No face detected"),
    TOO_FAR("Move the phone closer"),
    TOO_CLOSE("Move the phone further away"),
    OFF_CENTRE("Centre your head in the outline"),
    GOOD("Head position is good"),
}

private fun headPlacement(face: FaceStatus): HeadPlacement = when {
    !face.detected -> HeadPlacement.NONE
    face.eyeSpan < MIN_EYE_SPAN -> HeadPlacement.TOO_FAR
    face.eyeSpan > MAX_EYE_SPAN -> HeadPlacement.TOO_CLOSE
    face.centerX !in CENTERED || face.centerY !in CENTERED -> HeadPlacement.OFF_CENTRE
    else -> HeadPlacement.GOOD
}

/**
 * Shows where the user's *head* is in the camera's view, as a head outline moving inside a frame
 * with a dashed target outline to line it up with. It deliberately looks nothing like a pointer:
 * it is about positioning the head and does not respond to eye movement.
 */
@Composable
private fun HeadAlignmentGuide(face: FaceStatus, modifier: Modifier = Modifier) {
    val placement = headPlacement(face)
    val color = if (placement == HeadPlacement.GOOD) VakyamColors.Green else VakyamColors.Amber

    Column(modifier = modifier.width(130.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Head alignment", color = VakyamColors.OnDark, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.size(width = 120.dp, height = 150.dp)) {
            val stroke = 2.dp.toPx()
            drawRoundRect(
                color = VakyamColors.Outline,
                cornerRadius = CornerRadius(16.dp.toPx()),
                style = Stroke(width = stroke),
            )
            // Where the head should be.
            val targetWidth = size.width * TARGET_HEAD_WIDTH
            drawHeadOutline(
                center = Offset(size.width / 2f, size.height / 2f),
                headWidth = targetWidth,
                color = VakyamColors.Outline,
                stroke = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))),
            )
            // Where the head is. The camera image is not mirrored, so x is flipped to make the
            // guide behave like a mirror.
            if (face.detected) {
                val headWidth = (size.width * face.eyeSpan * HEAD_WIDTH_PER_EYE_SPAN)
                    .coerceIn(size.width * 0.15f, size.width * 0.95f)
                drawHeadOutline(
                    center = Offset(
                        x = size.width * (1f - face.centerX).coerceIn(0f, 1f),
                        // centerY is the eye line, which sits a little above the middle of the head.
                        y = size.height * face.centerY.coerceIn(0f, 1f) + headWidth * 0.15f,
                    ),
                    headWidth = headWidth,
                    color = color,
                    stroke = Stroke(width = 3.dp.toPx()),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(placement.hint, color = color, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

/** A head-and-shoulders outline: an oval for the head and an arc for the shoulders. */
private fun DrawScope.drawHeadOutline(center: Offset, headWidth: Float, color: Color, stroke: Stroke) {
    val headHeight = headWidth * 1.3f
    drawOval(
        color = color,
        topLeft = Offset(center.x - headWidth / 2f, center.y - headHeight / 2f),
        size = Size(headWidth, headHeight),
        style = stroke,
    )
    val shoulderWidth = headWidth * 1.9f
    drawArc(
        color = color,
        startAngle = 180f,
        sweepAngle = 180f,
        useCenter = false,
        topLeft = Offset(center.x - shoulderWidth / 2f, center.y + headHeight / 2f + headWidth * 0.08f),
        size = Size(shoulderWidth, headWidth * 1.1f),
        style = stroke,
    )
}

/**
 * Live confirmation that the irises are being tracked: two drawn eyes that turn green while the
 * eyes are detected and open, with pupils that follow the user's real iris movement. Unlike the
 * head guide, this one moves when only the eyes move.
 */
@Composable
private fun EyeTrackingIndicator(face: FaceStatus, modifier: Modifier = Modifier) {
    val (label, color) = when {
        !face.detected -> "Eyes not detected" to VakyamColors.Outline
        face.eyesClosed -> "Eyes closed" to VakyamColors.Amber
        else -> "Eyes detected – tracking active" to VakyamColors.Green
    }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Eye tracking", color = VakyamColors.OnDark, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.size(width = 140.dp, height = 60.dp)) {
            val eyeWidth = size.width * 0.44f
            val eyeHeight = if (face.eyesTracked) eyeWidth * 0.55f else eyeWidth * 0.12f
            val pupilRadius = eyeWidth * 0.16f
            // Offsets are tiny (about ±0.05 across the screen), so they are amplified to be
            // visible; x is flipped so the drawn eyes move like a mirror image.
            val travelX = (eyeWidth / 2f - pupilRadius) * (-face.irisDx / IRIS_DX_RANGE).coerceIn(-1f, 1f)
            val travelY = (eyeHeight / 2f - pupilRadius).coerceAtLeast(0f) *
                (face.irisDy / IRIS_DY_RANGE).coerceIn(-1f, 1f)

            for (eyeCenterX in listOf(size.width * 0.25f, size.width * 0.75f)) {
                val eyeCenter = Offset(eyeCenterX, size.height / 2f)
                drawOval(
                    color = color,
                    topLeft = Offset(eyeCenter.x - eyeWidth / 2f, eyeCenter.y - eyeHeight / 2f),
                    size = Size(eyeWidth, eyeHeight),
                    style = Stroke(width = 3.dp.toPx()),
                )
                if (face.eyesTracked) {
                    drawCircle(color = color, radius = pupilRadius, center = eyeCenter + Offset(travelX, travelY))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = color, fontSize = 14.sp, textAlign = TextAlign.Center)
        if (face.eyesTracked) {
            Text(
                "The pupils should follow your eyes",
                color = VakyamColors.OnDarkMuted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CalibrationTargets(step: CalibrationStep.InProgress, face: FaceStatus) {
    val center by animateOffsetAsState(
        targetValue = Offset(step.targetX, step.targetY),
        animationSpec = tween(durationMillis = 350),
        label = "calibrationTarget",
    )
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val maxRadius = 44.dp.toPx()
            val dotRadius = 10.dp.toPx()
            when (step.phase) {
                // A ring closing in on the dot draws the eye to it and counts the settle time down.
                CalibrationPhase.SETTLING -> drawCircle(
                    color = VakyamColors.OnDark,
                    radius = dotRadius + (maxRadius - dotRadius) * (1f - step.phaseProgress),
                    center = center,
                    style = Stroke(width = 3.dp.toPx()),
                )

                CalibrationPhase.COLLECTING -> {
                    val radius = dotRadius * 2.2f
                    drawArc(
                        color = VakyamColors.Green,
                        startAngle = -90f,
                        sweepAngle = 360f * step.phaseProgress,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
            drawCircle(color = VakyamColors.Amber, radius = dotRadius, center = center)
            drawCircle(color = VakyamColors.Ink, radius = dotRadius * 0.3f, center = center)
        }
        // Kept clear of all five targets (centre and the four corners).
        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(top = 200.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = when {
                    !face.detected -> "Face not visible – waiting"
                    face.eyesClosed -> "Open your eyes and look at the yellow dot"
                    else -> "Look at the yellow dot"
                },
                color = if (face.eyesTracked) VakyamColors.OnDarkMuted else VakyamColors.Amber,
                fontSize = 20.sp,
            )
            Text(
                text = "${step.pointIndex + 1} / ${step.pointCount}",
                color = VakyamColors.OnDarkMuted,
                fontSize = 16.sp,
            )
        }
    }
}

private const val MIN_EYE_SPAN = 0.12f
private const val MAX_EYE_SPAN = 0.45f
private val CENTERED = 0.25f..0.75f

/** Head width is roughly this many times the distance between the outer eye corners. */
private const val HEAD_WIDTH_PER_EYE_SPAN = 1.6f
private const val TARGET_HEAD_WIDTH = 0.42f

/** Iris offsets that move the drawn pupils to the edge of the drawn eye. */
private const val IRIS_DX_RANGE = 0.08f
private const val IRIS_DY_RANGE = 0.12f
