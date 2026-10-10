package com.demo.opencv.emergency

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.demo.opencv.R
import com.demo.opencv.UserDataManager
import com.demo.opencv.sensors.CaregiverAlert
import java.util.Locale

/**
 * The patient's way to call for help, the same on every screen. Create one per Activity after
 * setContentView, feed it every eye sample, start() in onResume, stop() in onPause and release()
 * in onDestroy.
 *
 * Three long blinks (see [HelpSignalDetector]), the SOS button or the blink keyboard's HELP key
 * start a siren, a spoken call for help and a text message to the caregiver; only a touch on the
 * screen stops them. It also chimes when the camera has lost the patient, because then no eye
 * input works at all.
 */
class EmergencyController(private val activity: Activity) {

    private val main = Handler(Looper.getMainLooper())
    private val detector = HelpSignalDetector(onClosure = { durationMs, counted ->
        if (durationMs >= HOLD_HINT_AFTER_MS) Log.i(TAG, "eyes closed for $durationMs ms, counted=$counted")
    })
    private val watchdog = PresenceWatchdog()
    private val alarm = EmergencyAlarm(activity)
    private val chime = CaregiverAlert(PRESENCE_CHIME_REPEAT_MS)
    private val overlay = EmergencyOverlay(activity) { dismiss() }
    private var started = false
    private var holdText: String? = null
    private var progressText: String? = null
    private var noticeText: String? = null
    private var shownChip: String? = null
    private var strings: Pair<String?, Context>? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (watchdog.tick(SystemClock.elapsedRealtime())) onPresenceChanged()
            main.postDelayed(this, TICK_MS)
        }
    }
    private val quietPresenceChime = Runnable { chime.stop(PRESENCE_KEY) }

    val isAlarmActive: Boolean get() = alarm.isActive

    init {
        activity.addContentView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    fun start() {
        if (started) return
        started = true
        main.post(ticker)
    }

    /** A sounding alarm keeps going: leaving the screen must not silence a call for help. */
    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(ticker)
        detector.reset()
        watchdog.reset()
        silencePresenceChime()
        holdText = null
        progressText = null
        noticeText = null
        renderChip()
    }

    fun release() {
        stop()
        main.removeCallbacksAndMessages(null)
        alarm.stop()
        chime.release()
        overlay.hideAlarm()
    }

    /** Any thread, once per analysed camera frame. */
    fun onEyeSample(faceVisible: Boolean, eyesClosed: Boolean) {
        val now = SystemClock.elapsedRealtime()
        main.post { handleSample(faceVisible, eyesClosed, now) }
    }

    /** Main thread. [source] is only logged. */
    fun trigger(source: String) {
        if (alarm.isActive) return
        Log.w(TAG, "help requested by $source")
        detector.reset()
        holdText = null
        progressText = null
        renderChip()
        val language = (activity.application as? UserDataManager)?.language
        val strings = localized(language)
        alarm.start(strings.getString(R.string.Emergency_Spoken), localeFor(language))
        overlay.showAlarm(
            strings.getString(R.string.Emergency_Title),
            strings.getString(R.string.Emergency_Subtitle),
            strings.getString(R.string.Emergency_Dismiss),
        )
        sendSms(strings)
    }

    private fun dismiss() {
        Log.i(TAG, "alarm stopped by touch")
        alarm.stop()
        overlay.hideAlarm()
    }

    private fun handleSample(faceVisible: Boolean, eyesClosed: Boolean, nowMs: Long) {
        if (!started) return
        if (watchdog.onSample(faceVisible, nowMs)) onPresenceChanged()
        if (alarm.isActive) return
        val language = (activity.application as? UserDataManager)?.language
        when (detector.update(faceVisible, eyesClosed, nowMs)) {
            HelpSignalDetector.Event.COUNTED -> {
                chime.chimeOnce(ToneGenerator.TONE_PROP_BEEP2)
                progressText = localized(language)
                    .getString(R.string.Emergency_Progress, detector.count, detector.requiredBlinks)
            }
            HelpSignalDetector.Event.TRIGGERED -> return trigger("long blinks")
            HelpSignalDetector.Event.EXPIRED -> progressText = null
            HelpSignalDetector.Event.NONE -> Unit
        }
        // The patient cannot see this with closed eyes; it shows a helper that the closure is being measured.
        val closedMs = detector.closureMs(nowMs)
        holdText = if (closedMs < HOLD_HINT_AFTER_MS) null
        else localized(language).getString(R.string.Emergency_Holding, closedMs / 1000f)
        renderChip()
    }

    private fun onPresenceChanged() {
        if (watchdog.absent) {
            Log.w(TAG, "patient not visible to the camera")
            val language = (activity.application as? UserDataManager)?.language
            noticeText = localized(language).getString(R.string.Emergency_NotVisible)
            chime.start(PRESENCE_KEY, ToneGenerator.TONE_CDMA_ABBR_ALERT)
            // A phone left on a table should not chime all day; the notice stays on screen.
            main.postDelayed(quietPresenceChime, PRESENCE_CHIME_FOR_MS)
        } else {
            noticeText = null
            silencePresenceChime()
        }
        renderChip()
    }

    private fun silencePresenceChime() {
        main.removeCallbacks(quietPresenceChime)
        chime.stop(PRESENCE_KEY)
    }

    private fun renderChip() {
        val text = holdText ?: progressText ?: noticeText
        if (text == shownChip) return
        shownChip = text
        overlay.showChip(text, urgent = text != noticeText)
    }

    private fun sendSms(strings: Context) {
        val number = (activity.application as? UserDataManager)?.caregiverPhone?.trim().orEmpty()
        when {
            number.isEmpty() -> overlay.setStatus(strings.getString(R.string.Emergency_SmsNoNumber))
            !CaregiverSms.hasPermission(activity) -> overlay.setStatus(strings.getString(R.string.Emergency_SmsNoPermission))
            else -> {
                overlay.setStatus(strings.getString(R.string.Emergency_SmsSending, number))
                CaregiverSms.send(activity, number, strings.getString(R.string.Emergency_SmsText)) { sent ->
                    Log.i(TAG, "caregiver SMS sent=$sent")
                    overlay.setStatus(
                        strings.getString(if (sent) R.string.Emergency_SmsSent else R.string.Emergency_SmsFailed, number)
                    )
                }
            }
        }
    }

    /** The patient's language, whatever locale this Activity happens to be showing. */
    private fun localized(language: String?): Context {
        strings?.takeIf { it.first == language }?.let { return it.second }
        val config = Configuration(activity.resources.configuration)
        config.setLocale(localeFor(language))
        return activity.createConfigurationContext(config).also { strings = language to it }
    }

    private fun localeFor(language: String?): Locale = when (language) {
        "Spanish" -> Locale("es", "ES")
        "Chinese" -> Locale.SIMPLIFIED_CHINESE
        "Hindi" -> Locale("hi", "IN")
        else -> Locale.US
    }

    private companion object {
        const val TAG = "Emergency"
        const val TICK_MS = 1_000L
        const val HOLD_HINT_AFTER_MS = 600L
        const val PRESENCE_KEY = "presence"
        const val PRESENCE_CHIME_REPEAT_MS = 60_000L
        const val PRESENCE_CHIME_FOR_MS = 5 * 60_000L
    }
}

