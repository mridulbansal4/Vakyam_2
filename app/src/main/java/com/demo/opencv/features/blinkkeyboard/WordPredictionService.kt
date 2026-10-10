package com.demo.opencv.features.blinkkeyboard

import android.content.Context
import android.util.Log
import com.demo.opencv.UserDataManager
import com.demo.opencv.personal.PersonalLexicon
import com.demo.opencv.personal.PersonalLexicon.Script
import com.demo.opencv.textEntry.HinglishDetector
import com.demo.opencv.textEntry.HinglishLexicon
import java.io.InputStream

/**
 * Prefix completion over the words the patient usually types after [predict]'s previous word, then
 * their other learned words, then the app's frequency-ordered `EnglishWords.txt` interleaved with the Hinglish
 * words (two of the language being typed for every one of the other), all read-only.
 */
class WordPredictionService(
    private val lexicon: PersonalLexicon,
    englishWords: List<String>,
    private val hinglish: HinglishLexicon,
    private val lastSpokenLanguage: () -> String,
    private val languageSetting: () -> String? = { HinglishDetector.ENGLISH },
) {

    constructor(context: Context, lexicon: PersonalLexicon) : this(
        lexicon,
        loadDictionary { context.assets.open(it) },
        HinglishLexicon.get(context),
        { HinglishDetector.lastSpoken(context) },
        { (context.applicationContext as? UserDataManager)?.language },
    )

    private val dictionary: List<String> = englishWords.filter(::isPlainWord).distinct()
    private val dictionarySet: Set<String> = dictionary.toHashSet()
    private val hinglishWords: List<String> = hinglish.words.filter(::isPlainWord).distinct()
    private val detector = HinglishDetector(hinglish)

    /** [typedWords] are the words already in the sentence, used to tell which language is being typed. */
    @JvmOverloads
    fun predict(
        prefix: String,
        previousWord: String? = null,
        limit: Int = 3,
        typedWords: List<String> = listOfNotNull(previousWord),
    ): List<String> {
        val followers = previousWord?.let { lexicon.followers(it, Script.LATIN) }.orEmpty()
        val personal = followers.asSequence() + lexicon.words(Script.LATIN).asSequence()
        val results = LinkedHashMap<String, String>()
        val candidates = if (prefix.isEmpty()) personal else personal + dictionaryMatches(prefix, typedWords).asSequence()
        candidates
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .forEach { results.putIfAbsent(it.lowercase(), it) }
        return results.values.take(limit)
    }

    fun isDictionaryWord(word: String): Boolean = word.lowercase() in dictionarySet || hinglish.isHinglish(word)

    private fun dictionaryMatches(prefix: String, typedWords: List<String>): List<String> {
        val english = dictionary.filter { it.startsWith(prefix, ignoreCase = true) }
        val roman = hinglishWords.filter { it.startsWith(prefix, ignoreCase = true) }
        return if (detector.resolve(languageSetting(), typedWords, null, lastSpokenLanguage()) == HinglishDetector.HINGLISH) {
            HinglishLexicon.interleave(roman, english)
        } else {
            HinglishLexicon.interleave(english, roman)
        }
    }

    companion object {
        private fun isPlainWord(word: String) = word.length > 1 && word.all(Char::isLetter) && word == word.lowercase()

        fun loadDictionary(open: (String) -> InputStream): List<String> = try {
            open(HinglishLexicon.ENGLISH_ASSET).bufferedReader().useLines { lines -> lines.map { it.trim() }.toList() }
        } catch (e: Exception) {
            Log.w("BlinkWordPrediction", "dictionary unavailable", e)
            emptyList()
        }
    }
}
