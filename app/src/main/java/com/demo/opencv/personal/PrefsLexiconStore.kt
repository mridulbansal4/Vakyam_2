package com.demo.opencv.personal

import android.content.Context
import org.json.JSONObject

/**
 * SharedPreferences backing for [PersonalLexicon]. Each map is a JSON object keyed by the lowercase
 * form: `{"harshad": {"w": "Harshad", "c": 2, "t": 1760000000000}}`. Words keep the file and key
 * the blink keyboard has always used, so vocabulary learned before the modes were joined is kept.
 */
class PrefsLexiconStore(context: Context) : PersonalLexicon.Store {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load() = PersonalLexicon.Snapshot(read(KEY_WORDS), read(KEY_PAIRS), read(KEY_PHRASES))

    override fun save(snapshot: PersonalLexicon.Snapshot) {
        prefs.edit()
            .putString(KEY_WORDS, write(snapshot.words))
            .putString(KEY_PAIRS, write(snapshot.pairs))
            .putString(KEY_PHRASES, write(snapshot.phrases))
            .apply()
    }

    private fun read(prefKey: String): Map<String, PersonalLexicon.Entry> {
        val json = try {
            JSONObject(prefs.getString(prefKey, null) ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        val entries = HashMap<String, PersonalLexicon.Entry>()
        for (key in json.keys()) {
            val item = json.optJSONObject(key) ?: continue
            entries[key] = PersonalLexicon.Entry(item.optString("w", key), item.optInt("c", 1), item.optLong("t"))
        }
        return entries
    }

    private fun write(entries: Map<String, PersonalLexicon.Entry>): String {
        val json = JSONObject()
        for ((key, entry) in entries) {
            json.put(key, JSONObject().put("w", entry.text).put("c", entry.count).put("t", entry.lastUsed))
        }
        return json.toString()
    }

    companion object {
        const val PREFS_NAME = "vakyam_blink_keyboard"
        const val KEY_WORDS = "vakyam_custom_words"
        const val KEY_PAIRS = "vakyam_word_pairs"
        const val KEY_PHRASES = "vakyam_phrases"
    }
}
