package com.chatgemma.app.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.chatgemma.app.domain.model.InferenceParams
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val _isUsingGpu = MutableStateFlow(false)
    private val inferenceMutex = Mutex()

    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()
    override val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()
    override val isUsingGpu: StateFlow<Boolean> = _isUsingGpu.asStateFlow()

    override suspend fun initialize(modelPath: String, params: InferenceParams) {
        withContext(Dispatchers.IO) {
            release()
            val wantGpu = params.gpuLayers > 0
            try {
                llmInference = createEngine(modelPath, params, useGpu = wantGpu)
                _isUsingGpu.value = wantGpu
                _isReady.value = true
            } catch (e: Exception) {
                if (wantGpu) {
                    // GPU backend selection can fail at engine-creation time (unsupported
                    // model/device combo); fall back to CPU rather than leaving it unloadable.
                    Log.w(TAG, "GPU engine creation failed (${e.javaClass.simpleName}: ${e.message}); " +
                        "falling back to CPU", e)
                    try {
                        llmInference = createEngine(modelPath, params, useGpu = false)
                        _isUsingGpu.value = false
                        _isReady.value = true
                    } catch (e2: Exception) {
                        _isReady.value = false
                        throw e2
                    }
                } else {
                    _isReady.value = false
                    throw e
                }
            }
        }
    }

    private fun createEngine(modelPath: String, params: InferenceParams, useGpu: Boolean): LlmInference {
        Log.i(TAG, "Creating MediaPipe LLM engine (backend=${if (useGpu) "GPU" else "CPU"})")
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(params.maxTokens)
            .setPreferredBackend(if (useGpu) LlmInference.Backend.GPU else LlmInference.Backend.CPU)
            .build()
        return LlmInference.createFromOptions(context, options)
    }

    override fun generateStream(
        prompt: String,
        images: List<Bitmap>,
        params: InferenceParams
    ): Flow<String> = flow {
        val engine = llmInference ?: throw IllegalStateException("Gemma engine not initialized")
        inferenceMutex.withLock {
            _isGenerating.value = true
            try {
                emit(withContext(Dispatchers.IO) { engine.generateResponse(prompt) })
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
        val engine = llmInference ?: error("Gemma engine not initialized")
        _isGenerating.value = true
        try {
            withContext(Dispatchers.IO) { engine.generateResponse(prompt) }
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

    private companion object {
        const val TAG = "GemmaEngine"
    }
}
