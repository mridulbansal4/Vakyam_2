package com.vakyam.app.aac

/** A quick need is spoken immediately as a fixed sentence, with no LLM round trip. */
data class QuickNeed(val id: String, val label: String, val symbol: String, val sentence: String)

/** A keyword the user can add to the sentence bar. [keyword] is what the LLM sees. */
data class Keyword(val label: String, val symbol: String, val keyword: String = label.lowercase())

data class Category(val id: String, val label: String, val symbol: String, val keywords: List<Keyword>)

/**
 * The AAC vocabulary. This is plain data: adding a quick need, a keyword or a whole category here
 * is all that is needed for it to appear in the UI (see AGENT_CONTEXT.md, "Extension points").
 */
object AacVocabulary {

    const val ALPHABET_CATEGORY_ID = "alphabet"

    val quickNeeds: List<QuickNeed> = listOf(
        QuickNeed("water", "Water", "💧", "I would like some water, please."),
        QuickNeed("pain", "Pain", "⚠️", "I am in pain. Please help me."),
        QuickNeed("lights", "Lights Off", "💡", "Please turn off the lights."),
        QuickNeed("bed", "Adjust Bed", "🛏️", "Please adjust my bed."),
    )

    val categories: List<Category> = listOf(
        Category(
            id = "physical",
            label = "Physical Needs",
            symbol = "🧍",
            keywords = listOf(
                Keyword("Hungry", "🍽️"),
                Keyword("Thirsty", "🥤"),
                Keyword("Toilet", "🚻"),
                Keyword("Cold", "🥶"),
                Keyword("Hot", "🥵"),
                Keyword("Tired", "😴"),
                Keyword("Medicine", "💊"),
                Keyword("Itchy", "🖐️"),
                Keyword("Breathing", "🫁", "trouble breathing"),
                Keyword("Reposition", "↔️", "change my position"),
            ),
        ),
        Category(
            id = "emotions",
            label = "Emotions",
            symbol = "🙂",
            keywords = listOf(
                Keyword("Happy", "😊"),
                Keyword("Sad", "😢"),
                Keyword("Scared", "😨"),
                Keyword("Angry", "😠"),
                Keyword("Lonely", "🫂"),
                Keyword("Love", "❤️"),
                Keyword("Frustrated", "😣"),
                Keyword("Worried", "😟"),
                Keyword("Calm", "😌"),
                Keyword("Grateful", "🙏"),
            ),
        ),
        Category(
            id = "chat",
            label = "Quick Chat",
            symbol = "💬",
            keywords = listOf(
                Keyword("Yes", "✅"),
                Keyword("No", "❌"),
                Keyword("Hello", "👋"),
                Keyword("Please", "🤲"),
                Keyword("Thank You", "🙏", "thank you"),
                Keyword("Help", "🆘"),
                Keyword("Wait", "✋"),
                Keyword("Again", "🔁"),
                Keyword("How Are You", "❓", "how are you"),
                Keyword("Good Night", "🌙", "good night"),
            ),
        ),
        // Alphabet has no keyword list: it opens the two-step letter picker instead.
        Category(id = ALPHABET_CATEGORY_ID, label = "Alphabet", symbol = "🔤", keywords = emptyList()),
    )

    /**
     * Letters are picked in two dwells (group, then letter). 26 single-letter tiles would be far
     * smaller than phone-camera gaze tracking can resolve.
     */
    val letterGroups: List<String> = listOf("ABCDEF", "GHIJKL", "MNOPQR", "STUVWX", "YZ")
}
