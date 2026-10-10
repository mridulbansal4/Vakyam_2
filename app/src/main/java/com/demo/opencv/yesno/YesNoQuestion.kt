package com.demo.opencv.yesno

/**
 * Pure: does a sentence said or typed to the patient ask for a yes or a no?
 *
 * The speech recogniser returns lower-case words without a question mark, in English or in Hindi
 * written in English letters, so this goes by how the sentence is built: an English question that
 * opens with a helping verb ("are you…", "do you…"), or a Hindi one that opens or closes with
 * "kya" or closes with "na". A question word or a choice between two things means a longer answer.
 */
object YesNoQuestion {

    private val englishOpeners = setOf(
        "is", "are", "am", "was", "were", "do", "does", "did", "can", "could", "will", "would", "should", "shall",
        "have", "has", "had", "may", "might", "must",
        "isn't", "aren't", "wasn't", "weren't", "don't", "doesn't", "didn't", "can't", "couldn't", "won't",
        "wouldn't", "shouldn't", "haven't", "hasn't", "hadn't",
    )
    private val questionWords = setOf(
        "what", "when", "where", "who", "whom", "whose", "why", "which", "how",
        "kaun", "kab", "kahan", "kaha", "kaise", "kaisa", "kaisi", "kyun", "kyon", "kyu", "kitna", "kitne", "kitni",
        "kaunsa", "kaunsi", "konsa", "konsi", "kisko", "kise", "kis", "kidhar",
    )
    /** After an opening "kya" these make it "what …" ("kya chahiye", "kya hua"), not a yes/no question. */
    private val afterKyaMeaningWhat = setOf(
        "chahiye", "hua", "hai", "ho", "tha", "kar", "kiya", "karna", "karu", "karun", "karoge", "karenge",
        "khana", "khaoge", "khayenge", "peena", "piyoge", "lena", "loge", "dekhna", "dekhoge", "bol", "bola",
        "kaha", "baat", "laun", "lau", "du", "dun", "doon",
    )

    @JvmStatic
    fun isYesNo(sentence: String?): Boolean {
        val words = sentence.orEmpty().lowercase()
            .replace('’', '\'')
            .split(Regex("[^a-z']+"))
            .filter { it.isNotEmpty() }
        if (words.size < 2) return false

        val asksToChoose = hasChoice(words, "or", setOf("not")) || hasChoice(words, "ya", setOf("nahi", "nahin"))
        if (asksToChoose) return false

        val first = words.first()
        val last = words.last()
        val rest = words.drop(1)
        return when {
            first in englishOpeners -> rest.none { it in questionWords }
            first == "kya" -> words[1] !in afterKyaMeaningWhat && rest.none { it in questionWords || it == "kya" }
            last == "kya" || last == "na" -> words.dropLast(1).none { it in questionWords || it == "kya" }
            else -> false
        }
    }

    /** True for "tea or coffee"; false for "… or not", which still wants a yes or a no. */
    private fun hasChoice(words: List<String>, or: String, not: Set<String>): Boolean {
        val at = words.indexOf(or)
        return at in 1 until words.lastIndex && words[at + 1] !in not
    }
}
