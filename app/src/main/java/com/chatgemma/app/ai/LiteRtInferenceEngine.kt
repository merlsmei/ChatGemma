package com.chatgemma.app.ai

import android.content.Context
import android.graphics.Bitmap
import com.chatgemma.app.domain.model.InferenceParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stub for the LiteRT-LM inference engine (.litertlm models, Google AI Edge).
 *
 * The real implementation requires the LiteRT-LM SDK
 * (com.google.ai.edge.litertlm:litertlm-android), which is compiled with
 * Kotlin 2.2. The project currently uses Kotlin 2.0.21, so the SDK cannot be
 * included as a compile-time dependency until the Kotlin toolchain is upgraded.
 *
 * All routing infrastructure (HybridInferenceEngine, model discovery, format
 * filter, UI badges) is in place. Once Kotlin is upgraded, replace this stub
 * with the real Engine/Conversation-based implementation.
 */
@Singleton
class LiteRtInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : GemmaInferenceEngine {

    private val _isReady = MutableStateFlow(false)
    private val _isGenerating = MutableStateFlow(false)

    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()
    override val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    override suspend fun initialize(modelPath: String, params: InferenceParams) {
        throw UnsupportedOperationException(
            "LiteRT-LM models (.litertlm) require a Kotlin 2.2+ build. " +
            "Please use GGUF or MediaPipe models instead."
        )
    }

    override fun generateStream(
        prompt: String,
        images: List<Bitmap>,
        params: InferenceParams
    ): Flow<String> = emptyFlow()

    override suspend fun generateFull(
        prompt: String,
        images: List<Bitmap>,
        params: InferenceParams
    ): String = throw UnsupportedOperationException("LiteRT-LM engine not available")

    override fun cancelGeneration() {
        _isGenerating.value = false
    }

    override fun release() {
        _isReady.value = false
        _isGenerating.value = false
    }

    override suspend fun countTokens(text: String): Int {
        return (text.length / 4).coerceAtLeast(1)
    }
}
