package com.demo.opencv.textEntry

import android.content.Context

/**
 * With Language = English, decides per utterance whether the input is English or Hinglish (Hindi typed in
 * English letters); that picks the word predictions, the LLM prompt and the speaking voice. With
 * Language = Hinglish everything is Hinglish and this is not consulted.
 */
class HinglishDetector(private val lexicon: HinglishLexicon) {

    /**
     * [words] are the chosen keywords, [context] the caregiver's last speech, [previous] the last spoken
     * utterance's language. Any Hinglish-only word wins (code-mixing counts as Hinglish), then any English-only
     * word; when every word is shared or unknown (names), the context and finally [previous] decide.
     */
    fun detect(words: List<String>?, context: String?, previous: String?): String {
        val tokens = words.orEmpty().flatMap(::tokens)
        return when {
            tokens.any(::isHinglishSignal) -> HINGLISH
            tokens.any(lexicon::isEnglishOnly) -> ENGLISH
            tokens(context).any(::isHinglishSignal) -> HINGLISH
            previous == HINGLISH -> HINGLISH
            else -> ENGLISH
        }
    }

    /** The same rule on a finished sentence, used to pick the voice it is spoken in. */
    @JvmOverloads
    fun detectText(sentence: String?, previous: String? = ENGLISH): String = detect(listOf(sentence.orEmpty()), null, previous)

    /** Language = Hinglish makes everything Hinglish; with English the words decide, as in [detect]. */
    fun resolve(setting: String?, words: List<String>?, context: String?, previous: String?): String =
        if (setting == HINGLISH) HINGLISH else detect(words, context, previous)

    fun resolveText(setting: String?, sentence: String?, previous: String?): String =
        if (setting == HINGLISH) HINGLISH else detectText(sentence, previous)

    private fun isHinglishSignal(token: String): Boolean = lexicon.isHinglishOnly(token)

    private fun tokens(text: String?): List<String> = text.orEmpty().split(SEPARATORS).filter { it.isNotEmpty() }

    companion object {
        const val ENGLISH = "English"
        const val HINGLISH = "Hinglish"

        private val SEPARATORS = Regex("[^\\p{L}\\p{M}]+")
        private const val PREFS = "vakyam_language"
        private const val KEY_LAST_SPOKEN = "last_spoken"

        @Volatile
        private var shared: HinglishDetector? = null

        @JvmStatic
        fun get(context: Context?): HinglishDetector {
            if (context == null) return HinglishDetector(HinglishLexicon.EMPTY)
            return shared ?: synchronized(this) {
                shared ?: HinglishDetector(HinglishLexicon.get(context)).also { shared = it }
            }
        }

        /** Language of the last Roman-script utterance spoken aloud; English until something else is spoken. */
        @JvmStatic
        fun lastSpoken(context: Context?): String =
            context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY_LAST_SPOKEN, ENGLISH) ?: ENGLISH

        @JvmStatic
        fun rememberSpoken(context: Context?, language: String) {
            if (context == null || (language != ENGLISH && language != HINGLISH)) return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_LAST_SPOKEN, language).apply()
        }
    }
}
