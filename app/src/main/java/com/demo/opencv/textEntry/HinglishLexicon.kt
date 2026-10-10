package com.demo.opencv.textEntry

import android.content.Context
import android.util.Log
import java.io.InputStream

/**
 * Hindi written in Roman letters. [words] holds one canonical spelling per word, frequency-ordered, for the
 * keyboards; variant spellings ("pani", "nahin") are only used to recognise what was typed.
 */
class HinglishLexicon(entries: List<Entry>, englishWords: Collection<String>) {

    data class Entry(val word: String, val variants: List<String> = emptyList())

    val words: List<String> = entries.map { it.word }

    private val forms: Set<String> = entries.flatMapTo(HashSet()) { listOf(it.word) + it.variants }
    private val english: Set<String> = englishWords.mapTo(HashSet()) { it.trim().lowercase() }
    private val hinglishOnly: Set<String> = forms - english - ENGLISH_STOPLIST

    fun isHinglish(word: String?): Boolean = normalize(word) in forms

    /** A Hinglish spelling that is not also an English word, so it settles the language on its own. */
    fun isHinglishOnly(word: String?): Boolean = normalize(word) in hinglishOnly

    /** An English word that no Hinglish spelling shares. */
    fun isEnglishOnly(word: String?): Boolean = normalize(word).let { it in english && it !in forms }

    companion object {
        private const val TAG = "HinglishLexicon"
        const val WORDS_ASSET = "HinglishWords.txt"
        const val BIGRAMS_ASSET = "HinglishBigrams.txt"
        const val ENGLISH_ASSET = "EnglishWords.txt"

        /**
         * English words outside the 5,000-word list (which has no inflected forms such as "is") that share a
         * Hinglish spelling, plus food, family and festival words Indian English uses inside English sentences.
         */
        val ENGLISH_STOPLIST = setOf(
            "is", "ache", "bees", "barf", "bade", "din", "pal", "jab", "sun", "par", "mat", "mile", "sat", "tin", "tab", "lag", "ate", "aye", "den",
            "char", "gale", "gala", "jute", "pita", "pith", "sans", "sham", "soya", "vale", "para", "hath", "jag",
            "kat", "sari", "sara",
            "ma", "mama", "papa", "dada", "nana", "baba", "beta", "didi", "bhaiya", "bhabhi", "dadi", "nani",
            "chacha", "chachi", "mami", "bua", "amma", "ammi", "abbu", "jija", "mummy",
            "chai", "samosa", "biryani", "paneer", "dosa", "idli", "lassi", "masala", "chutney", "halwa", "roti",
            "dal", "daal", "paratha", "pulao", "jalebi", "laddu", "kheer", "poha", "raita", "ghee",
            "namaste", "bazaar", "diwali", "holi", "eid",
        )

        @JvmField
        val EMPTY = HinglishLexicon(emptyList(), emptyList())

        @Volatile
        private var shared: HinglishLexicon? = null

        fun normalize(word: String?): String = word?.lowercase()?.filter { it in 'a'..'z' }.orEmpty()

        /** `canonical[<TAB>variant,variant]` per line, all in Roman letters. */
        fun parse(lines: Sequence<String>): List<Entry> = lines.mapNotNull { line ->
            val cols = line.split('\t')
            val word = cols[0].trim()
            if (word.isEmpty()) return@mapNotNull null
            val variants = cols.getOrNull(1)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            Entry(word, variants)
        }.toList()

        fun load(open: (String) -> InputStream): HinglishLexicon {
            val entries = open(WORDS_ASSET).bufferedReader(Charsets.UTF_8).useLines { parse(it) }
            val english = open(ENGLISH_ASSET).bufferedReader(Charsets.UTF_8).useLines { lines -> lines.toList() }
            return HinglishLexicon(entries, english)
        }

        @JvmStatic
        fun get(context: Context?): HinglishLexicon {
            if (context == null) return EMPTY
            shared?.let { return it }
            return synchronized(this) {
                shared ?: try {
                    load { context.applicationContext.assets.open(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "Hinglish lexicon unavailable", e)
                    EMPTY
                }.also { shared = it }
            }
        }

        /** Primary, primary, secondary, ... so each three-word page offers both languages; duplicates kept once. */
        @JvmStatic
        fun interleave(primary: List<String>, secondary: List<String>): List<String> {
            val out = LinkedHashSet<String>()
            var p = 0
            var s = 0
            while (p < primary.size || s < secondary.size) {
                repeat(2) { if (p < primary.size) out.add(primary[p++]) }
                if (s < secondary.size) out.add(secondary[s++])
                if (p >= primary.size) while (s < secondary.size) out.add(secondary[s++])
            }
            return out.toList()
        }
    }
}
