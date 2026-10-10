package com.demo.opencv.sensors

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/** Emits (status, headroom) on the main thread. status = -1 below API 29; headroom = NaN below API 30. */
class ThermalMonitor(
    context: Context,
    private val onReading: (status: Int, headroom: Float) -> Unit
) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val handler = Handler(Looper.getMainLooper())
    private var statusListener: Any? = null // PowerManager.OnThermalStatusChangedListener (API 29)
    private var status = -1
    private var headroom = Float.NaN
    private var running = false

    private val headroomPoll = object : Runnable {
        override fun run() {
            readHeadroom()
            onReading(status, headroom)
            handler.postDelayed(this, HEADROOM_POLL_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            status = powerManager.currentThermalStatus
            val listener = PowerManager.OnThermalStatusChangedListener { newStatus ->
                Log.i(TAG, "thermal status -> $newStatus")
                status = newStatus
                onReading(status, headroom)
            }
            powerManager.addThermalStatusListener(appContext.mainExecutor, listener)
            statusListener = listener
        }
        handler.post(headroomPoll)
    }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(headroomPoll)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (statusListener as? PowerManager.OnThermalStatusChangedListener)?.let {
                powerManager.removeThermalStatusListener(it)
            }
        }
        statusListener = null
    }

    private fun readHeadroom() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val h = powerManager.getThermalHeadroom(HEADROOM_FORECAST_S)
        if (!h.isNaN()) headroom = h // NaN = rate-limited or unsupported: keep the last value
    }

    companion object {
        private const val TAG = "ThermalMonitor"
        private const val HEADROOM_POLL_MS = 10_000L
        private const val HEADROOM_FORECAST_S = 10
    }
}
