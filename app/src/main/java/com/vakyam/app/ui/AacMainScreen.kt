package com.vakyam.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vakyam.app.aac.AacTile
import com.vakyam.app.aac.AacUiState
import com.vakyam.app.calibration.CalibrationQuality
import com.vakyam.app.dwell.DwellState
import com.vakyam.app.dwell.GazeRect
import com.vakyam.app.llm.LlmState
import com.vakyam.app.state.AppState
import com.vakyam.app.ui.components.GazeTile
import com.vakyam.app.ui.theme.VakyamColors

/**
 * The AAC board: sentence bar on top, a two-column grid of large tiles, and a fixed action row.
 * The gaze pointer itself is drawn by `GazeApp` above every screen.
 */
@Composable
fun AacMainScreen(
    aac: AacUiState,
    sentence: SentenceState,
    appState: AppState,
    llmState: LlmState,
    face: FaceStatus,
    calibrationQuality: CalibrationQuality?,
    dwell: State<DwellState>,
    onTileBounds: (id: String, bounds: GazeRect) -> Unit,
    onTileRemoved: (id: String) -> Unit,
    onTileClick: (id: String) -> Unit,
    onRecalibrate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VakyamColors.Background)
            .safeDrawingPadding()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SentenceBar(aac, sentence)
        StatusRow(aac.title, appState, llmState, face, calibrationQuality, onRecalibrate)

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            aac.tiles.chunked(2).forEach { rowTiles ->
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowTiles.forEach { tile ->
                        TileCell(tile, dwell, onTileBounds, onTileRemoved, onTileClick, Modifier.weight(1f))
                    }
                    if (rowTiles.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        Row(modifier = Modifier.height(104.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            aac.actions.forEach { tile ->
                TileCell(tile, dwell, onTileBounds, onTileRemoved, onTileClick, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TileCell(
    tile: AacTile,
    dwell: State<DwellState>,
    onTileBounds: (id: String, bounds: GazeRect) -> Unit,
    onTileRemoved: (id: String) -> Unit,
    onTileClick: (id: String) -> Unit,
    modifier: Modifier,
) {
    key(tile.id) {
        GazeTile(
            tile = tile,
            dwell = dwell,
            onBounds = onTileBounds,
            onRemoved = onTileRemoved,
            onClick = onTileClick,
            modifier = modifier.fillMaxHeight(),
        )
    }
}

/** Shows the keywords chosen so far and, below them, the sentence Gemma made of them. */
@Composable
private fun SentenceBar(aac: AacUiState, sentence: SentenceState) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .background(VakyamColors.Surface, shape)
            .border(2.dp, VakyamColors.Outline, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val composing = buildList {
            addAll(aac.keywords)
            // The trailing underscore marks the word still being spelled.
            if (aac.letterBuffer.isNotEmpty()) add(aac.letterBuffer + "_")
        }
        Text(
            text = if (composing.isEmpty()) "Look at a tile to choose words" else composing.joinToString("  ·  "),
            color = if (composing.isEmpty()) VakyamColors.OnDarkMuted else VakyamColors.Cyan,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = when {
                sentence.text.isNotEmpty() -> sentence.text
                sentence.isStreaming -> "…"
                else -> ""
            },
            color = VakyamColors.Amber,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 30.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StatusRow(
    title: String,
    appState: AppState,
    llmState: LlmState,
    face: FaceStatus,
    calibrationQuality: CalibrationQuality?,
    onRecalibrate: () -> Unit,
) {
    // Most urgent first: what the app is doing, then what is wrong, then the page title.
    val (text, color) = when {
        appState == AppState.LLM_EXPANDING -> "Composing your sentence…" to VakyamColors.Amber
        appState == AppState.SPEAKING -> "Speaking…" to VakyamColors.Green
        !face.detected -> "Eyes not visible – check the phone position" to VakyamColors.Red
        // The board opens even when calibration could only be estimated; say so, with the remedy.
        calibrationQuality == CalibrationQuality.APPROXIMATE ->
            "Rough calibration – close eyes 2 s to redo" to VakyamColors.Amber
        calibrationQuality == CalibrationQuality.DEFAULT ->
            "Default calibration – close eyes 2 s to calibrate" to VakyamColors.Amber
        llmState is LlmState.Loading || llmState is LlmState.NotLoaded ->
            "Loading the language model…" to VakyamColors.OnDarkMuted
        llmState is LlmState.ModelMissing -> "Language model not installed – keywords are spoken as is" to VakyamColors.Amber
        llmState is LlmState.Failed -> "Language model failed – keywords are spoken as is" to VakyamColors.Amber
        else -> title to VakyamColors.OnDarkMuted
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = text,
            color = color,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Caregiver touch control; the user's own route is the eyes-closed gesture.
        TextButton(onClick = onRecalibrate, enabled = appState == AppState.GAZE_ACTIVE) {
            Text("Recalibrate", fontSize = 14.sp)
        }
    }
}
