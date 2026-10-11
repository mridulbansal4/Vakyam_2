package com.demo.opencv.AI

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.demo.opencv.BuildConfig
import com.demo.opencv.sensors.PerformanceTier
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * On-device Gemma 4 E2B LLM manager for Vakyam AAC sentence expansion.
 * Replaces cloud Gemini API with local on-device LiteRT-LM execution.
 */
class GemmaManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mContext: Context? = null
    private var activeLanguage = "English"
    @Volatile private var tierOutputTokens = MAX_OUTPUT_TOKENS
    @Volatile private var llmDeferred = false

    fun setPerformanceTier(tier: PerformanceTier) {
        tierOutputTokens = if (tier == PerformanceTier.FULL) MAX_OUTPUT_TOKENS else REDUCED_OUTPUT_TOKENS
        llmDeferred = tier == PerformanceTier.POWER_SAVER
        Log.i(TAG, "performance tier $tier: maxTokens=$tierOutputTokens deferred=$llmDeferred")
    }

    // Romanised Hindi tokenizes longer than English
    internal fun outputTokenBudget(language: String): Int =
        if (language == "Hinglish") maxOf(tierOutputTokens, 32) else tierOutputTokens

    fun init(context: Context) {
        mContext = context.applicationContext
        registerDebugReceiver(context.applicationContext)
        scope.launch {
            initializeEngine()
        }
    }

    // adb shell am broadcast -a com.vakyam.debug.GEMMA --es keywords "paani, thanda" --es language Hinglish [--es context "..."]
    private fun registerDebugReceiver(appContext: Context) {
        if (!BuildConfig.DEBUG || debugReceiverRegistered) return
        debugReceiverRegistered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent ?: return
                generate(intent.getStringExtra("keywords"), intent.getStringExtra("context"), intent.getStringExtra("language"))
            }
        }
        ContextCompat.registerReceiver(appContext, receiver, IntentFilter(DEBUG_ACTION), ContextCompat.RECEIVER_EXPORTED)
    }

    private fun candidateModelPaths(ctx: Context): List<String> {
        val list = mutableListOf<String>()
        val primaryFile = MODEL_FILE_NAME
        val alternateNames = listOf(
            primaryFile,
            "gemma-4-e2b-it.litertlm",
            "gemma-4-E2B.litertlm",
            "gemma-4-e2b.litertlm",
            "gemma-2b-it.litertlm",
            "gemma-2b.litertlm",
            "gemma.litertlm",
            "model.litertlm"
        )

        // App private storage
        list.add(File(ctx.filesDir, primaryFile).absolutePath)
        list.add(File(ctx.filesDir, "models/$primaryFile").absolutePath)

        // External app-specific storage (no permissions required)
        ctx.getExternalFilesDir(null)?.let {
            list.add(File(it, primaryFile).absolutePath)
            list.add(File(it, "models/$primaryFile").absolutePath)
        }
        ctx.getExternalFilesDir("models")?.let {
            list.add(File(it, primaryFile).absolutePath)
        }

        // Device temp / push path
        list.add("/data/local/tmp/$primaryFile")

        // External shared storage paths
        list.add("/sdcard/Android/data/com.vakyam.app/files/$primaryFile")
        list.add("/sdcard/Android/data/com.demo.opencv/files/$primaryFile")
        list.add("/sdcard/Download/$primaryFile")
        list.add("/sdcard/Models/$primaryFile")
        list.add("/sdcard/$primaryFile")

        try {
            val extStorage = Environment.getExternalStorageDirectory()
            if (extStorage != null) {
                list.add(File(extStorage, "Android/data/com.vakyam.app/files/$primaryFile").absolutePath)
                list.add(File(extStorage, "Android/data/com.demo.opencv/files/$primaryFile").absolutePath)
                list.add(File(extStorage, primaryFile).absolutePath)
                list.add(File(extStorage, "Download/$primaryFile").absolutePath)
                list.add(File(extStorage, "Models/$primaryFile").absolutePath)
            }
        } catch (_: Throwable) {}

        // Also search for any *.litertlm or candidate file in known directories
        val searchDirs = listOfNotNull(
            ctx.filesDir,
            File(ctx.filesDir, "models"),
            ctx.getExternalFilesDir(null),
            ctx.getExternalFilesDir("models"),
            File("/data/local/tmp"),
            File("/sdcard/Download"),
            File("/sdcard/Models"),
            File("/sdcard")
        )

        for (dir in searchDirs) {
            try {
                if (dir.exists() && dir.isDirectory) {
                    // Check alternate names first
                    for (alt in alternateNames) {
                        val f = File(dir, alt)
                        if (f.exists() && !list.contains(f.absolutePath)) {
                            list.add(f.absolutePath)
                        }
                    }
                    // Check any file ending in .litertlm
                    val matching = dir.listFiles { file -> file.isFile && file.name.endsWith(".litertlm") }
                    matching?.forEach { file ->
                        if (!list.contains(file.absolutePath)) {
                            list.add(file.absolutePath)
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        return list
    }

    private suspend fun initializeEngine() {
        val ctx = mContext ?: return
        engineMutex.withLock {
            if (sharedEngine != null) return

            try {
                val candidates = candidateModelPaths(ctx)
                val modelFile = candidates.map { File(it) }.firstOrNull { it.exists() && it.canRead() }
                if (modelFile == null) {
                    Log.w(TAG, "Gemma model not found. Searched candidate paths:\n" + candidates.joinToString("\n"))
                    return
                }

                Log.i(TAG, "Found Gemma model at: ${modelFile.absolutePath} (size: ${modelFile.length()} bytes)")

                // Prefer GPU if available, fallback to CPU
                val backends = listOf(
                    "GPU" to { Backend.GPU() },
                    "CPU" to { Backend.CPU() }
                )

                for ((backendName, createBackend) in backends) {
                    try {
                        val config = EngineConfig(
                            modelPath = modelFile.absolutePath,
                            backend = createBackend(),
                            maxNumTokens = MAX_CONTEXT_TOKENS,
                            cacheDir = ctx.cacheDir.absolutePath
                        )
                        val newEngine = Engine(config)
                        newEngine.initialize()

                        // Verify backend can actually execute inference (e.g. GPU might fail if OpenCL driver is inaccessible)
                        val testConv = createConversation(newEngine, "English")
                        try {
                            testConv.sendMessage("test")
                        } finally {
                            runCatching { testConv.close() }
                        }

                        sharedEngine = newEngine
                        Log.i(TAG, "Gemma 4 E2B engine initialized and verified successfully on $backendName")
                        return
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed initializing Gemma on $backendName: ${e.message}")
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Unexpected error during Gemma initialization: ${e.message}", e)
            }
        }
    }

    fun generate(keywords: String?, context: String?, language: String?) {
        val kw = keywords?.trim().orEmpty()
        val ctx = context?.trim().orEmpty()
        val lang = language?.trim() ?: "English"
        activeLanguage = lang

        if (kw.isEmpty()) {
            Log.d(TAG, "Empty keywords, broadcasting empty sentence")
            sendMessage("")
            return
        }

        if (llmDeferred) {
            Log.i(TAG, "Thermal POWER_SAVER: skipping Gemma, using fallback sentence")
            sendMessage(fallbackSentence(kw, lang))
            return
        }

        scope.launch {
            try {
                if (sharedEngine == null) {
                    initializeEngine()
                }

                val activeEngine = sharedEngine
                if (activeEngine == null) {
                    Log.w(TAG, "Gemma engine unavailable, using fallback sentence")
                    val fallback = fallbackSentence(kw, lang)
                    sendMessage(fallback)
                    return@launch
                }

                val startMs = System.currentTimeMillis()
                val generated = expandWithGemma(activeEngine, kw, ctx, lang)
                Log.i(TIMING_TAG, "lang=$lang ms=${System.currentTimeMillis() - startMs} keywords=\"$kw\" -> \"$generated\"")
                val finalSentence = when {
                    generated.isBlank() -> fallbackSentence(kw, lang)
                    // The screen and keyboard are English letters only, so a Devanagari reply is never shown
                    hasDevanagari(generated) -> {
                        Log.w(TAG, "Gemma returned Devanagari, using fallback: $generated")
                        fallbackSentence(kw, lang)
                    }
                    // The patient must never speak a bare word, so an echo of the keywords is expanded instead
                    isBareKeyword(generated, kw) -> {
                        Log.w(TAG, "Gemma echoed the keywords, using fallback: $generated")
                        fallbackSentence(kw, lang)
                    }
                    else -> generated
                }
                sendMessage(finalSentence)
            } catch (e: Throwable) {
                Log.e(TAG, "Error generating sentence with Gemma: ${e.message}", e)
                sendMessage(fallbackSentence(kw, lang))
            }
        }
    }

    private suspend fun expandWithGemma(
        activeEngine: Engine,
        keywords: String,
        context: String,
        language: String
    ): String = engineMutex.withLock {
        val conversation = createConversation(activeEngine, language)
        return cleanSentence(decode(conversation, buildUserTurn(keywords, context), GENERATION_TIMEOUT_MS))
    }

    // Streams the reply, then always closes the conversation; a timeout returns what was generated so far
    private suspend fun decode(conversation: Conversation, prompt: String, timeoutMs: Long): String {
        val builder = StringBuilder()
        try {
            val finished = withTimeoutOrNull(timeoutMs) {
                conversation.sendMessageAsync(prompt).collect { chunk ->
                    val chunkText = chunk.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }
                    builder.append(chunkText)
                }
                true
            }

            if (finished == null) {
                runCatching { conversation.cancelProcess() }
                Log.w(TAG, "Gemma generation timed out after ${timeoutMs}ms")
            }
        } catch (e: CancellationException) {
            runCatching { conversation.cancelProcess() }
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Gemma decode loop failed: ${e.message}")
        } finally {
            runCatching { conversation.close() }
        }
        return builder.toString()
    }

    /**
     * Asks Gemma which words the patient is likely to answer [context] with and broadcasts them as "CWP-",
     * tagged with the context so a reply to an older question is ignored. Without the model nothing is sent
     * and the built-in topic words stay in place.
     */
    fun generateContextWords(context: String?, language: String?) {
        val ctx = context?.trim().orEmpty()
        if (ctx.isEmpty()) return
        if (llmDeferred) {
            Log.i(TAG, "Thermal POWER_SAVER: skipping Gemma context words")
            return
        }
        val lang = language?.trim() ?: "English"
        scope.launch {
            try {
                if (sharedEngine == null) initializeEngine()
                val activeEngine = sharedEngine
                if (activeEngine == null) {
                    Log.w(TAG, "Gemma engine unavailable, keeping built-in context words")
                    return@launch
                }
                val startMs = System.currentTimeMillis()
                val raw = engineMutex.withLock {
                    val (systemPrompt, examples) = wordListPromptAndExamples(lang)
                    val conversation = createConversation(activeEngine, systemPrompt, examples, WORD_LIST_OUTPUT_TOKENS)
                    decode(conversation, buildWordListTurn(ctx), WORD_LIST_TIMEOUT_MS)
                }
                val words = parseWordList(raw)
                Log.i(TIMING_TAG, "words lang=$lang ms=${System.currentTimeMillis() - startMs} context=\"$ctx\" -> $words")
                if (words.isNotEmpty()) sendContextWords(ctx, words)
            } catch (e: Throwable) {
                Log.e(TAG, "Error generating context words with Gemma: ${e.message}", e)
            }
        }
    }

    private fun createConversation(engine: Engine, language: String): Conversation {
        val (systemPrompt, examples) = getPromptAndExamples(language)
        return createConversation(engine, systemPrompt, examples, outputTokenBudget(language))
    }

    private fun createConversation(
        engine: Engine,
        systemPrompt: String,
        examples: List<Pair<String, String>>,
        maxOutputTokens: Int
    ): Conversation {
        return engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                initialMessages = examples.flatMap { (user, model) ->
                    listOf(Message.user(user), Message.model(model))
                },
                samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.3),
                maxOutputToken = maxOutputTokens
            )
        )
    }

    private fun sendContextWords(context: String, words: List<String>) {
        val ctx = mContext ?: return
        val intent = Intent("textGenerationEvent")
        intent.putExtra("message", "CWP-" + words.joinToString(", "))
        intent.putExtra("context", context)
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)
    }

    private fun sendMessage(sentence: String) {
        val ctx = mContext ?: return
        Log.d(TAG, "Broadcasting sentence: $sentence")
        val intent = Intent("textGenerationEvent")
        intent.putExtra("message", "SP-$sentence")
        intent.putExtra("content", sentence)
        intent.putExtra("type", "SP")
        LocalBroadcastManager.getInstance(ctx).sendBroadcast(intent)
    }

    private fun getPromptAndExamples(language: String): Pair<String, List<Pair<String, String>>> = promptAndExamples(language)

    companion object {
        private const val TAG = "GemmaManager"
        private const val TIMING_TAG = "GemmaTiming"
        const val DEBUG_ACTION = "com.vakyam.debug.GEMMA"
        const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        private const val MAX_CONTEXT_TOKENS = 1024
        private const val MAX_OUTPUT_TOKENS = 64
        private const val REDUCED_OUTPUT_TOKENS = 20
        private const val GENERATION_TIMEOUT_MS = 25_000L
        private const val WORD_LIST_OUTPUT_TOKENS = 128
        private const val WORD_LIST_TIMEOUT_MS = 15_000L
        private const val MAX_CONTEXT_WORDS = 40

        private val engineMutex = Mutex()
        @Volatile
        private var sharedEngine: Engine? = null
        @Volatile
        private var isEngineInitializing = false
        @Volatile
        private var debugReceiverRegistered = false

        @Volatile
        private var defaultInstance: GemmaManager? = null

        // Caregivers often ask in Hinglish whatever the patient types in, so both prompts must read it by meaning.
        private const val HINGLISH_CONTEXT_NOTE = "The Context is what the caregiver just said; it may be in English or in Hinglish (Hindi written in English letters). Understand its meaning before answering, e.g. \"aapko khane me kya chahiye\" means \"what do you want to eat\" and \"dard kahan ho raha hai\" means \"where does it hurt\"."
        private const val EXPAND_RULE_ENGLISH = "CRITICAL: Always expand the keywords into a complete, natural, first-person sentence (e.g., 'I want water, please.'). NEVER output only the raw keyword or a single word."
        private const val EXPAND_RULE_HINGLISH = "CRITICAL: Always expand the keywords into a complete, natural, first-person sentence (e.g., 'Mujhe paani chahiye.'). NEVER output only the raw keyword or a single word."

        internal fun promptAndExamples(language: String): Pair<String, List<Pair<String, String>>> = when (language) {
            "Hinglish" -> Pair(
                "You are an AI assistant for a person who cannot speak or move due to ALS/motor neuron disease. The user communicates by selecting keywords with their eyes, typing Hindi in English letters (Hinglish) mixed with English words. Generate ONE natural, grammatical, first-person sentence in Hinglish: Hindi written only in English (Roman) letters, never in Devanagari script, using the keywords and answering the conversation context. Keep any English words the user chose as they are. $EXPAND_RULE_HINGLISH $HINGLISH_CONTEXT_NOTE Reply with the sentence only, without quotes, translation or explanation.",
                listOf(
                    "Keywords: paani, thanda. Context: " to "Mujhe thanda paani chahiye.",
                    "Keywords: dard, peeth. Context: " to "Meri peeth mein dard ho raha hai.",
                    "Keywords: AC, band. Context: " to "AC band kar do please.",
                    "Keywords: roti, dal. Context: aapko khane me kya chahiye" to "Mujhe roti aur dal khani hai.",
                    "Keywords: khush, shukriya. Context: " to "Main bahut khush hoon, aapka bahut shukriya."
                )
            )
            else -> Pair(
                "You are an AI assistant for a person who cannot speak or move due to ALS/motor neuron disease. The user communicates by selecting keywords with their eyes. Your task is to generate ONE natural, grammatical, first-person English sentence using the provided keywords that answers the conversation context. $EXPAND_RULE_ENGLISH $HINGLISH_CONTEXT_NOTE Reply with the sentence only, in English, without quotes or explanation.",
                listOf(
                    "Keywords: chicken. Context: What do you want to eat for dinner" to "I want to have chicken for dinner.",
                    "Keywords: hot, AC, two. Context: is the room temperature ok" to "I am hot, can you turn the AC down by two degrees?",
                    "Keywords: water, cold, please. Context: " to "Could you please bring me some cold water?",
                    "Keywords: rice, curd. Context: aapko khane me kya chahiye" to "I want to eat rice with curd.",
                    "Keywords: happy, thank you. Context: " to "I am happy, thank you so much."
                )
            )
        }

        @JvmStatic
        fun getInstance(): GemmaManager {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: GemmaManager().also { defaultInstance = it }
            }
        }

        private const val WORD_LIST_TASK = "You help a person with ALS/motor neuron disease who answers a caregiver by spelling one word at a time with their eyes. Given what the caregiver just said, list the single words the person is most likely to choose for the answer: the specific foods, drinks, places, people, body parts, feelings, activities or times that fit the question, most likely first."
        private const val WORD_LIST_FORMAT = "Reply with about 30 lowercase single words separated by commas and nothing else: no sentences, numbering or explanation, no repeats, and no filler words such as i, want, the or to."

        internal fun wordListPromptAndExamples(language: String): Pair<String, List<Pair<String, String>>> = when (language) {
            "Hinglish" -> Pair(
                "$WORD_LIST_TASK The person types Hindi in English letters (Hinglish) mixed with English, so list words of both kinds, Hindi only in English (Roman) letters, never in Devanagari script. $HINGLISH_CONTEXT_NOTE $WORD_LIST_FORMAT",
                listOf(
                    "Context: aapko khane me kya chahiye" to "roti, chawal, dal, khichdi, sabzi, paratha, dahi, anda, rice, poha, upma, idli, dosa, soup, kela, seb, phal, paneer, biscuit, mithai, bread, egg, curry, nashta, salad, halwa, pulao, raita, aloo, fruit",
                    "Context: kahan jaana hai" to "ghar, bahar, park, mandir, bazaar, hospital, bagicha, chhat, kamra, market, garden, temple, masjid, gurudwara, dukaan, gaon, office, school, mall, beach, room, bathroom, balcony, terrace, station, doctor, dost, walk, village, river"
                )
            )
            else -> Pair(
                "$WORD_LIST_TASK $HINGLISH_CONTEXT_NOTE $WORD_LIST_FORMAT",
                listOf(
                    "Context: What do you want to eat" to "rice, roti, dal, soup, bread, egg, chicken, curry, fruit, banana, apple, idli, dosa, khichdi, curd, paratha, sandwich, biscuit, salad, noodles, fish, vegetables, porridge, oats, pasta, pizza, sweet, cake, paneer, toast",
                    "Context: Where do you want to go" to "park, home, garden, temple, market, hospital, outside, beach, mall, balcony, terrace, church, mosque, office, village, shop, restaurant, cinema, room, bathroom, bed, walk, friend, school, lake, river, station, city, doctor, airport"
                )
            )
        }

        fun buildWordListTurn(context: String): String = "Context: ${context.trim()}"

        private val WORD_LIST_SEPARATORS = Regex("[,;\\n]+")
        private val LIST_WORD = Regex("[a-z][a-z'-]{1,19}")
        private val FILLER_WORDS = setOf(
            "i", "me", "my", "you", "your", "want", "wants", "would", "like", "need", "the", "a", "an", "to", "of",
            "and", "or", "is", "am", "are", "be", "it", "some", "please", "with", "for", "in", "on", "at", "do"
        )

        /** Gemma's comma list as clean single words: no numbering, filler, phrases, Devanagari or repeats. */
        fun parseWordList(raw: String, limit: Int = MAX_CONTEXT_WORDS): List<String> {
            val out = LinkedHashSet<String>()
            for (part in raw.split(WORD_LIST_SEPARATORS)) {
                val word = part.trim()
                    .trimStart { it.isDigit() || it == '.' || it == ')' || it == '-' || it == '*' || it.isWhitespace() }
                    .trim('"', '“', '”', '\'', '`', '*', '.', ' ')
                    .lowercase()
                if (LIST_WORD.matches(word) && word !in FILLER_WORDS) out.add(word)
                if (out.size >= limit) break
            }
            return out.toList()
        }

        // Must match the few-shot examples, which always carry ". Context: "
        fun buildUserTurn(keywords: String, context: String): String {
            return if (context.isNotBlank()) {
                "Keywords: $keywords. Context: ${context.trim()}"
            } else {
                "Keywords: $keywords. Context: "
            }
        }

        fun cleanSentence(raw: String): String {
            var text = raw.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() } ?: return ""
            text = text.removePrefix("Sentence:").trim()
            text = text.removePrefix("Output:").trim()
            text = text.replace("*", "").trim()
            text = text.trim('"', '“', '”', '\'', '`').trim()
            return text
        }

        internal fun hasDevanagari(text: String): Boolean = text.any { it in '\u0900'..'\u097F' }

        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        // A reply of one word, or one that only repeats the keywords, is not a sentence
        internal fun isBareKeyword(generated: String, keywords: String): Boolean {
            val reply = generated.lowercase().replace(NON_WORD, " ").trim()
            if (reply.isEmpty() || !reply.contains(' ')) return true
            return reply == keywords.lowercase().replace(NON_WORD, " ").trim()
        }

        private val SINGLE_KEYWORD = Regex("[A-Za-z]+")

        // Already a complete reply on their own, so they are spoken as they are
        private val STANDALONE_REPLIES = setOf(
            "yes", "no", "ok", "okay", "hello", "hi", "bye", "thanks", "sorry", "please",
            "haan", "nahi", "namaste", "shukriya", "dhanyavaad"
        )

        private val ENGLISH_REQUESTS = mapOf(
            "water" to "I would like some water, please.",
            "food" to "I would like something to eat, please.",
            "tea" to "Could I please have some tea?",
            "coffee" to "Could I please have some coffee?",
            "medicine" to "I need to take my medicine, please.",
            "toilet" to "I need to use the washroom, please.",
            "washroom" to "I need to use the washroom, please.",
            "help" to "Can someone please help me?",
            "pain" to "I am in pain, please help me.",
            "cold" to "I am feeling cold, please help.",
            "hot" to "I am feeling hot, please adjust the temperature.",
            "hungry" to "I am hungry, I would like something to eat.",
            "tired" to "I am feeling tired and need to rest."
        )

        private val HINGLISH_REQUESTS = mapOf(
            "water" to "Mujhe paani chahiye.",
            "paani" to "Mujhe paani chahiye.",
            "food" to "Mujhe khana chahiye.",
            "khana" to "Mujhe khana chahiye.",
            "tea" to "Mujhe chai chahiye.",
            "chai" to "Mujhe chai chahiye.",
            "coffee" to "Mujhe coffee chahiye.",
            "medicine" to "Mujhe dawai leni hai.",
            "dawai" to "Mujhe dawai leni hai.",
            "toilet" to "Mujhe washroom jaana hai.",
            "washroom" to "Mujhe washroom jaana hai.",
            "help" to "Kripya meri madad karein.",
            "madad" to "Kripya meri madad karein.",
            "pain" to "Mujhe dard ho raha hai.",
            "dard" to "Mujhe dard ho raha hai.",
            "cold" to "Mujhe thand lag rahi hai.",
            "thand" to "Mujhe thand lag rahi hai.",
            "hot" to "Mujhe garmi lag rahi hai.",
            "garmi" to "Mujhe garmi lag rahi hai.",
            "hungry" to "Mujhe bhookh lagi hai.",
            "bhookh" to "Mujhe bhookh lagi hai.",
            "tired" to "Mujhe thakan ho rahi hai.",
            "thakan" to "Mujhe thakan ho rahi hai."
        )

        fun fallbackSentence(keywords: String, language: String? = null): String {
            val clean = keywords.trim().trim(',', '.', ';')
            val lang = language ?: defaultInstance?.activeLanguage ?: "English"
            if (clean.isEmpty()) {
                return if (lang == "Hinglish") "Haan." else "Yes."
            }
            val capitalized = clean.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            // Phrases, punctuation and non-Latin input are kept as the user wrote them
            if (!SINGLE_KEYWORD.matches(clean)) return "$capitalized."

            val word = clean.lowercase()
            if (word in STANDALONE_REPLIES) return "$capitalized."
            return if (lang == "Hinglish") {
                HINGLISH_REQUESTS[word] ?: "Mujhe $clean chahiye."
            } else {
                ENGLISH_REQUESTS[word] ?: "I want $clean, please."
            }
        }
    }
}
