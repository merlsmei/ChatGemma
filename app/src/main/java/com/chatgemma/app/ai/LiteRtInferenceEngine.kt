package com.chatgemma.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.chatgemma.app.domain.model.InferenceParams
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.ByteArrayOutputStream
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private var visionEnabled = false
    private val inferenceMutex = Mutex()

    override val visionCapable: Boolean get() = visionEnabled

    override suspend fun initialize(modelPath: String, params: InferenceParams) {
        this.modelPath = modelPath
        setGpuBackend(params.gpuLayers > 0)
        // Engine.initialize() is a blocking JNI call that can take several seconds;
        // it must not run on the main thread (the caller uses viewModelScope/Main).
        engine = withContext(Dispatchers.IO) {
            // Attempt order: requested backend with the vision executor first, then
            // progressively degrade (drop vision, then drop GPU). Any GPU
            // engine-creation failure (OpenCL missing, or a GPU-delegate
            // kernel-compilation failure like "Failed to create engine: INTERNAL
            // ERROR ... llm_litert_compiled_model_executor.cc") is treated the same
            // way: retry rather than leaving the model unloadable. Vision-executor
            // failures (text-only .litertlm model) likewise fall back to text-only.
            val wantGpu = usingGpuBackend
            val attempts = buildList {
                add(wantGpu to true)
                add(wantGpu to false)
                if (wantGpu) {
                    add(false to true)
                    add(false to false)
                }
            }
            var lastError: Exception? = null
            var created: Engine? = null
            for ((useGpu, withVision) in attempts) {
                try {
                    created = createEngine(modelPath, useGpu, withVision)
                    setGpuBackend(useGpu)
                    visionEnabled = withVision
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Engine creation failed (gpu=$useGpu, vision=$withVision): " +
                        "${e.javaClass.simpleName}: ${e.message}")
                    lastError = e
                }
            }
            created ?: throw lastError!!
        }
        _isReady.value = true
    }

    private fun setGpuBackend(useGpu: Boolean) {
        usingGpuBackend = useGpu
        _isUsingGpu.value = useGpu
    }

    private fun createEngine(modelPath: String, useGpu: Boolean, withVision: Boolean): Engine {
        val backend = if (useGpu) Backend.GPU() else Backend.CPU()
        val start = System.currentTimeMillis()
        Log.i(TAG, "Creating LiteRT engine (backend=${if (useGpu) "GPU" else "CPU"}, vision=$withVision)")
        val config = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            visionBackend = if (withVision) backend else null,
            maxNumImages = if (withVision) MAX_IMAGES else null,
            cacheDir = context.cacheDir.path
        )
        return Engine(config).also { it.initialize() }.also {
            Log.i(TAG, "LiteRT engine initialized in ${System.currentTimeMillis() - start}ms")
        }
    }

    private fun isOpenClUnavailable(e: Exception): Boolean =
        e.message?.contains("OpenCL", ignoreCase = true) == true

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
            val convConfig = ConversationConfig(samplerConfig = samplerConfig)
            val userMessage = extractLastUserMessage(prompt)
            var chunksTotal = 0

            fun buildMessage(withImages: Boolean): Message =
                if (withImages) {
                    val contents = buildList {
                        images.forEach { add(Content.ImageBytes(it.toJpegBytes())) }
                        add(Content.Text(userMessage))
                    }
                    Message.user(Contents.of(contents))
                } else {
                    Message.user(userMessage)
                }

            // Runs one conversation turn, streaming non-empty chunks downstream.
            // Returns the number of chunks emitted, or null if generation timed out
            // (guards against the known upstream GPU "0 chunks, no done" wedge).
            suspend fun stream(message: Message): Int? {
                var chunks = 0
                var chars = 0
                val backendLabel = if (usingGpuBackend) "GPU" else "CPU"
                val conversation = engine!!.createConversation(convConfig)
                val completed = withTimeoutOrNull(GENERATION_TIMEOUT_MS) {
                    conversation.sendMessageAsync(message)
                        .collect { msg ->
                            val text = msg.toString()
                            if (text.isNotEmpty()) {
                                chunks++
                                chunksTotal++
                                chars += text.length
                                send(text)
                            }
                        }
                    true
                }
                if (completed == null) {
                    Log.e(TAG, "LiteRT generation timed out after ${GENERATION_TIMEOUT_MS}ms " +
                        "(backend=$backendLabel, chunks=$chunks)")
                    runCatching { conversation.close() }
                    return null
                }
                Log.i(TAG, "LiteRT generation done (backend=$backendLabel, chunks=$chunks, chars=$chars)")
                return chunks
            }

            try {
                var sendImages = images.isNotEmpty() && visionEnabled
                if (images.isNotEmpty() && !visionEnabled) {
                    Log.w(TAG, "${images.size} image(s) attached but vision executor is not " +
                        "available for this model; sending text only")
                }
                if (sendImages) {
                    try {
                        if (stream(buildMessage(true)) == null) {
                            throw IllegalStateException(
                                "LiteRT multimodal generation timed out — the model may not support image input."
                            )
                        }
                    } catch (e: Exception) {
                        // Only retry text-only if nothing was streamed yet, so the
                        // downstream collector never sees duplicated output.
                        if (chunksTotal > 0) throw e
                        Log.w(TAG, "Multimodal generation failed; retrying text-only", e)
                        sendImages = false
                    }
                }
                if (!sendImages && chunksTotal == 0) {
                    try {
                        if (stream(buildMessage(false)) == null) {
                            throw IllegalStateException(
                                "LiteRT generation timed out — the GPU backend may be unsupported on this device."
                            )
                        }
                    } catch (e: Exception) {
                        // GPU backend init can succeed but OpenCL still be unavailable
                        // when the first conversation/generation actually runs. Fall
                        // back to CPU and retry once.
                        if (usingGpuBackend && isOpenClUnavailable(e)) {
                            Log.w(TAG, "OpenCL unavailable during generation; rebuilding engine on CPU", e)
                            engine?.close()
                            setGpuBackend(false)
                            engine = createEngine(modelPath!!, useGpu = false, withVision = visionEnabled)
                            if (stream(buildMessage(false)) == null) {
                                throw IllegalStateException("LiteRT CPU generation timed out.")
                            }
                        } else {
                            throw e
                        }
                    }
                }
            } finally {
                _isGenerating.value = false
            }
        }
    }.flowOn(Dispatchers.IO)

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
        engine?.close()
        engine = null
        visionEnabled = false
        _isReady.value = false
        _isGenerating.value = false
    }

    private fun Bitmap.toJpegBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray()
    }

    override suspend fun countTokens(text: String): Int =
        (text.length / 4).coerceAtLeast(1)

    // Parses Gemma-formatted prompts and returns only the last user message text.
    private fun extractLastUserMessage(prompt: String): String {
        val marker = "<start_of_turn>user\n"
        val end = "<end_of_turn>"
        val idx = prompt.lastIndexOf(marker)
        if (idx == -1) return prompt
        val start = idx + marker.length
        val endIdx = prompt.indexOf(end, start)
        return if (endIdx == -1) prompt.substring(start) else prompt.substring(start, endIdx)
    }

    private companion object {
        const val TAG = "LiteRtEngine"
        const val GENERATION_TIMEOUT_MS = 120_000L
        const val MAX_IMAGES = 8
        const val JPEG_QUALITY = 90
    }
}
