package com.chatgemma.app.ai

import android.content.Context
import android.graphics.Bitmap
import com.chatgemma.app.domain.model.InferenceParams
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LiteRT-LM inference engine for .litertlm models (Google AI Edge).
 * Uses Google's GPU delegate (OpenCL) — stable on Adreno, unlike Vulkan.
 *
 * Creates a fresh Conversation per generation call. The last user message is
 * extracted from the Gemma-formatted prompt and sent via the conversation API,
 * which manages the chat template internally.
 */
@Singleton
class LiteRtInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : GemmaInferenceEngine {

    private var engine: Engine? = null
    private val _isReady = MutableStateFlow(false)
    private val _isGenerating = MutableStateFlow(false)
    private val inferenceMutex = Mutex()

    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()
    override val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    override suspend fun initialize(modelPath: String, params: InferenceParams) {
        withContext(Dispatchers.IO) {
            try {
                release()
                val backend = if (params.gpuLayers > 0) Backend.GPU() else Backend.CPU()
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = backend
                )
                val e = Engine(config)
                e.initialize()
                engine = e
                _isReady.value = true
            } catch (e: Exception) {
                _isReady.value = false
                throw e
            }
        }
    }

    override fun generateStream(
        prompt: String,
        images: List<Bitmap>,
        params: InferenceParams
    ): Flow<String> = channelFlow {
        inferenceMutex.withLock {
            val e = engine ?: throw IllegalStateException("LiteRT-LM engine not initialized")
            _isGenerating.value = true
            try {
                val lastUserMsg = extractLastUserMessage(prompt)
                val convConfig = ConversationConfig(
                    samplerConfig = SamplerConfig(
                        topK = params.topK,
                        topP = params.topP.toDouble(),
                        temperature = params.temperature.toDouble()
                    )
                )
                withContext(Dispatchers.IO) {
                    e.createConversation(convConfig).use { conv ->
                        conv.sendMessageAsync(lastUserMsg).collect { chunk ->
                            trySend(chunk.toString())
                        }
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
    ): String = inferenceMutex.withLock {
        val e = engine ?: error("LiteRT-LM engine not initialized")
        _isGenerating.value = true
        try {
            val lastUserMsg = extractLastUserMessage(prompt)
            val convConfig = ConversationConfig(
                samplerConfig = SamplerConfig(
                    topK = params.topK,
                    topP = params.topP.toDouble(),
                    temperature = params.temperature.toDouble()
                )
            )
            withContext(Dispatchers.IO) {
                e.createConversation(convConfig).use { conv ->
                    val result = StringBuilder()
                    conv.sendMessageAsync(lastUserMsg).collect { chunk ->
                        result.append(chunk.toString())
                    }
                    result.toString()
                }
            }
        } finally {
            _isGenerating.value = false
        }
    }

    override fun cancelGeneration() {
        _isGenerating.value = false
    }

    override fun release() {
        try {
            engine?.close()
        } catch (_: Exception) {}
        engine = null
        _isReady.value = false
        _isGenerating.value = false
    }

    override suspend fun countTokens(text: String): Int {
        return (text.length / 4).coerceAtLeast(1)
    }

    /**
     * Extracts the last user message from a Gemma-formatted prompt.
     * The prompt format is: <bos><start_of_turn>user\n...<end_of_turn>\n<start_of_turn>model\n
     * We extract just the last user turn's text so LiteRT-LM's conversation API
     * doesn't double-wrap the chat template.
     */
    private fun extractLastUserMessage(prompt: String): String {
        val pattern = Regex("<start_of_turn>user\n(.*?)<end_of_turn>", RegexOption.DOT_MATCHES_ALL)
        val matches = pattern.findAll(prompt).toList()
        if (matches.isNotEmpty()) {
            return matches.last().groupValues[1].trim()
        }
        // Fallback: strip control tokens and use the whole prompt
        return prompt
            .replace("<bos>", "")
            .replace("<start_of_turn>model\n", "")
            .replace("<start_of_turn>user\n", "")
            .replace("<end_of_turn>", "")
            .trim()
    }
}
