package com.vakyam.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vakyam.app.aac.AacTile
import com.vakyam.app.aac.TileStyle
import com.vakyam.app.dwell.DwellState
import com.vakyam.app.dwell.GazeRect
import com.vakyam.app.ui.theme.VakyamColors
import kotlin.math.min

private class TileColors(val container: Color, val content: Color, val border: Color, val ring: Color)

private fun colorsFor(style: TileStyle): TileColors = when (style) {
    TileStyle.QUICK_NEED -> TileColors(VakyamColors.Amber, VakyamColors.Ink, VakyamColors.Amber, VakyamColors.Ink)
    TileStyle.CATEGORY -> TileColors(VakyamColors.CategoryContainer, VakyamColors.OnDark, VakyamColors.Cyan, VakyamColors.Cyan)
    TileStyle.KEYWORD, TileStyle.LETTER ->
        TileColors(VakyamColors.KeywordContainer, VakyamColors.OnDark, VakyamColors.OnDark, VakyamColors.Cyan)
    TileStyle.NAVIGATION -> TileColors(VakyamColors.NavigationContainer, VakyamColors.Green, VakyamColors.Green, VakyamColors.Cyan)
    TileStyle.ACTION -> TileColors(VakyamColors.ActionContainer, VakyamColors.OnDark, VakyamColors.Outline, VakyamColors.Cyan)
    TileStyle.PRIMARY_ACTION -> TileColors(VakyamColors.Green, VakyamColors.Ink, VakyamColors.Green, VakyamColors.Ink)
}

/**
 * A large AAC tile that can be selected by dwelling on it (or by touch, for caregivers).
 *
 * The tile reports its bounds in root coordinates through [onBounds] so the ViewModel can register
 * it as a dwell target, and draws a circular timer that fills while the gaze rests on it. [dwell]
 * is read only in the draw phase, so the ~30 progress updates per second repaint this tile without
 * recomposing anything.
 */
@Composable
fun GazeTile(
    tile: AacTile,
    dwell: State<DwellState>,
    onBounds: (id: String, bounds: GazeRect) -> Unit,
    onRemoved: (id: String) -> Unit,
    onClick: (id: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = remember(tile.style) { colorsFor(tile.style) }
    val shape = RoundedCornerShape(20.dp)
    var bounds by remember { mutableStateOf<GazeRect?>(null) }
    val currentOnBounds by rememberUpdatedState(onBounds)
    val currentOnRemoved by rememberUpdatedState(onRemoved)

    // Re-register when the tile is enabled or disabled as well as when it moves: the ViewModel
    // only keeps enabled tiles as dwell targets.
    LaunchedEffect(tile.id, tile.enabled, bounds) {
        bounds?.let { currentOnBounds(tile.id, it) }
    }
    DisposableEffect(tile.id) {
        onDispose { currentOnRemoved(tile.id) }
    }

    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                val rect = coordinates.boundsInRoot()
                val updated = GazeRect(rect.left, rect.top, rect.right, rect.bottom)
                if (updated != bounds) bounds = updated
            }
            .alpha(if (tile.enabled) 1f else 0.3f)
            .clip(shape)
            .background(colors.container)
            .border(3.dp, colors.border, shape)
            .clickable(enabled = tile.enabled) { onClick(tile.id) }
            .drawWithContent {
                drawContent()
                val state = dwell.value
                if (state.targetId == tile.id && state.progress > 0f) {
                    val strokeWidth = 10.dp.toPx()
                    val diameter = min(size.width, size.height) - strokeWidth * 2.5f
                    drawArc(
                        color = colors.ring,
                        startAngle = -90f,
                        sweepAngle = 360f * state.progress,
                        useCenter = false,
                        topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f),
                        size = Size(diameter, diameter),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (tile.symbol.isNotEmpty()) {
                Text(text = tile.symbol, fontSize = 30.sp)
            }
            Text(
                text = tile.label,
                color = colors.content,
                fontSize = if (tile.style == TileStyle.LETTER) 36.sp else 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}
