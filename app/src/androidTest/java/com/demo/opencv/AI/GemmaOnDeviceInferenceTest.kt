package com.demo.opencv.AI

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GemmaOnDeviceInferenceTest {

    @Test
    fun testOnDeviceGemmaSentenceGeneration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val gemma = GemmaManager.getInstance()
        gemma.init(context)

        val latch = CountDownLatch(1)
        var receivedSentence: String? = null

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val message = intent?.getStringExtra("message")
                val content = intent?.getStringExtra("content")
                if (message?.startsWith("SP-") == true || intent?.getStringExtra("type") == "SP") {
                    receivedSentence = content ?: message?.removePrefix("SP-")
                    latch.countDown()
                }
            }
        }

        LocalBroadcastManager.getInstance(context).registerReceiver(
            receiver,
            IntentFilter("textGenerationEvent")
        )

        try {
            gemma.generate("water please", "thirsty", "English")
            val received = latch.await(90, TimeUnit.SECONDS)
            assertTrue("Expected sentence generation broadcast within 90 seconds", received)
            assertNotNull("Sentence should not be null", receivedSentence)
            assertTrue("Sentence should not be empty", receivedSentence!!.isNotBlank())
            println("On-device Gemma generated: $receivedSentence")
        } finally {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver)
        }
    }

    @Test
    fun testEmptyKeywordsBroadcastsEmptySentence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val gemma = GemmaManager.getInstance()
        gemma.init(context)

        val latch = CountDownLatch(1)
        var receivedMessage: String? = null

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                receivedMessage = intent?.getStringExtra("content") ?: intent?.getStringExtra("message")?.removePrefix("SP-")
                latch.countDown()
            }
        }

        LocalBroadcastManager.getInstance(context).registerReceiver(
            receiver,
            IntentFilter("textGenerationEvent")
        )

        try {
            gemma.generate("", "context", "English")
            val received = latch.await(5, TimeUnit.SECONDS)
            assertTrue("Expected empty sentence broadcast immediately", received)
            assertTrue("Expected empty string", receivedMessage?.isEmpty() == true)
        } finally {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver)
        }
    }

    @Test
    fun testNoContextSentenceGeneration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val gemma = GemmaManager.getInstance()
        gemma.init(context)

        val latch = CountDownLatch(1)
        var receivedSentence: String? = null

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val message = intent?.getStringExtra("message")
                val content = intent?.getStringExtra("content")
                if (message?.startsWith("SP-") == true || intent?.getStringExtra("type") == "SP") {
                    receivedSentence = content ?: message?.removePrefix("SP-")
                    latch.countDown()
                }
            }
        }

        LocalBroadcastManager.getInstance(context).registerReceiver(
            receiver,
            IntentFilter("textGenerationEvent")
        )

        try {
            gemma.generate("help emergency", "", "English")
            val received = latch.await(45, TimeUnit.SECONDS)
            assertTrue("Expected sentence generation broadcast within 45 seconds", received)
            assertNotNull("Sentence should not be null", receivedSentence)
            assertTrue("Sentence should not be empty", receivedSentence!!.isNotBlank())
            println("On-device Gemma generated without context: $receivedSentence")
        } finally {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver)
        }
    }
}
