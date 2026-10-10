package com.demo.opencv.features.blinkkeyboard

import android.content.Context
import android.util.Log
import com.demo.opencv.personal.PersonalLexicon
import com.demo.opencv.personal.PersonalLexicon.Script

/**
 * Prefix completion over the words the patient usually types after [predict]'s previous word, then
 * their other learned words, then the app's frequency-ordered `EnglishWords.txt` asset (read-only).
 */
class WordPredictionService(context: Context, private val lexicon: PersonalLexicon) {

    private val dictionary: List<String> = loadDictionary(context)
    private val dictionarySet: Set<String> = dictionary.toHashSet()

    fun predict(prefix: String, previousWord: String? = null, limit: Int = 3): List<String> {
        val followers = previousWord?.let { lexicon.followers(it, Script.LATIN) }.orEmpty()
        val personal = followers.asSequence() + lexicon.words(Script.LATIN).asSequence()
        val results = LinkedHashMap<String, String>()
        (if (prefix.isEmpty()) personal else personal + dictionary.asSequence())
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .forEach { results.putIfAbsent(it.lowercase(), it) }
        return results.values.take(limit)
    }

    fun isDictionaryWord(word: String): Boolean = word.lowercase() in dictionarySet

    private fun loadDictionary(context: Context): List<String> = try {
        context.assets.open("EnglishWords.txt").bufferedReader().useLines { lines ->
            lines.map { it.trim() }
                .filter { it.length > 1 && it.all(Char::isLetter) && it == it.lowercase() }
                .distinct()
                .toList()
        }
    } catch (e: Exception) {
        Log.w("BlinkWordPrediction", "dictionary unavailable", e)
        emptyList()
    }
}
