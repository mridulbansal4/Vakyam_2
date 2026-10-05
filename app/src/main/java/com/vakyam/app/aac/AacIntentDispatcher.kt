package com.vakyam.app.aac

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What activating a tile means. */
sealed interface AacIntent {
    data class SpeakPhrase(val sentence: String) : AacIntent
    data class OpenCategory(val categoryId: String) : AacIntent
    data class AddKeyword(val keyword: String) : AacIntent
    data object NextPage : AacIntent
    data class OpenLetterGroup(val groupIndex: Int) : AacIntent
    data class TypeLetter(val letter: Char) : AacIntent
    data object CommitWord : AacIntent
    data object Backspace : AacIntent
    data object GoHome : AacIntent
    data object ExpandAndSpeak : AacIntent
}

/** Work the caller (the ViewModel) must carry out after an intent has been reduced. */
sealed interface AacEffect {
    data object None : AacEffect
    data class Speak(val sentence: String) : AacEffect
    data class Expand(val keywords: List<String>) : AacEffect
}

enum class TileStyle { QUICK_NEED, CATEGORY, KEYWORD, LETTER, NAVIGATION, ACTION, PRIMARY_ACTION }

/**
 * @property id unique among the tiles on screen; also the dwell target id.
 * @property enabled a disabled tile is drawn dimmed and is not a gaze target.
 * @property dwellMs overrides the default dwell duration for this tile.
 */
data class AacTile(
    val id: String,
    val label: String,
    val symbol: String,
    val intent: AacIntent,
    val style: TileStyle,
    val enabled: Boolean = true,
    val dwellMs: Long? = null,
)

/**
 * @property tiles the content grid, laid out two per row.
 * @property actions the fixed bottom row.
 * @property keywords keywords collected so far, in selection order.
 * @property letterBuffer the word currently being spelled in the alphabet picker.
 */
data class AacUiState(
    val title: String,
    val tiles: List<AacTile>,
    val actions: List<AacTile>,
    val keywords: List<String>,
    val letterBuffer: String,
)

/**
 * Reducer for the AAC board: holds which page is showing and what the user has composed, turns an
 * [AacIntent] into a new [AacUiState], and reports the side effect to run. It performs no I/O, so
 * the LLM and TTS stay out of the board logic.
 *
 * Call [dispatch] from one thread at a time (the ViewModel serialises selections through the app
 * state machine).
 */
class AacIntentDispatcher {

    private sealed interface Page {
        data object Home : Page
        data class CategoryPage(val category: Category, val pageIndex: Int) : Page
        data object LetterGroups : Page
        data class Letters(val groupIndex: Int) : Page
    }

    private var page: Page = Page.Home
    private var keywords: List<String> = emptyList()
    private var letterBuffer: String = ""

    private val _state = MutableStateFlow(buildState())
    val state: StateFlow<AacUiState> = _state.asStateFlow()

    fun findTile(id: String): AacTile? {
        val current = _state.value
        return current.tiles.firstOrNull { it.id == id } ?: current.actions.firstOrNull { it.id == id }
    }

    fun dispatch(intent: AacIntent): AacEffect {
        var effect: AacEffect = AacEffect.None
        when (intent) {
            is AacIntent.SpeakPhrase -> effect = AacEffect.Speak(intent.sentence)

            is AacIntent.OpenCategory -> {
                page = if (intent.categoryId == AacVocabulary.ALPHABET_CATEGORY_ID) {
                    Page.LetterGroups
                } else {
                    val category = AacVocabulary.categories.first { it.id == intent.categoryId }
                    Page.CategoryPage(category, 0)
                }
            }

            is AacIntent.AddKeyword -> addKeyword(intent.keyword)

            AacIntent.NextPage -> (page as? Page.CategoryPage)?.let { current ->
                val pageCount = keywordPages(current.category).size
                page = current.copy(pageIndex = (current.pageIndex + 1) % pageCount)
            }

            is AacIntent.OpenLetterGroup -> page = Page.Letters(intent.groupIndex)

            is AacIntent.TypeLetter -> {
                if (letterBuffer.length < MAX_WORD_LENGTH) letterBuffer += intent.letter
                page = Page.LetterGroups
            }

            AacIntent.CommitWord -> commitWord()

            AacIntent.Backspace -> when {
                letterBuffer.isNotEmpty() -> letterBuffer = letterBuffer.dropLast(1)
                keywords.isNotEmpty() -> keywords = keywords.dropLast(1)
            }

            AacIntent.GoHome -> page = Page.Home

            AacIntent.ExpandAndSpeak -> {
                commitWord()
                if (keywords.isNotEmpty()) effect = AacEffect.Expand(keywords)
            }
        }
        _state.value = buildState()
        return effect
    }

