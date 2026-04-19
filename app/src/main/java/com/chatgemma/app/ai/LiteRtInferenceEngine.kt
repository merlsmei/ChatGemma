package com.chatgemma.app.ai

import android.content.Context
import android.graphics.Bitmap
import com.chatgemma.app.domain.model.InferenceParams
import com.google.ai.edge.litert.lm.Backend
import com.google.ai.edge.litert.lm.Conversation
import com.google.ai.edge.litert.lm.ConversationConfig
import com.google.ai.edge.litert.lm.Engine
import com.google.ai.edge.litert.lm.EngineConfig
import com.google.ai.edge.litert.lm.SamplerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
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
    private val inferenceMutex = Mutex()

    override suspend fun initialize(modelPath: String, params: InferenceParams) {
        val backend = if (params.gpuLayers > 0) Backend.GPU else Backend.CPU
        val config = EngineConfig.builder()
            .setModelPath(modelPath)
            .setBackend(backend)
            .build()
        engine = Engine(config).also { it.initialize() }
        _isReady.value = true
    }

    override fun generateStream(
        prompt: String,
        images: List<Bitmap>,
        params: InferenceParams
    ): Flow<String> = callbackFlow {
        inferenceMutex.withLock {
            _isGenerating.value = true
            try {
                val samplerConfig = SamplerConfig.builder()
                    .setTemperature(params.temperature)
                    .setTopK(params.topK)
                    .setTopP(params.topP)
                    .build()
                val convConfig = ConversationConfig.builder()
                    .setSamplerConfig(samplerConfig)
                    .build()
                val conversation: Conversation = engine!!.createConversation(convConfig)
                val userMessage = extractLastUserMessage(prompt)
                conversation.sendMessageAsync(userMessage).collect { token -> send(token) }
            } finally {
                _isGenerating.value = false
            }
        }
        awaitClose()
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
