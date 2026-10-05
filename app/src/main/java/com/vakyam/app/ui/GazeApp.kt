package com.vakyam.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.SoundEffectConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vakyam.app.state.AppState
import com.vakyam.app.ui.components.GazePointer
import com.vakyam.app.ui.theme.VakyamColors

private object Routes {
    const val PERMISSION = "permission"
    const val CALIBRATION = "calibration"
    const val MAIN = "main"
}

private fun routeFor(state: AppState): String = when (state) {
    AppState.IDLE, AppState.PERMISSION_CHECK -> Routes.PERMISSION
    AppState.CALIBRATION_REQUIRED, AppState.CALIBRATING_5_POINT -> Routes.CALIBRATION
    AppState.GAZE_ACTIVE, AppState.DWELL_TRIGGERED, AppState.LLM_EXPANDING, AppState.SPEAKING -> Routes.MAIN
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * Top-level composable. Navigation is a pure function of [AppState]: the ViewModel's state machine
 * decides where the app is, and this maps the state to a route. Nothing navigates on its own.
 *
 * The root Box defines the coordinate space shared by gaze points, calibration targets and tile
 * bounds, so every screen is laid out inside it without offsets.
 */
@Composable
fun GazeApp(viewModel: GazeViewModel = viewModel()) {
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navController = rememberNavController()

    val appState by viewModel.appState.collectAsStateWithLifecycle()
    val permissionDenied by viewModel.permissionDenied.collectAsStateWithLifecycle()
    val gaze by viewModel.gaze.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> viewModel.onCameraPermissionResult(granted, lifecycleOwner) }

    LaunchedEffect(Unit) {
        viewModel.onAppStarted()
        if (hasCameraPermission(context)) {
            viewModel.onCameraPermissionResult(true, lifecycleOwner)
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    // Picks up a permission granted in system settings while the app was in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (permissionDenied && hasCameraPermission(context)) {
            viewModel.onCameraPermissionResult(true, lifecycleOwner)
        }
    }

    val route = routeFor(appState)
    LaunchedEffect(route) {
        if (navController.currentDestination?.route != route) {
            navController.navigate(route) {
                popUpTo(navController.graph.id) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    // Audible + haptic confirmation of every selection, for the user and for whoever is listening.
    LaunchedEffect(Unit) {
        viewModel.selections.collect {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            view.playSoundEffect(SoundEffectConstants.CLICK)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VakyamColors.Background)
            .onSizeChanged { viewModel.onScreenSize(it.width, it.height) },
    ) {
        NavHost(
            navController = navController,
            startDestination = Routes.PERMISSION,
            // No cross-fades: a calibration dot must be fully visible the instant it is shown.
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
        ) {
            composable(Routes.PERMISSION) {
                PermissionScreen(
                    denied = permissionDenied,
                    onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", context.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                )
            }
            composable(Routes.CALIBRATION) {
                val prompt by viewModel.calibrationPrompt.collectAsStateWithLifecycle()
                val step by viewModel.calibrationStep.collectAsStateWithLifecycle()
                val face by viewModel.face.collectAsStateWithLifecycle()
                CalibrationScreen(
                    prompt = prompt,
                    step = step,
                    face = face,
                    onCalibrateNow = viewModel::calibrateNow,
                    onUseSaved = viewModel::useSavedCalibration,
                    onSkip = viewModel::skipCalibration,
                )
            }
            composable(Routes.MAIN) {
                val aac by viewModel.aac.collectAsStateWithLifecycle()
                val sentence by viewModel.sentence.collectAsStateWithLifecycle()
                val llmState by viewModel.llmState.collectAsStateWithLifecycle()
                val face by viewModel.face.collectAsStateWithLifecycle()
                val dwell = viewModel.dwell.collectAsStateWithLifecycle()
                val calibrationQuality by viewModel.calibrationQuality.collectAsStateWithLifecycle()
                AacMainScreen(
                    calibrationQuality = calibrationQuality,
                    aac = aac,
                    sentence = sentence,
                    appState = appState,
                    llmState = llmState,
                    face = face,
                    dwell = dwell,
                    onTileBounds = viewModel::setTileBounds,
                    onTileRemoved = viewModel::removeTile,
                    onTileClick = viewModel::activateTile,
                    onRecalibrate = viewModel::requestRecalibration,
                )
            }
        }

        if (route == Routes.MAIN) GazePointer(point = gaze)

        if (route == Routes.MAIN && error == null) {
            notice?.let { NoticeBanner(it, Modifier.align(Alignment.TopCenter)) }
        }

        error?.let { message ->
            ErrorBanner(message = message, onDismiss = viewModel::dismissError, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun PermissionScreen(denied: Boolean, onRequest: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(VakyamColors.Background).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Vakyam", color = VakyamColors.Amber, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (denied) {
                "Vakyam needs the front camera to follow your eyes. Camera images are processed on " +
                    "this device only and are never stored or sent anywhere."
            } else {
                "Starting…"
            },
            color = VakyamColors.OnDark,
            fontSize = 18.sp,
            textAlign = TextAlign.Center,
        )
        if (denied) {
            Spacer(Modifier.height(32.dp))
            Button(onClick = onRequest, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Allow camera", fontSize = 18.sp)
            }
            Spacer(Modifier.height(12.dp))
            // After two denials Android stops showing the dialog; settings is then the only way.
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Open app settings", fontSize = 18.sp)
            }
        }
    }
}

/** A confirmation that needs no response; it does not take touches and clears by itself. */
@Composable
private fun NoticeBanner(message: String, modifier: Modifier = Modifier) {
    Text(
        text = "✓  $message",
        color = VakyamColors.Ink,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = modifier
            .safeDrawingPadding()
            .padding(8.dp)
            .fillMaxWidth()
            .background(VakyamColors.Green, RoundedCornerShape(12.dp))
            .padding(vertical = 14.dp, horizontal = 16.dp),
    )
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .safeDrawingPadding()
            .padding(8.dp)
            .fillMaxWidth()
            .background(VakyamColors.Red, RoundedCornerShape(12.dp))
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, color = VakyamColors.Ink, fontSize = 15.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) {
            Text("Dismiss", color = VakyamColors.Ink, fontWeight = FontWeight.Bold)
        }
    }
}
