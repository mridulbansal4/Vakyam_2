package com.demo.opencv.sensors

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.demo.opencv.AI.GemmaManager
import com.demo.opencv.BuildConfig
import com.demo.opencv.ContractInterface
import com.demo.opencv.R
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicInteger

/**
 * Create in MainActivity.onCreate (after views + viewModel), start() in onResume,
 * stop() in onPause, release() in onDestroy. Every Listener callback runs on the main thread.
 */
class SensorHealthController(
    context: Context,
    private val presenter: ContractInterface.Presenter,
    private val listener: Listener
) {
    interface Listener {
        fun onBannerChanged(banner: SensorBanner?)
        fun onWarmTintChanged(alpha: Float)
        fun onSnapshot(snapshot: SensorHealthSnapshot)
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val alert = CaregiverAlert()
    private val banners: EnumSet<SensorBanner> = EnumSet.noneOf(SensorBanner::class.java)
    private var shownBanner: SensorBanner? = null
    private var snapshot = SensorHealthSnapshot()
    private var started = false

    val frameGovernor = FrameRateGovernor()

    @Volatile private var lastFrameMs = Float.NaN

    // 02 — thermal
    private val thermalPolicy = ThermalPolicy()
    private var tierApplied = false
    private val thermalMonitor = ThermalMonitor(appContext) { status, headroom ->
        publish { it.copy(thermalStatus = status, thermalHeadroom = headroom) }
        reevaluateTier()
    }

    // 03 — battery
    private val heatGuard = BatteryHeatGuard()
    private val batteryMonitor = BatteryThermalMonitor(appContext) { tempC, plugged ->
        onBatteryReading(tempC, plugged)
    }

    // 04 — color temperature
    private val tintPolicy = WarmTintPolicy()
    private var cctOverrideUntilMs = 0L
    private val colorMonitor = AmbientColorMonitor(appContext) { kelvin, lux ->
        if (SystemClock.elapsedRealtime() >= cctOverrideUntilMs) onColorReading(kelvin, lux)
    }

    private val processedFrames = AtomicInteger()
    private var lastTickMs = 0L
    private val ticker = object : Runnable {
        override fun run() {
            reevaluateTier() // lets ThermalPolicy step back down after its recovery hold
            val now = SystemClock.elapsedRealtime()
            val elapsed = if (lastTickMs == 0L) TICK_MS else (now - lastTickMs).coerceAtLeast(1L)
            lastTickMs = now
            val fps = processedFrames.getAndSet(0) * 1000f / elapsed // 0 while suspended, by design
            publish { it.copy(measuredFps = fps, frameMs = lastFrameMs) }
            main.postDelayed(this, TICK_MS)
        }
    }

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            intent ?: return
            if (intent.hasExtra("cct")) {
                cctOverrideUntilMs = SystemClock.elapsedRealtime() + DEBUG_CCT_HOLD_MS
                onColorReading(intent.getFloatExtra("cct", Float.NaN), intent.getFloatExtra("lux", 100f))
            }
        }
    }
    private var debugRegistered = false

    init {
        // Battery heat matters most overnight, so it lives for the whole Activity, not just while resumed.
        batteryMonitor.start()
        Log.i(TAG, "cct=${colorMonitor.cctSensor?.name ?: "none"}")
    }

    fun start() {
        if (started) return
        started = true
        thermalMonitor.start()
        colorMonitor.start()
        if (BuildConfig.DEBUG && !debugRegistered) {
            ContextCompat.registerReceiver(appContext, debugReceiver, IntentFilter(DEBUG_ACTION), ContextCompat.RECEIVER_EXPORTED)
            debugRegistered = true
        }
        main.post(ticker)
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(ticker)
        lastTickMs = 0L
        thermalMonitor.stop()
        colorMonitor.stop()
        if (debugRegistered) {
            appContext.unregisterReceiver(debugReceiver)
            debugRegistered = false
        }
    }

    fun release() {
        stop()
        batteryMonitor.stop()
        alert.release()
    }

    /** Camera analyzer thread, after presenter.onFrame(). */
    fun onFrameProcessed(processingMs: Long) {
        processedFrames.incrementAndGet()
        val prev = lastFrameMs
        lastFrameMs = if (prev.isNaN()) processingMs.toFloat() else prev + 0.1f * (processingMs - prev)
    }

    // ---------------------------------------------------------------- 02
    private fun reevaluateTier() {
        val previous = thermalPolicy.tier
        val tier = thermalPolicy.evaluate(
            snapshot.thermalStatus, snapshot.thermalHeadroom, snapshot.batteryTempC, SystemClock.elapsedRealtime()
        )
        if (tier != previous || !tierApplied) applyTier(tier)
    }

    private fun applyTier(tier: PerformanceTier) {
        tierApplied = true
        frameGovernor.targetFps = tier.targetFps
        presenter.setFaceRoiDetection(tier != PerformanceTier.FULL)
        GemmaManager.getInstance().setPerformanceTier(tier)
        setBanner(SensorBanner.THERMAL_CRITICAL, tier == PerformanceTier.POWER_SAVER)
        Log.i(TAG, "performance tier -> $tier")
        publish { it.copy(tier = tier) }
    }

    // ---------------------------------------------------------------- 03
    private fun onBatteryReading(tempC: Float, plugged: Boolean) {
        val previous = heatGuard.level
        val level = heatGuard.update(tempC, plugged)
        publish { it.copy(batteryTempC = tempC, plugged = plugged, chargingHeat = level) }
        if (level != previous) {
            setBanner(SensorBanner.CHARGING_WARM, level == ChargingHeat.WARM)
            setBanner(SensorBanner.CHARGING_HOT, level == ChargingHeat.HOT)
            when (level) {
                ChargingHeat.HOT -> {
                    alert.start(ALERT_HEAT, ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD)
                    presenter.speakCaregiverMessage(appContext.getString(R.string.Sensor_ChargingHotSpoken))
                }
                ChargingHeat.WARM -> {
                    alert.stop(ALERT_HEAT)
                    if (previous == ChargingHeat.NORMAL) alert.chimeOnce(ToneGenerator.TONE_PROP_BEEP)
                }
                ChargingHeat.NORMAL -> alert.stop(ALERT_HEAT)
            }
            Log.i(TAG, "charging heat $previous -> $level at $tempC °C plugged=$plugged")
        }
        reevaluateTier()
    }

    // ---------------------------------------------------------------- 04
    private fun onColorReading(kelvin: Float, lux: Float) {
        val changed = tintPolicy.update(kelvin)
        publish { it.copy(cctKelvin = tintPolicy.smoothedKelvin, ambientLux = lux, warmTintAlpha = tintPolicy.alpha) }
        if (changed) listener.onWarmTintChanged(tintPolicy.alpha)
    }

    // ---------------------------------------------------------------- shared
    private fun setBanner(banner: SensorBanner, on: Boolean) {
        if (on) banners.add(banner) else banners.remove(banner)
        val top = banners.firstOrNull()
        if (top != shownBanner) {
            shownBanner = top
            listener.onBannerChanged(top)
        }
    }

    private fun publish(change: (SensorHealthSnapshot) -> SensorHealthSnapshot) {
        snapshot = change(snapshot)
        listener.onSnapshot(snapshot)
    }

    companion object {
        private const val TAG = "SensorHealth"
        const val DEBUG_ACTION = "com.vakyam.debug.SENSOR"
        private const val TICK_MS = 1_000L
        private const val DEBUG_CCT_HOLD_MS = 60_000L
        private const val ALERT_HEAT = "heat"
    }
}
