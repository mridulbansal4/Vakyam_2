package com.demo.opencv.features.blinkkeyboard

import android.content.Context
import android.util.Log

/**
 * Prefix completion over the user's saved custom words (ranked first) and the app's existing
 * frequency-ordered `EnglishWords.txt` asset (read-only).
 */
class WordPredictionService(context: Context, private val vocabulary: CustomVocabularyService) {

    private val dictionary: List<String> = loadDictionary(context)
    private val dictionarySet: Set<String> = dictionary.toHashSet()

    fun predict(prefix: String, limit: Int = 3): List<String> {
        val custom = vocabulary.getWords()
        if (prefix.isEmpty()) return custom.take(limit)
        val results = LinkedHashMap<String, String>()
        (custom.asSequence() + dictionary.asSequence())
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
