package com.vakyam.app.llm

/**
 * Prompting for keyword-to-sentence expansion.
 *
 * The instruction is kept short and paired with a few worked examples: a 2B model follows a
 * demonstrated format far more reliably than a described one, and a short preface keeps the
 * prefill cost (paid once per conversation) low.
 */
object AacPromptTemplate {

    const val SYSTEM_INSTRUCTION: String =
        "You are the voice of a person who cannot speak or move, and who communicates by " +
            "selecting keywords with their eyes. Turn the keywords into ONE short, natural, polite " +
            "sentence in the first person, exactly as that person would say it aloud. Use only " +
            "what the keywords mean and never invent details. Reply with the sentence only, " +
            "without quotes or explanations."

    /** Few-shot examples as (user turn, model turn) pairs. */
    val EXAMPLES: List<Pair<String, String>> = listOf(
        userTurn(listOf("water", "cold", "please")) to "Could you please bring me some cold water?",
        userTurn(listOf("pain", "back")) to "I am having pain in my back.",
        userTurn(listOf("happy", "thank you")) to "I am feeling happy, thank you so much.",
        userTurn(listOf("no")) to "No, thank you.",
    )

    fun userTurn(keywords: List<String>): String = "Keywords: " + keywords.joinToString(", ")

    /** Spoken when the model is unavailable: the keywords themselves, readably joined. */
    fun fallbackSentence(keywords: List<String>): String =
        keywords.joinToString(", ").replaceFirstChar { it.uppercase() } + "."

    /**
     * Reduces raw model output to one speakable sentence: first non-empty line, without any
     * label, quotes or markdown emphasis the model may have added.
     */
    fun cleanSentence(raw: String): String {
        var text = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        text = text.removePrefix("Sentence:").trim()
        text = text.replace("*", "").trim()
        text = text.trim('"', '“', '”', '\'', '`').trim()
        return text
    }
}
