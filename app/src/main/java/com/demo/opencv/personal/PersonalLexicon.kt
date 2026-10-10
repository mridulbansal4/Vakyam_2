package com.demo.opencv.personal

import android.content.Context
import java.util.Locale

/**
 * What this patient actually says: their words, which word follows which, and whole spoken
 * sentences. One instance is shared by every input mode (gaze text entry, quick chat and the
 * blink keyboard), so a name spelled once in any of them is predicted in all of them.
 */
class PersonalLexicon @JvmOverloads constructor(
    private val store: Store,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    interface Store {
        fun load(): Snapshot
        fun save(snapshot: Snapshot)
    }

    data class Entry(val text: String, val count: Int, val lastUsed: Long)

    /** Each map is keyed by the lowercase form; [pairs] by "previous next" with [Entry.text] = next. */
    data class Snapshot(
        val words: Map<String, Entry> = emptyMap(),
        val pairs: Map<String, Entry> = emptyMap(),
        val phrases: Map<String, Entry> = emptyMap(),
    )

    enum class Script {
        LATIN, DEVANAGARI;

        companion object {
            @JvmStatic
            fun of(word: String): Script {
                val first = word.firstOrNull(Char::isLetter) ?: return LATIN
                return when {
                    first in 'ऀ'..'ॿ' -> DEVANAGARI
                    else -> LATIN
                }
            }

            @JvmStatic
            fun forLanguage(language: String?): Script = when (language) {
                "Hindi" -> DEVANAGARI
                else -> LATIN
            }
        }
    }

    private val words = HashMap<String, Entry>()
    private val pairs = HashMap<String, Entry>()
    private val phrases = HashMap<String, Entry>()

    init {
        val saved = store.load()
        words.putAll(saved.words)
        pairs.putAll(saved.pairs)
        phrases.putAll(saved.phrases)
    }

    /**
     * Learns one confirmed utterance: every word, each adjacent word pair, and [phrase] as a
     * reusable sentence. [phrase] defaults to the words joined; pass the spoken text when it
     * differs (an LLM sentence expanded from keywords).
     */
    @JvmOverloads
    @Synchronized
    fun recordUtterance(spokenWords: List<String>, phrase: String? = null) {
        val now = clock()
        val keys = spokenWords.mapNotNull { learnWord(it, now) }
        keys.zipWithNext { previous, next -> bump(pairs, "$previous $next", next, now) }
        learnPhrase(phrase ?: spokenWords.joinToString(" "), now)
        persist()
    }

    /** Learns a single word typed after [previous] (null at the start of a sentence). */
    @JvmOverloads
    @Synchronized
    fun recordWord(word: String, previous: String? = null) {
        val now = clock()
        val key = learnWord(word, now) ?: return
        previous?.let(::cleanWord)?.let { bump(pairs, "${keyOf(it)} $key", key, now) }
        persist()
    }

    @Synchronized
    fun recordPhrase(phrase: String) {
        if (learnPhrase(phrase, clock())) persist()
    }

    /** Learned words in [script], most used first. */
    @Synchronized
    fun words(script: Script): List<String> =
        words.values.filter { Script.of(it.text) == script }.sortedWith(STRONGEST_FIRST).map { it.text }

    /** Words this patient has typed straight after [previous], most frequent first. */
    @Synchronized
    fun followers(previous: String, script: Script): List<String> {
        val prefix = keyOf(cleanWord(previous) ?: return emptyList()) + " "
        return pairs.entries.asSequence()
            .filter { it.key.startsWith(prefix) }
            .map { it.value }
            .sortedWith(STRONGEST_FIRST)
            .map { words[it.text]?.text ?: it.text }
            .filter { Script.of(it) == script }
            .toList()
    }

    /** Most spoken sentences first. */
    @Synchronized
    fun phrases(limit: Int): List<String> = phrases.values.sortedWith(STRONGEST_FIRST).take(limit).map { it.text }

    @Synchronized
    fun wordEntries(): List<Entry> = words.values.sortedWith(STRONGEST_FIRST)

    @Synchronized
    fun phraseEntries(): List<Entry> = phrases.values.sortedWith(STRONGEST_FIRST)

    /** Removes a mistyped word and every pair that mentions it. */
    @Synchronized
    fun forgetWord(word: String) {
        val key = keyOf(word.trim())
        if (words.remove(key) == null) return
        pairs.keys.removeAll { it.startsWith("$key ") || it.endsWith(" $key") }
        persist()
    }

    @Synchronized
    fun forgetPhrase(phrase: String) {
        if (phrases.remove(keyOf(stripEdges(phrase))) != null) persist()
    }

    private fun learnWord(raw: String, now: Long): String? {
        val word = cleanWord(raw) ?: return null
        val key = keyOf(word)
        val known = words[key]?.text
        // A name saved as "Harshad" keeps its capital when it is later typed on a lowercase-only keyboard.
        val display = if (known != null && word == key && known != key) known else word
        bump(words, key, display, now)
        return key
    }

    private fun learnPhrase(raw: String, now: Long): Boolean {
        val phrase = raw.trim().replace(WHITESPACE, " ")
        val key = keyOf(stripEdges(phrase))
        if (key.isEmpty() || phrase.length > MAX_PHRASE_LENGTH) return false
        bump(phrases, key, phrase, now)
        return true
    }

    private fun bump(map: MutableMap<String, Entry>, key: String, text: String, now: Long) {
        map[key] = Entry(text, (map[key]?.count ?: 0) + 1, now)
    }

    private fun persist() {
        trim(words, MAX_WORDS)
        trim(pairs, MAX_PAIRS)
        trim(phrases, MAX_PHRASES)
        store.save(Snapshot(HashMap(words), HashMap(pairs), HashMap(phrases)))
    }

    /** Over the limit, the least used and oldest tenth is dropped in one go. */
    private fun trim(map: MutableMap<String, Entry>, max: Int) {
        if (map.size <= max) return
        map.entries.sortedWith(compareBy<Map.Entry<String, Entry>> { it.value.count }.thenBy { it.value.lastUsed })
            .take(map.size - max + max / 10)
            .forEach { map.remove(it.key) }
    }

    private fun cleanWord(raw: String): String? {
        val word = stripEdges(raw)
        return word.takeIf { it.length <= MAX_WORD_LENGTH && it.any(Char::isLetter) && it.none(Char::isWhitespace) }
    }

    private fun stripEdges(text: String) = text.trim().trim { !it.isLetterOrDigit() && it !in COMBINING_MARKS }

    private fun keyOf(text: String) = text.lowercase(Locale.ROOT)

    companion object {
        const val MAX_WORDS = 2000
        const val MAX_PAIRS = 4000
        const val MAX_PHRASES = 200
        private const val MAX_WORD_LENGTH = 40
        private const val MAX_PHRASE_LENGTH = 200
        private val WHITESPACE = Regex("\\s+")
        // Devanagari vowel signs are not letters to Char.isLetterOrDigit but end many Hindi words.
        private val COMBINING_MARKS = 'ऀ'..'ॿ'
        private val STRONGEST_FIRST = compareByDescending<Entry> { it.count }.thenByDescending { it.lastUsed }

        @Volatile
        private var instance: PersonalLexicon? = null

        @JvmStatic
        fun get(context: Context): PersonalLexicon = instance ?: synchronized(this) {
            instance ?: PersonalLexicon(PrefsLexiconStore(context.applicationContext)).also { instance = it }
        }
    }
}
