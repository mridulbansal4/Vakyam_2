package com.demo.opencv.features.blinkkeyboard

import android.app.Activity
import android.content.Intent

/**
 * Public entry point for the eye-blink scanning keyboard:
 * `BlinkKeyboard.open(activity, word -> ..., () -> ...)`.
 */
object BlinkKeyboard {

    fun interface OnWordCompleted {
        fun onWordCompleted(word: String)
    }

    fun interface OnClose {
        fun onClose()
    }

    private var onWordCompleted: OnWordCompleted? = null
    private var onClose: OnClose? = null

    @JvmStatic
    @JvmOverloads
    fun open(activity: Activity, onWordCompleted: OnWordCompleted? = null, onClose: OnClose? = null) {
        this.onWordCompleted = onWordCompleted
        this.onClose = onClose
        activity.startActivity(Intent(activity, BlinkKeyboardActivity::class.java))
    }

    internal fun deliverWord(word: String) {
        onWordCompleted?.onWordCompleted(word)
    }

    internal fun onKeyboardFinished() {
        val callback = onClose
        onWordCompleted = null
        onClose = null
        callback?.onClose()
    }
}
