package com.chatgemma.app.ai

import android.graphics.Bitmap
import com.chatgemma.app.domain.model.InferenceParams
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
class LlamaCppInferenceEngine @Inject constructor() : GemmaInferenceEngine {

    private var modelHandle: Long = 0L
    private val _isReady = MutableStateFlow(false)
    private val _isGenerating = MutableStateFlow(false)

    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()
    override val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    companion object {
        val available: Boolean by lazy {
            try { System.loadLibrary("chatgemma_llama"); true }
            catch (_: UnsatisfiedLinkError) { false }
        }
    }

    override suspend fun initialize(modelPath: String, params: InferenceParams) {
        withContext(Dispatchers.IO) {
            release()
            if (!available) throw IllegalStateException(
                "llama.cpp native library not available on this build."
            )

            // Validate file before JNI call for better error messages
            val file = java.io.File(modelPath)
            if (!file.exists()) throw IllegalStateException(
                "Model file not found: $modelPath\nRe-download the model from Model Manager."
            )
            if (!file.canRead()) throw IllegalStateException(
                "Model file not readable: $modelPath\nCheck storage permissions."
            )
            if (file.length() < 1_024 * 1_024) throw IllegalStateException(
                "Model file too small (${file.length()} bytes): $modelPath\nFile may be corrupted. Delete and re-download."
            )

            nativeInit()
            val nCtx     = params.maxTokens.coerceIn(512, 8192)
            val nThreads = Runtime.getRuntime().availableProcessors().coerceAtMost(8)
            val handle = nativeLoadModel(modelPath, nCtx, nThreads)
            if (handle == 0L) throw IllegalStateException(
                "llama.cpp could not load $modelPath (${"%.1f".format(file.length() / (1024f * 1024f))} MB). " +
                "The model format may be unsupported. Try a different GGUF quantization."
            )
            modelHandle = handle
            _isReady.value = true
        }
    }

    override fun generateStream(
        prompt: String, images: List<Bitmap>, params: InferenceParams
    ): Flow<String> = flow {
        val h = modelHandle.takeIf { it != 0L }
            ?: throw IllegalStateException("Model not loaded")
        _isGenerating.value = true
        try {
            val result = withContext(Dispatchers.IO) {
                nativeGenerate(h, buildGemmaPrompt(prompt, images),
                    params.maxTokens, params.temperature, params.topP)
            }
            emit(result)
        } finally {
            _isGenerating.value = false
        }
    }

    override suspend fun generateFull(
        prompt: String, images: List<Bitmap>, params: InferenceParams
    ): String = withContext(Dispatchers.IO) {
        val h = modelHandle.takeIf { it != 0L } ?: error("Model not loaded")
        _isGenerating.value = true
        try {
            nativeGenerate(h, buildGemmaPrompt(prompt, images),
                params.maxTokens, params.temperature, params.topP)
        } finally {
            _isGenerating.value = false
        }
    }

    override fun cancelGeneration() { _isGenerating.value = false }

    override fun release() {
        val h = modelHandle
        if (h != 0L) { try { nativeFree(h) } catch (_: Exception) {} }
        modelHandle = 0L
        _isReady.value = false
        _isGenerating.value = false
    }

    override suspend fun countTokens(text: String): Int {
        val h = modelHandle
        return if (h != 0L && available) {
            withContext(Dispatchers.IO) { nativeCountTokens(h, text) }
        } else {
            (text.length / 4).coerceAtLeast(1)
        }
    }

    /** Format prompt using Gemma chat template. */
    private fun buildGemmaPrompt(text: String, images: List<Bitmap>): String {
        val imageNote = if (images.isNotEmpty())
            images.joinToString("\n") { "[Image: ${it.width}×${it.height}px]" } + "\n"
        else ""
        return "<start_of_turn>user\n$imageNote$text<end_of_turn>\n<start_of_turn>model\n"
    }

    // ── JNI declarations ────────────────────────────────────────────────────
    private external fun nativeInit()
    private external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeGenerate(
        handle: Long, prompt: String, maxTokens: Int, temperature: Float, topP: Float
    ): String
    private external fun nativeCountTokens(handle: Long, text: String): Int
    private external fun nativeFree(handle: Long)
}
