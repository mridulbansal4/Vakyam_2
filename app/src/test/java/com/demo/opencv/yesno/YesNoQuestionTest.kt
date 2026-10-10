package com.demo.opencv.yesno

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YesNoQuestionTest {

    private fun yes(vararg sentences: String) = sentences.forEach { assertTrue(it, YesNoQuestion.isYesNo(it)) }
    private fun no(vararg sentences: String) = sentences.forEach { assertFalse(it, YesNoQuestion.isYesNo(it)) }

    @Test
    fun englishQuestionsOpeningWithAHelpingVerb() = yes(
        "are you in pain",
        "Do you want some water?",
        "is the room too cold",
        "can you hear me",
        "would you like to sleep now",
        "did you take your medicine",
        "don't you want to eat",
        "have you eaten",
    )

    @Test
    fun englishQuestionsThatNeedMoreThanYesOrNo() = no(
        "what do you want to eat",
        "how are you feeling",
        "where does it hurt",
        "do you want tea or coffee",
        "who is coming today",
        "tell me what you need",
    )

    @Test
    fun orNotStillWantsAYesOrNo() = yes("do you want to go out or not", "aapko khana chahiye ya nahi kya")

    @Test
    fun hinglishYesNoQuestions() = yes(
        "kya aapko dard ho raha hai",
        "kya tumhe paani chahiye",
        "dard ho raha hai kya",
        "aapko bhook lagi hai na",
        "Kya aap theek hain?",
    )

    @Test
    fun hinglishQuestionsThatNeedMoreThanYesOrNo() = no(
        "kya chahiye aapko",
        "kya hua",
        "aap kya khana chahte ho",
        "aapko kahan dard hai",
        "kya aap chai ya coffee lenge",
        "kaun aaya hai kya",
        "kitne baje sona hai",
    )

    @Test
    fun statementsAndEmptyInput() = no(
        "i am going to the market",
        "the doctor will come at five",
        "khana ready hai",
        "yes",
        "",
        null.toString(),
    )

    @Test
    fun nullIsNotAQuestion() = assertFalse(YesNoQuestion.isYesNo(null))
}
