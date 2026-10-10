package com.demo.opencv.other

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfflineSpeechRecognizerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val quiet = 0.002f
    private val speech = 0.05f

    /** Feeds [count] 100 ms chunks and returns how many were accepted before the endpointer asked to stop (-1 = never). */
    private fun feed(endpointer: SpeechEndpointer, rms: Float, count: Int): Int {
        for (i in 1..count) if (endpointer.accept(rms, 100L)) return i
        return -1
    }

    @Test
    fun stopsAfterTrailingSilenceOnceSpeechWasHeard() {
        val endpointer = SpeechEndpointer()
        assertEquals(-1, feed(endpointer, quiet, 5))
        assertEquals(-1, feed(endpointer, speech, 10))
        assertTrue(endpointer.heardSpeech)
        assertEquals(12, feed(endpointer, quiet, 30)) // 1200 ms of silence
    }

    @Test
    fun givesUpWhenNobodySpeaks() {
        val endpointer = SpeechEndpointer()
        assertEquals(70, feed(endpointer, quiet, 100)) // 7 s
        assertFalse(endpointer.heardSpeech)
    }

    @Test
    fun singleLoudChunkIsNotSpeech() {
        val endpointer = SpeechEndpointer()
        feed(endpointer, quiet, 3)
        feed(endpointer, speech, 1) // e.g. the tap on the record button
        feed(endpointer, quiet, 20)
        assertFalse(endpointer.heardSpeech)
    }

    @Test
    fun adaptsToANoisyRoom() {
        val endpointer = SpeechEndpointer()
        feed(endpointer, 0.02f, 20) // steady background noise well above the minimum speech level
        assertFalse(endpointer.heardSpeech)
        feed(endpointer, 0.1f, 5)
        assertTrue(endpointer.heardSpeech)
    }

    @Test
    fun shortPausesBetweenWordsDoNotEndTheRecording() {
        val endpointer = SpeechEndpointer()
        feed(endpointer, quiet, 3)
        feed(endpointer, speech, 5)
        assertEquals(-1, feed(endpointer, quiet, 8))
        assertEquals(-1, feed(endpointer, speech, 5))
        assertEquals(12, feed(endpointer, quiet, 30))
    }

    @Test
    fun cleanTranscriptNormalisesWhisperOutput() {
        assertEquals("do you want some water?", OfflineSpeechRecognizer.cleanTranscript(" Do you want  some water?"))
        assertEquals("", OfflineSpeechRecognizer.cleanTranscript(" [BLANK_AUDIO] "))
        assertEquals("hello there", OfflineSpeechRecognizer.cleanTranscript("(music) Hello there"))
        assertEquals("क्या आप पानी पिएंगे?", OfflineSpeechRecognizer.cleanTranscript(" क्या आप पानी पिएंगे?"))
        assertEquals("", OfflineSpeechRecognizer.cleanTranscript(null))
    }

    @Test
    fun findsModelFilesAndPrefersInt8() {
        val dir = folder.newFolder("asr")
        assertNull(OfflineSpeechRecognizer.modelIn(dir))
        assertNull(OfflineSpeechRecognizer.modelIn(File(dir, "missing")))

        listOf("base-encoder.onnx", "base-encoder.int8.onnx", "base-decoder.int8.onnx").forEach { File(dir, it).writeText("x") }
        assertNull("tokens file is required", OfflineSpeechRecognizer.modelIn(dir))

        File(dir, "base-tokens.txt").writeText("x")
        val model = OfflineSpeechRecognizer.modelIn(dir)!!
        assertEquals(File(dir, "base-encoder.int8.onnx").absolutePath, model.encoder)
        assertEquals(File(dir, "base-decoder.int8.onnx").absolutePath, model.decoder)
        assertEquals(File(dir, "base-tokens.txt").absolutePath, model.tokens)
    }
}
