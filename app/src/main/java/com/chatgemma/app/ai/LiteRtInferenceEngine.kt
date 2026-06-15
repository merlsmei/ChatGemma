package com.chatgemma.app.ai

import android.content.Context
import android.graphics.Bitmap
import com.chatgemma.app.domain.model.InferenceParams
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiteRtInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : GemmaInferenceEngine {

    private val _isReady = MutableStateFlow(false)
    private val _isGenerating = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()
    override val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private var engine: Engine? = null
    private var modelPath: String? = null
    private var usingGpuBackend = false
    private val inferenceMutex = Mutex()

    override suspend fun initialize(modelPath: String, params: InferenceParams) {
        this.modelPath = modelPath
        usingGpuBackend = params.gpuLayers > 0
        engine = try {
            createEngine(modelPath, usingGpuBackend)
        } catch (e: Exception) {
            if (usingGpuBackend && isOpenClUnavailable(e)) {
                usingGpuBackend = false
                createEngine(modelPath, useGpu = false)
            } else {
                throw e
            }
        }
        _isReady.value = true
    }

    private fun createEngine(modelPath: String, useGpu: Boolean): Engine {
        val backend = if (useGpu) Backend.GPU() else Backend.CPU()
        val config = EngineConfig(
            modelPath = modelPath,
            backend = backend,
            cacheDir = context.cacheDir.path
        )
        return Engine(config).also { it.initialize() }
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
            try {
                val samplerConfig = SamplerConfig(
                    topK = params.topK,
                    topP = params.topP.toDouble(),
                    temperature = params.temperature.toDouble()
                )
                val convConfig = ConversationConfig(samplerConfig = samplerConfig)
                val userMessage = extractLastUserMessage(prompt)
                try {
                    engine!!.createConversation(convConfig)
                        .sendMessageAsync(Message.user(userMessage))
                        .collect { msg -> send(msg.toString()) }
                } catch (e: Exception) {
                    // GPU backend init can succeed but OpenCL still be unavailable
                    // when the first conversation/generation actually runs. Fall
                    // back to CPU and retry once.
                    if (usingGpuBackend && isOpenClUnavailable(e)) {
                        engine?.close()
                        usingGpuBackend = false
                        engine = createEngine(modelPath!!, useGpu = false)
                        engine!!.createConversation(convConfig)
                            .sendMessageAsync(Message.user(userMessage))
                            .collect { msg -> send(msg.toString()) }
                    } else {
                        throw e
                    }
                }
            } finally {
                _isGenerating.value = false
            }
        }
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
        engine?.close()
        engine = null
        _isReady.value = false
        _isGenerating.value = false
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
}
