package com.vakyam.app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.vakyam.app.gaze.ScreenPoint
import com.vakyam.app.ui.theme.VakyamColors

private class OffsetHolder(var value: Offset)

/**
 * Full-screen overlay showing where the user is looking.
 *
 * Gaze samples arrive at camera rate (~30 Hz); the spring interpolates between them at display
 * rate so the pointer glides instead of stepping. This is cosmetic only: dwell detection uses the
 * unanimated gaze point. The overlay never intercepts touches.
 */
@Composable
fun GazePointer(point: ScreenPoint?, modifier: Modifier = Modifier) {
    // When tracking is lost the pointer fades out in place rather than flying to a corner.
    val lastKnown = remember { OffsetHolder(Offset.Zero) }
    if (point != null) lastKnown.value = Offset(point.x, point.y)

    val position by animateOffsetAsState(
        targetValue = lastKnown.value,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "gazePosition",
    )
    val alpha by animateFloatAsState(targetValue = if (point != null) 1f else 0f, label = "gazeAlpha")

    Canvas(modifier = modifier.fillMaxSize()) {
        if (alpha <= 0f) return@Canvas
        drawCircle(
            color = VakyamColors.Cyan.copy(alpha = 0.25f * alpha),
            radius = 26.dp.toPx(),
            center = position,
        )
        drawCircle(
            color = VakyamColors.Cyan.copy(alpha = alpha),
            radius = 26.dp.toPx(),
            center = position,
            style = Stroke(width = 3.dp.toPx()),
        )
        drawCircle(color = VakyamColors.OnDark.copy(alpha = alpha), radius = 5.dp.toPx(), center = position)
    }
}
