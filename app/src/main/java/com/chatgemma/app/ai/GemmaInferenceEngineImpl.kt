package com.chatgemma.app.ai

import android.content.Context
import android.graphics.Bitmap
import com.chatgemma.app.domain.model.InferenceParams
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GemmaInferenceEngineImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : GemmaInferenceEngine {

    private var llmInference: LlmInference? = null
    private val _isReady = MutableStateFlow(false)
    private val _isGenerating = MutableStateFlow(false)

    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()
    override val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    override suspend fun initialize(modelPath: String, params: InferenceParams) {
        withContext(Dispatchers.IO) {
            try {
                release()
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(params.maxTokens)
                    .setTemperature(params.temperature)
                    .setRandomSeed(params.randomSeed)
                    .build()
                llmInference = LlmInference.createFromOptions(context, options)
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
    ): Flow<String> = flow {
        val engine = llmInference ?: throw IllegalStateException("Gemma engine not initialized")
        _isGenerating.value = true
        try {
            val fullPrompt = if (images.isNotEmpty()) {
                val imageNote = images.joinToString("\n") { "[Attached image: ${it.width}×${it.height}px]" }
                "$imageNote\n$prompt"
            } else {
                prompt
            }
            emit(withContext(Dispatchers.IO) { engine.generateResponse(fullPrompt) })
        } finally {
            _isGenerating.value = false
        }
    }

    override suspend fun generateFull(
        prompt: String,
        images: List<Bitmap>,
        params: InferenceParams
    ): String = withContext(Dispatchers.IO) {
        val engine = llmInference ?: error("Gemma engine not initialized")
        _isGenerating.value = true
        try {
            val fullPrompt = if (images.isNotEmpty()) {
                val imageNote = images.joinToString("\n") { "[Attached image: ${it.width}×${it.height}px]" }
                "$imageNote\n$prompt"
            } else {
                prompt
            }
            engine.generateResponse(fullPrompt)
        } finally {
            _isGenerating.value = false
        }
    }

    override fun cancelGeneration() {
        _isGenerating.value = false
    }

    override fun release() {
        try {
            llmInference?.close()
        } catch (_: Exception) {}
        llmInference = null
        _isReady.value = false
        _isGenerating.value = false
    }

    override suspend fun countTokens(text: String): Int {
        return (text.length / 4).coerceAtLeast(1)
    }
}
