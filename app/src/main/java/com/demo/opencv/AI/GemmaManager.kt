package com.demo.opencv.AI

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
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
 * On-device Gemma 4 E2B LLM manager for Gaze-Link AAC sentence expansion.
 * Replaces cloud Gemini API with local on-device LiteRT-LM execution.
 */
class GemmaManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mContext: Context? = null
    private var activeLanguage = "English"

    fun init(context: Context) {
        mContext = context.applicationContext
        scope.launch {
            initializeEngine()
        }
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

        scope.launch {
            try {
                if (sharedEngine == null) {
                    initializeEngine()
                }

                val activeEngine = sharedEngine
                if (activeEngine == null) {
                    Log.w(TAG, "Gemma engine unavailable, using fallback sentence")
                    val fallback = fallbackSentence(kw)
                    sendMessage(fallback)
                    return@launch
                }

                val generated = expandWithGemma(activeEngine, kw, ctx, lang)
                val finalSentence = if (generated.isNotBlank()) {
                    generated
                } else {
                    fallbackSentence(kw)
                }
                sendMessage(finalSentence)
            } catch (e: Throwable) {
                Log.e(TAG, "Error generating sentence with Gemma: ${e.message}", e)
                sendMessage(fallbackSentence(kw))
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
        val prompt = buildUserTurn(keywords, context)
        val builder = StringBuilder()

        try {
            val finished = withTimeoutOrNull(GENERATION_TIMEOUT_MS) {
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
                Log.w(TAG, "Gemma generation timed out after ${GENERATION_TIMEOUT_MS}ms")
            }
        } catch (e: CancellationException) {
            runCatching { conversation.cancelProcess() }
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Gemma decode loop failed: ${e.message}")
        } finally {
            runCatching { conversation.close() }
        }

        return cleanSentence(builder.toString())
    }

    private fun createConversation(engine: Engine, language: String): Conversation {
        val (systemPrompt, examples) = getPromptAndExamples(language)
        return engine.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                initialMessages = examples.flatMap { (user, model) ->
                    listOf(Message.user(user), Message.model(model))
                },
                samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.3),
                maxOutputToken = MAX_OUTPUT_TOKENS
            )
        )
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

    private fun getPromptAndExamples(language: String): Pair<String, List<Pair<String, String>>> {
        return when (language) {
            "Chinese" -> Pair(
                "你是运动神经元疾病患者的人工智能助手，用来帮助他们进行对话交流。你的目标是使用对话的语境和用户输入的关键字来生成语法连贯的第一人称句子。只输出生成的句子，不要包含引号或多余的解释。",
                listOf(
                    "Keywords: 水, 喝, 请. Context: " to "请帮我倒一杯水，谢谢。",
                    "Keywords: 鸡肉. Context: 你晚餐想吃什么" to "我晚餐想吃鸡肉。",
                    "Keywords: 疼, 背部. Context: " to "我的背部有点疼。"
                )
            )
            "Spanish" -> Pair(
                "Eres un asistente de IA para pacientes con enfermedades de la motoneurona para comunicarse en una conversación. Tu objetivo es generar una oración gramatical y coherente en primera persona con las palabras clave que escribieron y que se ajuste al contexto de la conversación. Responde solo con la oración, sin comillas ni explicaciones.",
                listOf(
                    "Keywords: agua, por favor. Context: " to "¿Podrías traerme un poco de agua, por favor?",
                    "Keywords: pollo. Context: Qué quieres cenar" to "Quiero cenar pollo.",
                    "Keywords: dolor, espalda. Context: " to "Tengo dolor en la espalda."
                )
            )
            "Hindi" -> Pair(
                "You are an AI assistant for a person who cannot speak or move. The user communicates by selecting Hindi keywords. Generate ONE natural, grammatical, first-person Hindi sentence in Devanagari script using the keywords that fits the conversation context. Reply with the sentence only.",
                listOf(
                    "Keywords: पानी, ठंडा. Context: " to "मुझे ठंडा पानी चाहिए।",
                    "Keywords: दर्द, पीठ. Context: " to "मेरी पीठ में दर्द हो रहा है।",
                    "Keywords: खाना, क्या. Context: " to "खाने में क्या है?"
                )
            )
            else -> Pair(
                "You are an AI assistant for a person who cannot speak or move due to ALS/motor neuron disease. The user communicates by selecting keywords with their eyes. Your task is to generate ONE natural, grammatical, first-person sentence using the provided keywords that fits the conversation context. Reply with the sentence only, without quotes or explanation.",
                listOf(
                    "Keywords: chicken. Context: What do you want to eat for dinner" to "I want to have chicken for dinner.",
                    "Keywords: hot, AC, two. Context: is the room temperature ok" to "I am hot, can you turn the AC down by two degrees?",
                    "Keywords: water, cold, please. Context: " to "Could you please bring me some cold water?",
                    "Keywords: pain, back. Context: " to "I am having pain in my back.",
                    "Keywords: happy, thank you. Context: " to "I am happy, thank you so much."
                )
            )
        }
    }

    companion object {
        private const val TAG = "GemmaManager"
        const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"
        private const val MAX_CONTEXT_TOKENS = 1024
        private const val MAX_OUTPUT_TOKENS = 64
        private const val GENERATION_TIMEOUT_MS = 25_000L

        private val engineMutex = Mutex()
        @Volatile
        private var sharedEngine: Engine? = null
        @Volatile
        private var isEngineInitializing = false

        @Volatile
        private var defaultInstance: GemmaManager? = null

        @JvmStatic
        fun getInstance(): GemmaManager {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: GemmaManager().also { defaultInstance = it }
            }
        }

        fun buildUserTurn(keywords: String, context: String): String {
            return if (context.isNotBlank()) {
                "Keywords: $keywords. Context: $context"
            } else {
                "Keywords: $keywords"
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

        fun fallbackSentence(keywords: String, language: String? = null): String {
            val clean = keywords.trim().trim(',', '.', ';')
            val lang = language ?: defaultInstance?.activeLanguage ?: "English"
            if (clean.isEmpty()) {
                return if (lang == "Hindi") "हाँ।" else "Yes."
            }
            val capitalized = clean.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            val suffix = if (lang == "Hindi") "।" else "."
            return capitalized + suffix
        }
    }
}
