package com.demo.opencv.features.blinkkeyboard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.demo.opencv.UserDataManager
import com.demo.opencv.other.AudioManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Full-screen host for [BlinkKeyboardOverlay]. Runs as its own activity so it can own the front
 * camera: CameraX suspends the caller's camera binding while this activity is in the foreground
 * and restores it when this one finishes.
 */
class BlinkKeyboardActivity : AppCompatActivity(), BlinkKeyboardOverlay.Listener {

    private lateinit var overlay: BlinkKeyboardOverlay
    private var analyzer: EyeAspectRatioAnalyzer? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var tts: AudioManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        overlay = BlinkKeyboardOverlay(this)
        overlay.listener = this
        setContentView(overlay)
        hideSystemBars()

        tts = AudioManager().apply {
            initialize(this@BlinkKeyboardActivity)
            setLanguage((application as? UserDataManager)?.language)
        }
        overlay.speaker = BlinkKeyboardOverlay.Speaker { tts?.speakText(it) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onClose()
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        overlay.start()
    }

    override fun onPause() {
        overlay.stop()
        super.onPause()
    }

    override fun onDestroy() {
        overlay.release()
        analyzer?.close()
        cameraExecutor.shutdown()
        tts?.destroy()
        tts = null
        if (isFinishing) BlinkKeyboard.onKeyboardFinished()
        super.onDestroy()
    }

    override fun onWordCompleted(word: String) = BlinkKeyboard.deliverWord(word)

    override fun onClose() = finish()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode in SWITCH_KEYS) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) overlay.triggerManualSelect()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            overlay.setCameraStatus("No camera permission. Tap or press Space")
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                val eyeAnalyzer = EyeAspectRatioAnalyzer(overlay.config) { overlay.onEyeSample(it) }
                analysis.setAnalyzer(cameraExecutor, eyeAnalyzer)
                analyzer = eyeAnalyzer
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "camera start failed", e)
                overlay.setCameraStatus("Camera unavailable. Tap or press Space")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private companion object {
        const val TAG = "BlinkKeyboardActivity"
        const val REQUEST_CAMERA = 4711
        val SWITCH_KEYS = setOf(
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
        )
    }
}
