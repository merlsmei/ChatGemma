package com.chatgemma.app.ai

import android.graphics.Bitmap
import com.chatgemma.app.domain.model.InferenceParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes inference to the appropriate engine based on downloaded model file extension:
 *  - .gguf / .ggml  → LlamaCppInferenceEngine  (llama.cpp, GGUF format)
 *  - .litertlm      → LiteRtInferenceEngine    (LiteRT-LM, Google AI Edge)
 *  - .task           → GemmaInferenceEngineImpl (MediaPipe, .task format)
 */
@Singleton
class HybridInferenceEngine @Inject constructor(
    private val mediaPipeEngine: GemmaInferenceEngineImpl,
    private val llamaCppEngine: LlamaCppInferenceEngine,
    private val liteRtEngine: LiteRtInferenceEngine
) : GemmaInferenceEngine {

    private var active: GemmaInferenceEngine? = null
    private val _isReady      = MutableStateFlow(false)
    private val _isGenerating = MutableStateFlow(false)
    private val noGpu         = MutableStateFlow(false).asStateFlow()

    override val isReady:      StateFlow<Boolean> = _isReady.asStateFlow()
    override val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()
    override val isUsingGpu:   StateFlow<Boolean> get() = active?.isUsingGpu ?: noGpu

    override suspend fun initialize(modelPath: String, params: InferenceParams) {
        val engine = when {
            modelPath.endsWith(".gguf", ignoreCase = true) ||
            modelPath.endsWith(".ggml", ignoreCase = true) -> llamaCppEngine
            modelPath.endsWith(".litertlm", ignoreCase = true) -> liteRtEngine
            params.modelFormat.equals("LiteRT", ignoreCase = true) -> liteRtEngine
            params.modelFormat.equals("GGUF", ignoreCase = true) -> llamaCppEngine
            else -> mediaPipeEngine
        }
        // Switching engines must free the old engine's model, or it stays
        // resident and doubles memory pressure. Off the main thread: llama.cpp
        // release() blocks until any in-flight generation stops.
        val previous = active
        if (previous != null && previous !== engine) {
            withContext(Dispatchers.IO) { previous.release() }
        }
        active = engine
        engine.initialize(modelPath, params)
        _isReady.value = true
    }

    override fun generateStream(
        prompt: String, images: List<Bitmap>, params: InferenceParams
    ): Flow<String> = active?.generateStream(prompt, images, params)
        ?: throw IllegalStateException("No model loaded")

    override suspend fun generateFull(
        prompt: String, images: List<Bitmap>, params: InferenceParams
    ): String = active?.generateFull(prompt, images, params)
        ?: throw IllegalStateException("No model loaded")

    override fun cancelGeneration() { active?.cancelGeneration() }

    override fun release() {
        active?.release()
        active = null
        _isReady.value = false
        _isGenerating.value = false
    }

    override suspend fun countTokens(text: String): Int =
        active?.countTokens(text) ?: estimateTokens(text)
}
