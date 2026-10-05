package com.vakyam.app.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.vakyam.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

sealed interface LlmState {
    data object NotLoaded : LlmState
    data object Loading : LlmState
    data class Ready(val backendName: String) : LlmState

    /** The model file is on none of the [searchedPaths]. */
    data class ModelMissing(val searchedPaths: List<String>) : LlmState

    data class Failed(val message: String) : LlmState
}

/**
 * On-device Gemma 4 E2B through the LiteRT-LM runtime.
 *
 * The model file is memory-mapped by the runtime, so its ~2.6 GB on disk is not 2.6 GB of app
 * heap: pages are file-backed and reclaimable by the kernel. Only the text decoder is initialised
 * (no vision/audio backends), which keeps resident memory inside the app's budget.
 *
 * All blocking runtime calls run on [ioDispatcher].
 */
class GemmaLlmManager(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow<LlmState>(LlmState.NotLoaded)
    val state: StateFlow<LlmState> = _state.asStateFlow()

    /** Serialises every use of the engine: it supports one conversation at a time. */
    private val mutex = Mutex()
    private var engine: Engine? = null

    /**
     * A conversation whose preface (system instruction + examples) is already prefilled, created
     * while the app is idle so that the next request only pays for its own few tokens.
     */
    private var warmConversation: Conversation? = null

    /** Where the model is looked for, in order. See README.md for the matching adb commands. */
    fun candidateModelPaths(): List<String> = listOfNotNull(
        File(appContext.filesDir, MODEL_FILE_NAME).absolutePath,
        appContext.getExternalFilesDir(null)?.let { File(it, MODEL_FILE_NAME).absolutePath },
        "/data/local/tmp/$MODEL_FILE_NAME",
    )

    /** Loads the model. Takes several seconds; safe to call again after a failure. */
    suspend fun initialize() = withContext(ioDispatcher) {
        mutex.withLock {
            if (engine != null) return@withLock
            val candidates = candidateModelPaths()
            val modelPath = candidates.firstOrNull { File(it).canRead() }
            if (modelPath == null) {
                Log.w(TAG, "Model not found in $candidates")
                _state.value = LlmState.ModelMissing(candidates)
                return@withLock
            }
            _state.value = LlmState.Loading

            val backends: List<Pair<String, () -> Backend>> = buildList {
                if (BuildConfig.LLM_PREFER_GPU) add("GPU" to { Backend.GPU() })
                add("CPU" to { Backend.CPU() })
            }
            var lastError: Throwable? = null
            for ((name, createBackend) in backends) {
                try {
                    val created = Engine(
                        EngineConfig(
                            modelPath = modelPath,
                            backend = createBackend(),
                            maxNumTokens = MAX_CONTEXT_TOKENS,
                            cacheDir = appContext.cacheDir.absolutePath,
                        ),
                    )
                    created.initialize()
                    engine = created
                    warmConversation = createConversation(created)
                    _state.value = LlmState.Ready(name)
                    Log.i(TAG, "Gemma ready on $name from $modelPath")
                    return@withLock
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Throwable on purpose: a missing delegate surfaces as UnsatisfiedLinkError.
                    Log.w(TAG, "Engine init failed on $name", e)
                    lastError = e
                }
            }
            _state.value = LlmState.Failed(lastError?.message ?: "The language model could not be loaded")
        }
    }

    /**
     * Expands telegraphic [keywords] into one natural first-person sentence.
     *
     * The flow emits the sentence as it grows (each emission is the full text so far) and
     * completes when generation ends. It throws [LlmUnavailableException] if the model is not
     * loaded or if generation exceeds [GENERATION_TIMEOUT_MS]; whatever was emitted before a
     * timeout is still a usable partial sentence.
     */
    fun expand(keywords: List<String>): Flow<String> = flow {
        // If the model has not finished loading, wait for it rather than failing the user's
        // request. NotLoaded counts as loading: initialize() is started at app launch.
        val settled = state.first { it !is LlmState.Loading && it !is LlmState.NotLoaded }
        if (settled !is LlmState.Ready) throw LlmUnavailableException(describe(settled))

        mutex.withLock {
            val activeEngine = engine ?: throw LlmUnavailableException("The language model was closed")
            val conversation = warmConversation ?: createConversation(activeEngine)
            warmConversation = null
            try {
                val builder = StringBuilder()
                val finished = withTimeoutOrNull(GENERATION_TIMEOUT_MS) {
                    conversation.sendMessageAsync(AacPromptTemplate.userTurn(keywords)).collect { chunk ->
                        builder.append(chunk.text())
                        val sentence = AacPromptTemplate.cleanSentence(builder.toString())
                        if (sentence.isNotEmpty()) emit(sentence)
                    }
                    true
                }
                if (finished == null) {
                    runCatching { conversation.cancelProcess() }
                    throw LlmUnavailableException("The language model took too long to answer")
                }
            } catch (e: CancellationException) {
                // The caller went away: stop the native decode loop instead of letting it run on.
                runCatching { conversation.cancelProcess() }
                throw e
            } finally {
                // Each request gets a fresh conversation so earlier sentences never leak into the
                // context of later ones.
                runCatching { conversation.close() }
                warmConversation = runCatching { createConversation(activeEngine) }
                    .onFailure { Log.w(TAG, "Could not pre-warm the next conversation", it) }
                    .getOrNull()
            }
        }
    }.flowOn(ioDispatcher)

    suspend fun close() = withContext(ioDispatcher) {
        mutex.withLock {
            runCatching { warmConversation?.close() }
            warmConversation = null
            runCatching { engine?.close() }
            engine = null
            _state.value = LlmState.NotLoaded
        }
    }

    private fun createConversation(engine: Engine): Conversation = engine.createConversation(
        ConversationConfig(
            systemInstruction = Contents.of(AacPromptTemplate.SYSTEM_INSTRUCTION),
            initialMessages = AacPromptTemplate.EXAMPLES.flatMap { (user, model) ->
                listOf(Message.user(user), Message.model(model))
            },
            // Low temperature: the task is rewriting, and the user must be able to trust that the
            // same keywords produce essentially the same sentence.
            samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.3),
            maxOutputToken = MAX_OUTPUT_TOKENS,
        ),
    )

    private fun Message.text(): String =
        contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }

    private fun describe(state: LlmState): String = when (state) {
        is LlmState.ModelMissing -> "The language model file is not on the device"
        is LlmState.Failed -> state.message
        else -> "The language model is not loaded"
    }

    companion object {
        private const val TAG = "GemmaLlmManager"
        const val MODEL_FILE_NAME = "gemma-4-E2B-it.litertlm"

        /** Preface (~200 tokens) + keywords + one sentence fits comfortably; a small context
         *  window keeps the KV cache, and so resident memory, small. */
        private const val MAX_CONTEXT_TOKENS = 1024
        private const val MAX_OUTPUT_TOKENS = 64
        private const val GENERATION_TIMEOUT_MS = 30_000L
    }
}

class LlmUnavailableException(message: String) : Exception(message)
