package com.demo.opencv.textEntry

/**
 * Words the patient is likely to answer with, picked from what the caregiver just said. These lead the
 * suggestions as soon as letters are typed, so "what do you want to eat" offers rice before nice and side.
 * Works offline and instantly; Gemma's own suggestions are appended when they arrive.
 */
object ContextVocabulary {

    private class Topic(val triggers: Set<String>, val phrases: List<String>, val english: List<String>, val hinglish: List<String>)

    // Most specific first: "where does it hurt" is about pain, not a place; "want" alone only means a need.
    private val TOPICS = listOf(
        Topic( // food
            setOf("eat", "eating", "food", "hungry", "breakfast", "lunch", "dinner", "meal", "snack", "khana", "khane",
                "khaana", "khaane", "khaoge", "khaogi", "khayenge", "khaenge", "nashta", "bhookh", "bhook", "bhuk"),
            emptyList(),
            listOf("rice", "roti", "dal", "soup", "bread", "egg", "chicken", "curry", "fruit", "banana", "apple", "idli",
                "dosa", "khichdi", "curd", "paratha", "sandwich", "biscuit", "salad", "noodles", "fish", "vegetables",
                "porridge", "oats", "pasta", "pizza", "sweet", "cake", "paneer", "poha", "upma", "mango", "toast", "potato"),
            listOf("khana", "chawal", "sabzi", "dahi", "anda", "phal", "kela", "seb", "mithai", "halwa", "pulao",
                "raita", "aloo", "nashta")
        ),
        Topic( // drink
            setOf("drink", "drinking", "thirsty", "juice", "beverage", "tea", "coffee", "chai", "peena", "peene", "pina",
                "piyoge", "piyogi", "piyenge", "pyaas", "pyas"),
            emptyList(),
            listOf("water", "tea", "coffee", "milk", "juice", "soup", "lassi", "buttermilk", "coconut", "lemonade", "cold",
                "hot", "warm"),
            listOf("paani", "chai", "doodh", "nimbu", "chaas", "sharbat", "garam", "thanda")
        ),
        Topic( // pain
            setOf("hurt", "hurts", "hurting", "pain", "painful", "ache", "aching", "sore", "injury", "itching", "dard",
                "takleef", "taklif", "chot", "khujli"),
            emptyList(),
            listOf("head", "back", "neck", "leg", "stomach", "chest", "throat", "arm", "hand", "shoulder", "knee", "foot",
                "eyes", "tooth", "ear", "mouth", "hip", "everywhere", "here"),
            listOf("sir", "kamar", "gardan", "pair", "pet", "seena", "gala", "haath", "kandha", "ghutna", "aankh",
                "daant", "kaan", "yahan")
        ),
        Topic( // place
            setOf("where", "go", "going", "visit", "place", "trip", "outing", "travel", "outside", "kahan", "kaha",
                "kahaan", "jaana", "jana", "jaoge", "jaogi", "jaenge", "jayenge", "chaloge", "chalogi", "ghumne", "ghoomne"),
            emptyList(),
            listOf("park", "home", "garden", "temple", "market", "hospital", "outside", "beach", "mall", "balcony",
                "terrace", "church", "mosque", "office", "village", "shop", "restaurant", "cinema", "room", "bathroom",
                "bed", "walk", "friend", "school", "lake", "river", "station"),
            listOf("ghar", "bahar", "mandir", "bazaar", "bagicha", "chhat", "kamra", "masjid", "gurudwara", "dukaan", "gaon")
        ),
        Topic( // people
            setOf("who", "whom", "call", "meet", "kaun", "kisko", "kisse", "kise", "milna", "miloge", "bulau", "bulaun", "phone"),
            emptyList(),
            listOf("mom", "dad", "wife", "husband", "son", "daughter", "brother", "sister", "friend", "doctor", "nurse",
                "family", "mother", "father", "grandma", "grandpa", "children", "everyone"),
            listOf("mummy", "papa", "maa", "beta", "beti", "bhai", "behen", "dost", "dadi", "nani", "dada", "nana", "bacche")
        ),
        Topic( // time
            setOf("when", "time", "kab", "kitne", "baje"),
            emptyList(),
            listOf("now", "later", "today", "tomorrow", "morning", "evening", "night", "soon", "afternoon", "tonight", "weekend"),
            listOf("abhi", "baad", "aaj", "kal", "subah", "shaam", "raat", "jaldi", "dopahar")
        ),
        Topic( // activity
            setOf("watch", "play", "listen", "tv", "music", "bored", "karna", "karoge", "karogi", "dekhna", "dekhoge",
                "dekhogi", "sunna", "sunoge", "sunogi", "khelna"),
            listOf("want to do", "like to do"),
            listOf("tv", "music", "news", "movie", "cricket", "song", "book", "read", "sleep", "rest", "walk", "game",
                "prayer", "video", "phone", "talk"),
            listOf("gaana", "film", "khel", "kitaab", "padhna", "sona", "aaram", "bhajan", "baat")
        ),
        Topic( // feeling
            setOf("feel", "feeling", "mood", "kaise", "kaisa", "kaisi", "tabiyat", "tabiyet", "tabeeyat"),
            listOf("how are you"),
            listOf("good", "fine", "okay", "better", "tired", "sleepy", "sad", "happy", "pain", "weak", "sick", "bored",
                "worried", "scared", "uncomfortable", "cold", "hot", "great", "bad", "worse"),
            listOf("theek", "accha", "badhiya", "thaka", "thaki", "dard", "kamzori", "udaas", "khush", "neend", "bura", "pareshan")
        ),
        Topic( // needs
            setOf("need", "want", "anything", "help", "comfortable", "chahiye", "zaroorat", "kuch", "madad"),
            emptyList(),
            listOf("water", "food", "toilet", "help", "blanket", "fan", "AC", "light", "pillow", "medicine", "rest", "sleep",
                "bathroom", "turn", "position", "nothing", "doctor", "nurse", "phone", "tv"),
            listOf("paani", "khana", "dawai", "kambal", "pankha", "takiya", "batti", "madad", "aaram", "kuch", "nahi")
        )
    )

    private val SEPARATORS = Regex("[^a-z]+")

    /** Answer words for [context], the patient's language first; empty when nothing in it is recognised. */
    @JvmStatic
    fun wordsFor(context: String?, hinglishFirst: Boolean): List<String> {
        val text = context.orEmpty().lowercase()
        if (text.isBlank()) return emptyList()
        val tokens = text.split(SEPARATORS).filter { it.isNotEmpty() }.toSet()
        val out = LinkedHashSet<String>()
        for (topic in TOPICS) {
            if (tokens.none { it in topic.triggers } && topic.phrases.none { it in text }) continue
            out.addAll(if (hinglishFirst) HinglishLexicon.interleave(topic.hinglish, topic.english)
                       else HinglishLexicon.interleave(topic.english, topic.hinglish))
        }
        return out.toList()
    }

    /** The instant words first, then whatever the LLM adds that they do not already cover. */
    @JvmStatic
    fun merge(topicWords: List<String>, llmWords: List<String>): List<String> {
        val out = LinkedHashSet<String>(topicWords)
        val seen = topicWords.mapTo(HashSet()) { it.lowercase() }
        for (w in llmWords) if (seen.add(w.lowercase())) out.add(w)
        return out.toList()
    }
}