    /** Clears the composed keywords once their sentence has been spoken, and returns home. */
    fun onSentenceSpoken() {
        keywords = emptyList()
        letterBuffer = ""
        page = Page.Home
        _state.value = buildState()
    }

    private fun addKeyword(keyword: String) {
        if (keywords.size < MAX_KEYWORDS) keywords = keywords + keyword
    }

    private fun commitWord() {
        if (letterBuffer.isNotEmpty()) {
            addKeyword(letterBuffer.lowercase())
            letterBuffer = ""
        }
    }

    private fun buildState(): AacUiState {
        val (title, tiles) = when (val current = page) {
            Page.Home -> "What would you like to say?" to homeTiles()
            is Page.CategoryPage -> current.category.label to categoryTiles(current)
            Page.LetterGroups -> "Spell a word" to letterGroupTiles()
            is Page.Letters -> "Pick a letter" to letterTiles(current.groupIndex)
        }
        val hasContent = keywords.isNotEmpty() || letterBuffer.isNotEmpty()
        val actions = listOf(
            AacTile("action:home", "Home", "🏠", AacIntent.GoHome, TileStyle.ACTION, enabled = page != Page.Home),
            AacTile("action:delete", "Delete", "⌫", AacIntent.Backspace, TileStyle.ACTION, enabled = hasContent),
            AacTile(
                "action:speak", "Speak", "🔊", AacIntent.ExpandAndSpeak, TileStyle.PRIMARY_ACTION,
                enabled = hasContent,
                // Speaking is the one irreversible action on the board, so it takes a longer look.
                dwellMs = SPEAK_DWELL_MS,
            ),
        )
        return AacUiState(title, tiles, actions, keywords, letterBuffer)
    }

    private fun homeTiles(): List<AacTile> =
        AacVocabulary.quickNeeds.map {
            AacTile("quick:${it.id}", it.label, it.symbol, AacIntent.SpeakPhrase(it.sentence), TileStyle.QUICK_NEED)
        } + AacVocabulary.categories.map {
            AacTile("category:${it.id}", it.label, it.symbol, AacIntent.OpenCategory(it.id), TileStyle.CATEGORY)
        }

    private fun keywordPages(category: Category): List<List<Keyword>> =
        if (category.keywords.size <= TILES_PER_PAGE) {
            listOf(category.keywords)
        } else {
            // One slot on every page is taken by the "More" tile.
            category.keywords.chunked(TILES_PER_PAGE - 1)
        }

    private fun categoryTiles(current: Page.CategoryPage): List<AacTile> {
        val pages = keywordPages(current.category)
        val prefix = "kw:${current.category.id}:${current.pageIndex}"
        val tiles = pages[current.pageIndex].map {
            AacTile("$prefix:${it.keyword}", it.label, it.symbol, AacIntent.AddKeyword(it.keyword), TileStyle.KEYWORD)
        }
        return if (pages.size > 1) {
            tiles + AacTile("$prefix:more", "More", "➡️", AacIntent.NextPage, TileStyle.NAVIGATION)
        } else {
            tiles
        }
    }

    private fun letterGroupTiles(): List<AacTile> =
        AacVocabulary.letterGroups.mapIndexed { index, group ->
            AacTile(
                id = "letters:group:$index",
                label = if (group.length > 2) "${group.first()} – ${group.last()}" else group.toList().joinToString(" "),
                symbol = "",
                intent = AacIntent.OpenLetterGroup(index),
                style = TileStyle.LETTER,
            )
        } + AacTile(
            "letters:commit", "Add Word", "➕", AacIntent.CommitWord, TileStyle.NAVIGATION,
            enabled = letterBuffer.isNotEmpty(),
        )

    private fun letterTiles(groupIndex: Int): List<AacTile> =
        AacVocabulary.letterGroups[groupIndex].map { letter ->
            AacTile("letters:$groupIndex:$letter", letter.toString(), "", AacIntent.TypeLetter(letter), TileStyle.LETTER)
        }

    companion object {
        const val TILES_PER_PAGE = 6
        const val MAX_KEYWORDS = 8
        const val MAX_WORD_LENGTH = 16
        const val SPEAK_DWELL_MS = 1000L
    }
}
