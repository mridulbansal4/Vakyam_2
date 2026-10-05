package com.vakyam.app

import com.vakyam.app.aac.AacEffect
import com.vakyam.app.aac.AacIntent
import com.vakyam.app.aac.AacIntentDispatcher
import com.vakyam.app.aac.AacVocabulary
import com.vakyam.app.dwell.DwellDetector
import com.vakyam.app.dwell.GazeRect
import com.vakyam.app.gaze.ScreenPoint
import com.vakyam.app.llm.AacPromptTemplate
import com.vakyam.app.state.AppState
import com.vakyam.app.state.AppStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractionLogicTest {

    private val tileA = GazeRect(0f, 0f, 400f, 400f)
    private val tileB = GazeRect(500f, 0f, 900f, 400f)
    private val insideA = ScreenPoint(200f, 200f)
    private val insideB = ScreenPoint(700f, 200f)

    private fun detector() = DwellDetector(defaultDwellMs = 700, graceMs = 180, cooldownMs = 600, hysteresisPx = 28f)
        .apply {
            setTarget("a", tileA)
            setTarget("b", tileB)
        }

    /** Feeds [point] every 33 ms from [from] until [until]; returns the first selection and its time. */
    private fun DwellDetector.gazeAt(point: ScreenPoint?, from: Long, until: Long): Pair<String, Long>? {
        var t = from
        while (t <= until) {
            onGaze(point, t)?.let { return it to t }
            t += 33
        }
        return null
    }

    // ---- DwellDetector ----

    @Test
    fun dwellFiresAfterThresholdAndReportsProgress() {
        val d = detector()
        assertNull(d.gazeAt(insideA, 0, 400))
        val state = d.state.value
        assertEquals("a", state.targetId)
        assertTrue(state.progress in 0.5f..0.65f)

        val (id, time) = d.gazeAt(insideA, 429, 2_000)!!
        assertEquals("a", id)
        assertTrue("fired at $time", time in 700..740)
        assertNull(d.state.value.targetId)
    }

    @Test
    fun movingToAnotherTargetRestartsTheTimer() {
        val d = detector()
        assertNull(d.gazeAt(insideA, 0, 500))
        val (id, time) = d.gazeAt(insideB, 533, 3_000)!!
        assertEquals("b", id)
        assertTrue("fired at $time", time >= 533 + 700)
    }

    @Test
    fun shortBlinkDoesNotResetDwellButLongLossDoes() {
        val blink = detector()
        blink.gazeAt(insideA, 0, 300)
        blink.gazeAt(null, 333, 430)
        assertEquals("a", blink.gazeAt(insideA, 462, 800)!!.first)

        val lost = detector()
        lost.gazeAt(insideA, 0, 300)
        lost.gazeAt(null, 333, 700)
        assertNull(lost.state.value.targetId)
        assertNull(lost.gazeAt(insideA, 733, 1_300))
    }

    @Test
    fun jitterJustOutsideTheEdgeStaysOnTheCurrentTarget() {
        val d = detector()
        d.gazeAt(insideA, 0, 200)
        // 20 px outside A's right edge: within hysteresis, so the dwell completes.
        assertEquals("a", d.gazeAt(ScreenPoint(420f, 200f), 233, 1_000)!!.first)
    }

    @Test
    fun cooldownBlocksImmediateReselection() {
        val d = detector()
        val (_, firedAt) = d.gazeAt(insideA, 0, 1_000)!!
        // Still looking at the same place: the next selection needs cooldown + a full dwell.
        val (_, secondAt) = d.gazeAt(insideA, firedAt + 33, 5_000)!!
        assertTrue("second at ${secondAt - firedAt}", secondAt - firedAt >= 600 + 700)
    }

    @Test
    fun removedOrPerTargetDwellIsHonoured() {
        val d = detector()
        d.setTarget("slow", GazeRect(0f, 500f, 400f, 900f), dwellMs = 1_500)
        assertNull(d.gazeAt(ScreenPoint(200f, 700f), 0, 1_400))
        assertEquals("slow", d.gazeAt(ScreenPoint(200f, 700f), 1_433, 2_000)!!.first)

        d.removeTarget("a")
        assertNull(d.gazeAt(insideA, 5_000, 7_000))
    }

    // ---- AacIntentDispatcher ----

    @Test
    fun homeShowsQuickNeedsAndCategories() {
        val state = AacIntentDispatcher().state.value
        assertEquals(AacVocabulary.quickNeeds.size + AacVocabulary.categories.size, state.tiles.size)
        assertEquals(state.tiles.size, state.tiles.map { it.id }.toSet().size)
        assertFalse(state.actions.first { it.id == "action:speak" }.enabled)
    }

    @Test
    fun quickNeedSpeaksItsSentenceDirectly() {
        val dispatcher = AacIntentDispatcher()
        val water = dispatcher.findTile("quick:water")!!
        val effect = dispatcher.dispatch(water.intent)
        assertEquals(AacEffect.Speak(AacVocabulary.quickNeeds.first().sentence), effect)
    }

    @Test
    fun keywordsAccumulateAcrossPagesAndExpand() {
        val dispatcher = AacIntentDispatcher()
        dispatcher.dispatch(AacIntent.OpenCategory("physical"))
        val firstPage = dispatcher.state.value.tiles
        assertEquals(AacIntentDispatcher.TILES_PER_PAGE, firstPage.size)
        assertEquals(AacIntent.NextPage, firstPage.last().intent)

        dispatcher.dispatch(firstPage.first().intent)
        dispatcher.dispatch(AacIntent.NextPage)
        val secondPage = dispatcher.state.value.tiles
        assertFalse(secondPage.first().id == firstPage.first().id)
        dispatcher.dispatch(secondPage.first().intent)

        assertEquals(2, dispatcher.state.value.keywords.size)
        val effect = dispatcher.dispatch(AacIntent.ExpandAndSpeak)
        assertEquals(AacEffect.Expand(dispatcher.state.value.keywords), effect)

        dispatcher.onSentenceSpoken()
        assertTrue(dispatcher.state.value.keywords.isEmpty())
    }

    @Test
    fun everyCategoryKeywordIsReachable() {
        for (category in AacVocabulary.categories.filter { it.keywords.isNotEmpty() }) {
            val dispatcher = AacIntentDispatcher()
            dispatcher.dispatch(AacIntent.OpenCategory(category.id))
            val seen = HashSet<String>()
            repeat(5) {
                dispatcher.state.value.tiles.mapNotNull { (it.intent as? AacIntent.AddKeyword)?.keyword }.let(seen::addAll)
                dispatcher.dispatch(AacIntent.NextPage)
            }
            assertEquals(category.keywords.map { it.keyword }.toSet(), seen)
        }
    }

    @Test
    fun spellingAWordTakesTwoStepsPerLetter() {
        val dispatcher = AacIntentDispatcher()
        dispatcher.dispatch(AacIntent.OpenCategory(AacVocabulary.ALPHABET_CATEGORY_ID))
        for (letter in "TEA") {
            val group = AacVocabulary.letterGroups.indexOfFirst { letter in it }
            dispatcher.dispatch(AacIntent.OpenLetterGroup(group))
            assertTrue(dispatcher.state.value.tiles.any { it.intent == AacIntent.TypeLetter(letter) })
            dispatcher.dispatch(AacIntent.TypeLetter(letter))
        }
        assertEquals("TEA", dispatcher.state.value.letterBuffer)

        dispatcher.dispatch(AacIntent.Backspace)
        assertEquals("TE", dispatcher.state.value.letterBuffer)
        dispatcher.dispatch(AacIntent.TypeLetter('A'))

        // Speak commits the word being spelled.
        assertEquals(AacEffect.Expand(listOf("tea")), dispatcher.dispatch(AacIntent.ExpandAndSpeak))
    }

    @Test
    fun speakWithNothingComposedDoesNothing() {
        assertEquals(AacEffect.None, AacIntentDispatcher().dispatch(AacIntent.ExpandAndSpeak))
    }

    // ---- Prompt template ----

    @Test
    fun promptAndCleanup() {
        assertEquals("Keywords: water, cold, please", AacPromptTemplate.userTurn(listOf("water", "cold", "please")))
        assertEquals(
            "Could you please bring me some cold water?",
            AacPromptTemplate.cleanSentence("\n \"Could you please bring me some cold water?\"\nExtra line"),
        )
        assertEquals("I am in pain.", AacPromptTemplate.cleanSentence("Sentence: **I am in pain.**"))
        assertEquals("", AacPromptTemplate.cleanSentence("  \n "))
        assertEquals("Pain, back.", AacPromptTemplate.fallbackSentence(listOf("pain", "back")))
    }

    // ---- State machine ----

    @Test
    fun stateMachineFollowsTheHappyPathAndRejectsIllegalEdges() {
        val machine = AppStateMachine()
        assertFalse(machine.transition(AppState.GAZE_ACTIVE))
        val path = listOf(
            AppState.PERMISSION_CHECK, AppState.CALIBRATION_REQUIRED, AppState.CALIBRATING_5_POINT,
            AppState.GAZE_ACTIVE, AppState.DWELL_TRIGGERED, AppState.LLM_EXPANDING, AppState.SPEAKING,
            AppState.GAZE_ACTIVE,
        )
        for (state in path) assertTrue("-> $state", machine.transition(state))

        assertFalse(machine.transition(AppState.SPEAKING))
        // Compare-and-set: only one of two racing selections wins.
        assertTrue(machine.transition(AppState.DWELL_TRIGGERED, from = AppState.GAZE_ACTIVE))
        assertFalse(machine.transition(AppState.DWELL_TRIGGERED, from = AppState.GAZE_ACTIVE))
        assertEquals(AppState.entries.toSet(), AppStateMachine.ALLOWED.keys)
    }
}
