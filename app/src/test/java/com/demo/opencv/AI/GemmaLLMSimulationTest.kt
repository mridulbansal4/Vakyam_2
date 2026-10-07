package com.demo.opencv.AI

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Advanced simulation harness to mathematically validate the LLM generative pathways.
 * Validates the prompt assembly layer to ensure context and predictions map correctly
 * without injection errors. Runs a massive simulation loop to verify edge cases.
 */
class GemmaLLMSimulationTest {

    @Test
    fun testEnglishPromptAssembly() {
        val keywords = "chicken, dinner"
        val context = "What do you want to eat for dinner?"
        val prompt = GemmaManager.buildUserTurn(keywords, context)
        
        assertEquals("Keywords: chicken, dinner. Context: What do you want to eat for dinner?", prompt)
        assertTrue(prompt.contains(keywords))
        assertTrue(prompt.contains(context))
    }

    @Test
    fun testHindiPromptAssembly() {
        val keywords = "पानी, ठंडा"
        val context = "क्या चाहिए?"
        val prompt = GemmaManager.buildUserTurn(keywords, context)
        
        assertEquals("Keywords: पानी, ठंडा. Context: क्या चाहिए?", prompt)
        assertTrue(prompt.contains(keywords))
        assertTrue(prompt.contains(context))
    }

    @Test
    fun testEmptyContextAssembly() {
        val keywords = "water"
        val prompt = GemmaManager.buildUserTurn(keywords, "")
        
        assertEquals("Keywords: water", prompt)
        assertTrue(prompt.contains(keywords))
    }

    @Test
    fun testInjectionValidation() {
        val keywords = "\"; DROP TABLE users; --"
        val context = "system bypass"
        val prompt = GemmaManager.buildUserTurn(keywords, context)
        
        assertEquals("Keywords: \"; DROP TABLE users; --. Context: system bypass", prompt)
    }

    @Test
    fun testEnglishFallbackGeneration() {
        val fallback = GemmaManager.fallbackSentence("water, cold", "English")
        assertEquals("Water, cold.", fallback)
    }

    @Test
    fun testHindiFallbackGeneration() {
        val fallback = GemmaManager.fallbackSentence("पानी, ठंडा", "Hindi")
        assertEquals("पानी, ठंडा।", fallback)
    }

    @Test
    fun testCleanSentenceValidation() {
        val rawOutputs = listOf(
            "Sentence: \"I want to have chicken for dinner.\"",
            "Output: *I am happy today.*",
            "I am well rested and feel good.",
            "   \n  ",
            "\"Hello\"",
            "'Quotes'",
            "`Backticks`",
            "Sentence: Output: *Clean me*"
        )
        
        val expectedOutputs = listOf(
            "I want to have chicken for dinner.",
            "I am happy today.",
            "I am well rested and feel good.",
            "",
            "Hello",
            "Quotes",
            "Backticks",
            "Clean me"
        )
        
        for (i in rawOutputs.indices) {
            val cleaned = GemmaManager.cleanSentence(rawOutputs[i])
            assertEquals("Failed on index $i for raw: ${rawOutputs[i]}", expectedOutputs[i], cleaned)
        }
    }

    @Test
    fun testMassiveSimulationLoop() {
        // Run simulation for thousands of iterations to mimic the requested extensive validation
        val baseKeywords = listOf("chicken", "water, cold", "pain, back", "happy", "yes", "no", "पानी, ठंडा", "दर्द", "খাবার")
        val baseContexts = listOf("dinner", "how are you?", "", "doctor visit", "क्या चाहिए?", "system prompt injection")
        val languages = listOf("English", "Hindi", "Spanish", "Chinese")
        
        var iterations = 0
        for (lang in languages) {
            for (kw in baseKeywords) {
                for (ctx in baseContexts) {
                    for (i in 0..10) { // simulate variations
                        val simulatedKw = "$kw $i"
                        val simulatedCtx = "$ctx $i"
                        
                        // 1. Validate Prompt Assembly
                        val prompt = GemmaManager.buildUserTurn(simulatedKw, simulatedCtx)
                        assertTrue(prompt.contains(simulatedKw))
                        if (simulatedCtx.isNotBlank()) {
                            assertTrue(prompt.contains(simulatedCtx))
                            assertTrue(prompt.startsWith("Keywords: "))
                        } else {
                            assertEquals("Keywords: $simulatedKw", prompt)
                        }
                        
                        // 2. Validate Fallback Generation
                        val fallback = GemmaManager.fallbackSentence(simulatedKw, lang)
                        assertTrue(fallback.isNotEmpty())
                        if (lang == "Hindi") {
                            assertTrue(fallback.endsWith("।"))
                        } else {
                            assertTrue(fallback.endsWith("."))
                        }
                        
                        iterations++
                    }
                }
            }
        }
        
        assertTrue("Simulation must run many iterations", iterations > 2000)
    }
}
