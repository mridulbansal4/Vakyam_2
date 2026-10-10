package com.demo.opencv.yesno

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.demo.opencv.ContractInterface
import com.demo.opencv.R

enum class Answer { YES, NO }

/**
 * A spoken yes or no. When the sentence said or typed to the patient in Type is a yes/no question
 * ([YesNoQuestion]), this screen takes over: the patient looks left for yes or right for no and
 * the phone says the answer, or looks up to go back to typing. Create one per Activity after
 * setContentView, feed it the gaze gestures, and close() it in onPause.
 */
class YesNoController(private val activity: AppCompatActivity, private val presenter: ContractInterface.Presenter) {

    private val main = Handler(Looper.getMainLooper())
    private val overlay = YesNoOverlay(activity, object : YesNoOverlay.Listener {
        override fun onWordTapped(answer: Answer) = accept(answer, "touch")
        override fun onBackTapped() = close()
    })
    private val backToClose = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = close()
    }
    private val closeTask = Runnable { close() }
    private val noAnswerTask = Runnable {
        answered = true
        overlay.showResult(null)
        main.postDelayed(closeTask, RESULT_MS)
    }
    private var openedAtMs = 0L
    private var answered = false

    @Volatile
    var isOpen = false
        private set

    init {
        activity.addContentView(overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        activity.onBackPressedDispatcher.addCallback(activity, backToClose)
    }

    /** Main thread. [question] is shown between the two answers. */
    fun open(question: String) {
        if (isOpen) return
        presenter.setGazeInputPaused(true) // the look that answers must not also type or pick a phrase
        answered = false
        openedAtMs = SystemClock.elapsedRealtime()
        overlay.show(question)
        backToClose.isEnabled = true
        isOpen = true
        main.postDelayed(noAnswerTask, NO_ANSWER_MS)
    }

    /** Main thread. */
    fun close() {
        if (!isOpen) return
        isOpen = false
        main.removeCallbacks(closeTask)
        main.removeCallbacks(noAnswerTask)
        backToClose.isEnabled = false
        overlay.hide()
        presenter.setGazeInputPaused(false)
    }

    /** Any thread. [gazeType] is DetectionOutput.gestureOutput: the same left and right the other screens select with. */
    fun onGesture(gazeType: Int) {
        if (!isOpen) return
        if (gazeType != GAZE_UP && gazeType !in LEFT && gazeType !in RIGHT) return
        val now = SystemClock.elapsedRealtime()
        main.post {
            // A look already under way when the screen opened belongs to whatever was on screen before.
            if (now - openedAtMs < SETTLE_MS) return@post
            when (gazeType) {
                GAZE_UP -> if (!answered) close()
                in LEFT -> accept(Answer.YES, "eyes")
                else -> accept(Answer.NO, "eyes")
            }
        }
    }

    private fun accept(answer: Answer, source: String) {
        if (!isOpen || answered) return
        answered = true
        Log.i(TAG, "answered $answer by $source")
        main.removeCallbacks(noAnswerTask)
        overlay.showResult(answer)
        presenter.speakPatientText(activity.getString(if (answer == Answer.YES) R.string.YesNo_Yes else R.string.YesNo_No))
        main.postDelayed(closeTask, RESULT_MS)
    }

    private companion object {
        const val TAG = "YesNo"
        const val RESULT_MS = 3_000L
        const val NO_ANSWER_MS = 45_000L
        const val SETTLE_MS = 800L
        // Indices of GazeData's gaze types
        const val GAZE_UP = 3
        val LEFT = setOf(1, 6) // left, left-up
        val RIGHT = setOf(2, 7) // right, right-up
    }
}
