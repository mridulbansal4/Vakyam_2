package com.demo.opencv.features.blinkkeyboard

import android.content.Context
import org.json.JSONObject

/**
 * Persists words spelled with the blink keyboard (mostly proper nouns) in SharedPreferences under
 * [KEY_CUSTOM_WORDS], as a JSON object keyed by lowercase word:
 * `{"harshad": {"w": "Harshad", "c": 2, "t": 1760000000000}}`.
 */
class CustomVocabularyService(context: Context) {

    companion object {
        const val PREFS_NAME = "vakyam_blink_keyboard"
        const val KEY_CUSTOM_WORDS = "vakyam_custom_words"
    }

    private data class Entry(val word: String, val count: Int, val lastUsed: Long)

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Saved words, most used first. */
    fun getWords(): List<String> = load()
        .sortedWith(compareByDescending<Entry> { it.count }.thenByDescending { it.lastUsed })
        .map { it.word }

    fun saveWord(word: String) {
        val clean = word.trim()
        if (clean.isEmpty()) return
        val json = loadJson()
        val key = clean.lowercase()
        val previous = json.optJSONObject(key)
        json.put(key, JSONObject().apply {
            put("w", clean)
            put("c", (previous?.optInt("c") ?: 0) + 1)
            put("t", System.currentTimeMillis())
        })
        prefs.edit().putString(KEY_CUSTOM_WORDS, json.toString()).apply()
    }

    private fun load(): List<Entry> {
        val json = loadJson()
        return json.keys().asSequence().mapNotNull { key ->
            json.optJSONObject(key)?.let { Entry(it.optString("w", key), it.optInt("c"), it.optLong("t")) }
        }.toList()
    }

    private fun loadJson(): JSONObject = try {
        JSONObject(prefs.getString(KEY_CUSTOM_WORDS, null) ?: "{}")
    } catch (_: Exception) {
        JSONObject()
    }
}