/** Sits above the Activity's content. Lets touches through unless the alarm panel is showing. */
@SuppressLint("ViewConstructor")
private class EmergencyOverlay(context: Context, onDismiss: () -> Unit) : FrameLayout(context) {

    private val chip = label(18f).apply {
        setPadding(dp(18), dp(8), dp(18), dp(8))
        visibility = View.GONE
    }
    private val title = label(54f)
    private val subtitle = label(24f)
    private val status = label(18f)
    private val dismissButton = label(26f).apply {
        setTextColor(ALARM_RED)
        background = rounded(Color.WHITE)
        setPadding(dp(32), dp(18), dp(32), dp(18))
        setOnClickListener { onDismiss() }
    }
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true // swallows touches so nothing underneath is pressed by the person rushing to help
        visibility = View.GONE
        setPadding(dp(24), dp(16), dp(24), dp(16))
        addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(subtitle, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        addView(dismissButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(20) })
        addView(status, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(16) })
    }
    private val flash = ValueAnimator.ofObject(ArgbEvaluator(), ALARM_RED, ALARM_DARK).apply {
        duration = 500L
        repeatMode = ValueAnimator.REVERSE
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { panel.setBackgroundColor(it.animatedValue as Int) }
    }

    init {
        addView(panel, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(chip, LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(6)
        })
    }

    fun showChip(text: String?, urgent: Boolean) {
        chip.text = text
        chip.background = rounded(if (urgent) ALARM_RED else NOTICE_AMBER)
        chip.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    fun showAlarm(titleText: String, subtitleText: String, dismissText: String) {
        title.text = titleText
        subtitle.text = subtitleText
        dismissButton.text = dismissText
        status.text = ""
        panel.visibility = View.VISIBLE
        flash.start()
    }

    fun setStatus(text: String) {
        status.text = text
    }

    fun hideAlarm() {
        flash.cancel()
        panel.visibility = View.GONE
    }

    private fun label(sizeSp: Float) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
    }

    private fun rounded(fill: Int) = GradientDrawable().apply {
        cornerRadius = dp(14).toFloat()
        setColor(fill)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val ALARM_RED = 0xFFC62828.toInt()
        const val ALARM_DARK = 0xFF7F0000.toInt()
        const val NOTICE_AMBER = 0xFFEF6C00.toInt()
    }
}
