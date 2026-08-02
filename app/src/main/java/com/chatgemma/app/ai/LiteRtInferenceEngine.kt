package com.chatgemma.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.chatgemma.app.domain.model.InferenceParams
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiteRtInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : GemmaInferenceEngine {

    private val _isReady = MutableStateFlow(false)
    private val _isGenerating = MutableStateFlow(false)
    private val _isUsingGpu = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()
    override val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()
    override val isUsingGpu: StateFlow<Boolean> = _isUsingGpu.asStateFlow()

    private var engine: Engine? = null
    private var modelPath: String? = null
    private var usingGpuBackend = false
    private val inferenceMutex = Mutex()

    // ── Persistent conversation state (Gallery-style KV-cache reuse) ───────
    // The AI Edge Gallery keeps one Conversation alive across turns so the
    // model retains full history and prefill only processes the new message.
    // We mirror the turns already inside the conversation; when the incoming
    // prompt's history matches, we send just the last user message. Any
    // mismatch (branch switch, edited history, compression, new session,
    // sampler/system change) rebuilds the conversation seeded with
    // initialMessages.
    private var conversation: Conversation? = null
    private var sentTurns: List<Turn> = emptyList()
    private var convSystem: String? = null
    private var convSamplerKey: String? = null

    private data class Turn(val role: String, val text: String)

    // The ViewModel strips Gemma control tokens and trims replies before
    // persisting them, so the history parsed from the next prompt differs
    // from the raw streamed text. Compare turns in that same normalized form,
    // or the KV-cache fast path would never match after the first reply.
    private fun List<Turn>.normalized(): List<Turn> = map { turn ->
        Turn(
            turn.role,
            turn.text
                .replace("<end_of_turn>", "")
                .replace("<start_of_turn>", "")
                .replace("<eos>", "")
                .replace("<bos>", "")
                .trim()
        )
    }

    override suspend fun initialize(modelPath: String, params: InferenceParams) {
        this.modelPath = modelPath
        setGpuBackend(params.gpuLayers > 0)
        resetConversation()
        // Engine.initialize() is a blocking JNI call that can take several seconds;
        // it must not run on the main thread (the caller uses viewModelScope/Main).
        engine = withContext(Dispatchers.IO) {
            try {
                createEngine(modelPath, usingGpuBackend, params)
            } catch (e: Exception) {
                if (usingGpuBackend) {
                    // Any GPU engine-creation failure (OpenCL missing, or a GPU-delegate
                    // kernel-compilation failure like "Failed to create engine: INTERNAL
                    // ERROR ... llm_litert_compiled_model_executor.cc") is treated the same
                    // way: retry on CPU rather than leaving the model unloadable.
                    Log.w(TAG, "GPU engine creation failed (${e.javaClass.simpleName}: ${e.message}); " +
                        "falling back to CPU", e)
                    setGpuBackend(false)
                    try {
                        createEngine(modelPath, useGpu = false, params)
                    } catch (e2: Exception) {
                        throw mapEngineError(e2)
                    }
                } else {
                    throw mapEngineError(e)
                }
            }
        }
        _isReady.value = true
    }

    private fun setGpuBackend(useGpu: Boolean) {
        usingGpuBackend = useGpu
        _isUsingGpu.value = useGpu
    }

    private fun createEngine(modelPath: String, useGpu: Boolean, params: InferenceParams): Engine {
        val backend = if (useGpu) Backend.GPU() else Backend.CPU()
        val start = System.currentTimeMillis()
        Log.i(TAG, "Creating LiteRT engine (backend=${if (useGpu) "GPU" else "CPU"}, " +
            "maxNumTokens=${params.contextSize})")
        val config = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            // Total context window (prompt + output) — the Gallery sizes this
            // from its per-model config; default EngineConfig values are small.
            maxNumTokens = params.contextSize,
            cacheDir = context.cacheDir.path
        )
        return Engine(config).also { it.initialize() }.also {
            Log.i(TAG, "LiteRT engine initialized in ${System.currentTimeMillis() - start}ms")
        }
    }

    private fun isOpenClUnavailable(e: Exception): Boolean =
        e.message?.contains("OpenCL", ignoreCase = true) == true

    /**
     * "TF_LITE_PREFILL_DECODE not found in the model" means the .litertlm file
     * has no generic CPU/GPU graph — it's a web- or NPU-specific bundle (e.g.
     * gemma-4-E2B-it-web.litertlm) that older app versions could download by
     * mistake. The raw library message reaches the UI, so replace it with
     * something the user can act on.
     */
    private fun mapEngineError(e: Exception): Exception =
        if (e.message?.contains("TF_LITE_PREFILL_DECODE", ignoreCase = true) == true) {
            IllegalStateException(
                "This model file doesn't include the on-device CPU/GPU version " +
                    "(it looks like a web- or NPU-specific bundle). Delete the model " +
                    "in Model Manager and download it again to get the standard bundle.",
                e
            )
        } else e

    override fun generateStream(
        prompt: String,
        images: List<Bitmap>,
        params: InferenceParams
    ): Flow<String> = channelFlow {
        inferenceMutex.withLock {
            _isGenerating.value = true
            val samplerConfig = SamplerConfig(
                topK = params.topK,
                topP = params.topP.toDouble(),
                temperature = params.temperature.toDouble()
            )
            val samplerKey = "${params.topK}/${params.topP}/${params.temperature}"
            val (systemPrompt, turns) = parsePrompt(prompt)
            val history = turns.dropLast(1)
            val lastUser = turns.lastOrNull()?.takeIf { it.role == "user" }?.text
                ?: extractLastUserMessage(prompt)

            // Runs one conversation turn, streaming non-empty chunks downstream.
            // Returns the accumulated response, or null if generation stalled —
            // no chunk within the watchdog window (guards against the known
            // upstream GPU "0 chunks, no done" wedge). Total generation time is
            // deliberately unbounded: CPU decode of a long reply takes many
            // minutes while still making steady progress, and a whole-turn
            // timeout was killing those healthy generations at the cap.
            suspend fun stream(): String? = coroutineScope {
                val response = StringBuilder()
                val backendLabel = if (usingGpuBackend) "GPU" else "CPU"
                val conv = obtainConversation(systemPrompt, history, samplerConfig, samplerKey)
                val chunks = AtomicInteger(0)
                val lastChunkAt = AtomicLong(SystemClock.elapsedRealtime())
                val stalled = AtomicBoolean(false)
                val collector = async {
                    conv.sendMessageAsync(Message.user(lastUser))
                        .collect { msg ->
                            val text = msg.toString()
                            if (text.isNotEmpty()) {
                                chunks.incrementAndGet()
                                response.append(text)
                                lastChunkAt.set(SystemClock.elapsedRealtime())
                                send(text)
                            }
                        }
                }
                val watchdog = launch {
                    while (isActive) {
                        delay(WATCHDOG_POLL_MS)
                        // Prefill emits no chunks, so before the first token allow
                        // for CPU prefill of a long history; between tokens the
                        // steady-state gap is orders of magnitude below the limit.
                        val allowance = if (chunks.get() == 0 && !usingGpuBackend) {
                            FIRST_CHUNK_TIMEOUT_MS
                        } else {
                            STALL_TIMEOUT_MS
                        }
                        if (SystemClock.elapsedRealtime() - lastChunkAt.get() > allowance) {
                            stalled.set(true)
                            collector.cancel()
                            break
                        }
                    }
                }
                try {
                    collector.await()
                } catch (e: CancellationException) {
                    // Only swallow the watchdog's own cancel; external
                    // cancellation (stop button, leaving the screen) propagates.
                    if (!stalled.get()) throw e
                } finally {
                    watchdog.cancel()
                }
                if (stalled.get()) {
                    Log.e(TAG, "LiteRT generation stalled (backend=$backendLabel, " +
                        "chunks=${chunks.get()})")
                    resetConversation()
                    return@coroutineScope null
                }
                // Record the turns now inside the conversation so the next call
                // can reuse the warm KV cache and skip re-prefilling history.
                sentTurns = history + Turn("user", lastUser) + Turn("model", response.toString())
                Log.i(TAG, "LiteRT generation done (backend=$backendLabel, chunks=${chunks.get()}, " +
                    "chars=${response.length}, historyTurns=${history.size})")
                response.toString()
            }

            fun rebuildEngineOnCpu() {
                resetConversation()
                engine?.close()
                setGpuBackend(false)
                engine = createEngine(modelPath!!, useGpu = false, params)
            }

            var turnCompleted = false
            try {
                try {
                    if (stream() == null) {
                        // A stalled GPU generation is the same wedge class as an
                        // OpenCL failure — rebuild on CPU and retry once rather
                        // than surfacing an error.
                        if (usingGpuBackend) {
                            Log.w(TAG, "GPU generation stalled; rebuilding engine on CPU")
                            rebuildEngineOnCpu()
                            if (stream() == null) {
                                throw IllegalStateException(
                                    "Generation stalled — the model stopped producing output on CPU as well."
                                )
                            }
                        } else {
                            throw IllegalStateException(
                                "Generation stalled — the model stopped producing output."
                            )
                        }
                    }
                    turnCompleted = true
                } catch (e: Exception) {
                    // GPU backend init can succeed but OpenCL still be unavailable
                    // when the first conversation/generation actually runs. Fall
                    // back to CPU and retry once.
                    if (usingGpuBackend && isOpenClUnavailable(e)) {
                        Log.w(TAG, "OpenCL unavailable during generation; rebuilding engine on CPU", e)
                        rebuildEngineOnCpu()
                        if (stream() == null) {
                            throw IllegalStateException("Generation stalled after falling back to CPU.")
                        }
                        turnCompleted = true
                    } else {
                        throw e
                    }
                }
            } finally {
                // A cancelled or failed turn leaves the conversation's KV cache
                // holding a half-finished exchange we can't mirror — drop it so
                // the next turn rebuilds from the prompt instead of desyncing.
                if (!turnCompleted) resetConversation()
                _isGenerating.value = false
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Reuses the live conversation when its contents match the incoming
     * history (the common append-only chat case — Gallery behavior), otherwise
     * closes it and creates a new one seeded with the full history and system
     * instruction so the model never loses context.
     */
    private fun obtainConversation(
        systemPrompt: String?,
        history: List<Turn>,
        samplerConfig: SamplerConfig,
        samplerKey: String
    ): Conversation {
        val existing = conversation
        if (existing != null &&
            convSystem == systemPrompt &&
            convSamplerKey == samplerKey &&
            sentTurns.normalized() == history.normalized()
        ) {
            return existing
        }
        runCatching { existing?.close() }
        Log.i(TAG, "Building LiteRT conversation (historyTurns=${history.size}, " +
            "system=${systemPrompt != null})")
        val initialMessages = history.map { turn ->
            if (turn.role == "user") Message.user(turn.text) else Message.model(turn.text)
        }
        val config = if (systemPrompt != null) {
            ConversationConfig(
                systemInstruction = Contents.of(systemPrompt),
                initialMessages = initialMessages,
                samplerConfig = samplerConfig
            )
        } else {
            ConversationConfig(
                initialMessages = initialMessages,
                samplerConfig = samplerConfig
            )
        }
        return engine!!.createConversation(config).also {
            conversation = it
            sentTurns = history
            convSystem = systemPrompt
            convSamplerKey = samplerKey
        }
    }

    private fun resetConversation() {
        runCatching { conversation?.close() }
        conversation = null
        sentTurns = emptyList()
        convSystem = null
        convSamplerKey = null
    }

    override suspend fun generateFull(
        prompt: String,
        images: List<Bitmap>,
        params: InferenceParams
    ): String {
        val sb = StringBuilder()
        generateStream(prompt, images, params).collect { sb.append(it) }
        return sb.toString()
    }

    override fun cancelGeneration() {
        _isGenerating.value = false
    }

    override fun release() {
        resetConversation()
        engine?.close()
        engine = null
        _isReady.value = false
        _isGenerating.value = false
    }

    override suspend fun countTokens(text: String): Int = estimateTokens(text)

    // ── Prompt parsing ──────────────────────────────────────────────────────

    /**
     * Parses a PromptBuilder-formatted Gemma prompt back into a system
     * instruction plus ordered turns. PromptBuilder encodes the system prompt
     * as a leading "user: [System: …]" / "model: Understood." pair — that pair
     * is converted to a real systemInstruction here.
     */
    private fun parsePrompt(prompt: String): Pair<String?, List<Turn>> {
        val turns = mutableListOf<Turn>()
        var idx = prompt.indexOf(TURN_START)
        while (idx != -1) {
            val roleStart = idx + TURN_START.length
            val roleEnd = prompt.indexOf('\n', roleStart)
            if (roleEnd == -1) break
            val role = prompt.substring(roleStart, roleEnd).trim()
            val contentEnd = prompt.indexOf(TURN_END, roleEnd + 1)
            if (contentEnd == -1) {
                // Trailing open turn ("<start_of_turn>model\n") — generation cue, skip
                break
            }
            val text = prompt.substring(roleEnd + 1, contentEnd)
            turns.add(Turn(if (role == "user") "user" else "model", text))
            idx = prompt.indexOf(TURN_START, contentEnd)
        }

        var systemPrompt: String? = null
        if (turns.size >= 2 &&
            turns[0].role == "user" && turns[0].text.startsWith(SYSTEM_PREFIX) &&
            turns[1].role == "model"
        ) {
            systemPrompt = turns[0].text
                .removePrefix(SYSTEM_PREFIX)
                .removeSuffix("]")
                .trim()
            turns.removeAt(1)
            turns.removeAt(0)
        }
        return systemPrompt to turns
    }

    // Parses Gemma-formatted prompts and returns only the last user message text.
    private fun extractLastUserMessage(prompt: String): String {
        val marker = "${TURN_START}user\n"
        val idx = prompt.lastIndexOf(marker)
        if (idx == -1) return prompt
        val start = idx + marker.length
        val endIdx = prompt.indexOf(TURN_END, start)
        return if (endIdx == -1) prompt.substring(start) else prompt.substring(start, endIdx)
    }

    private companion object {
        const val TAG = "LiteRtEngine"
        // Max gap between streamed chunks before the turn counts as stalled.
        const val STALL_TIMEOUT_MS = 120_000L
        // CPU prefill of a long history emits nothing until the first token.
        const val FIRST_CHUNK_TIMEOUT_MS = 300_000L
        const val WATCHDOG_POLL_MS = 5_000L
        const val TURN_START = "<start_of_turn>"
        const val TURN_END = "<end_of_turn>"
        const val SYSTEM_PREFIX = "[System: "
    }
}
