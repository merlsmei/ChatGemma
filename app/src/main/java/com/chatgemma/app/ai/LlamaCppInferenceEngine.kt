package com.chatgemma.app.ai

import android.graphics.Bitmap
import androidx.annotation.Keep
import com.chatgemma.app.domain.model.InferenceParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlamaCppInferenceEngine @Inject constructor() : GemmaInferenceEngine {

    @Volatile
    private var modelHandle: Long = 0L
    // Guards reads of modelHandle paired with nativeCancel, so a cancel can
    // never race a concurrent nativeFree and touch a dangling handle.
    private val handleLock = Any()
    private val _isReady = MutableStateFlow(false)
    private val _isGenerating = MutableStateFlow(false)
    private val _isUsingGpu = MutableStateFlow(false)
    // Serializes every native call that uses the model handle (generate,
    // countTokens, free). release() acquires it too, so the model can never be
    // freed while a ggml compute thread is still decoding — that use-after-free
    // was crashing the app inside libggml-cpu.so.
    private val inferenceMutex = Mutex()

    @Volatile
    private var tokenSink: ((String) -> Unit)? = null

    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()
    override val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()
    override val isUsingGpu: StateFlow<Boolean> = _isUsingGpu.asStateFlow()

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
            val nCtx     = params.contextSize.coerceIn(512, 8192)
            // Leave the efficiency cores out: ggml synchronizes all threads at
            // every op, so on big.LITTLE SoCs (e.g. Snapdragon 8 Gen 3: 6 big
            // + 2 little) the slowest core gates every layer.
            val cores    = Runtime.getRuntime().availableProcessors()
            val nThreads = (cores - 2).coerceIn(2, 6)
            val handle = nativeLoadModel(modelPath, nCtx, nThreads, params.gpuLayers)
            if (handle == 0L) throw IllegalStateException(
                "llama.cpp could not load $modelPath (${"%.1f".format(file.length() / (1024f * 1024f))} MB). " +
                "The model format may be unsupported. Try a different GGUF quantization."
            )
            modelHandle = handle
            // Only report GPU when a GPU backend device actually registered;
            // requesting gpuLayers > 0 with no OpenCL device silently runs on
            // CPU, and the UI should say so.
            _isUsingGpu.value = params.gpuLayers > 0 && nativeHasGpuBackend()
            _isReady.value = true
        }
    }

    // Called from JNI (nativeGenerateStreaming) for each generated token
    @Keep
    @Suppress("unused")
    private fun onToken(token: String) {
        tokenSink?.invoke(token)
    }

    override fun generateStream(
        prompt: String, images: List<Bitmap>, params: InferenceParams
    ): Flow<String> = channelFlow {
        inferenceMutex.withLock {
            // Read the handle only after acquiring the mutex: release() zeroes
            // it under the same mutex, so a stale handle can't reach JNI.
            val h = modelHandle.takeIf { it != 0L }
                ?: throw IllegalStateException("Model not loaded")
            _isGenerating.value = true
            try {
                tokenSink = { token -> trySend(token) }
                withContext(Dispatchers.IO) {
                    nativeGenerateStreaming(h, prompt,
                        params.maxTokens, params.temperature, params.topP)
                }
            } finally {
                tokenSink = null
                _isGenerating.value = false
            }
        }
    }

    override suspend fun generateFull(
        prompt: String, images: List<Bitmap>, params: InferenceParams
    ): String = inferenceMutex.withLock {
        val h = modelHandle.takeIf { it != 0L } ?: error("Model not loaded")
        _isGenerating.value = true
        try {
            withContext(Dispatchers.IO) {
                nativeGenerate(h, prompt,
                    params.maxTokens, params.temperature, params.topP)
            }
        } finally {
            _isGenerating.value = false
        }
    }

    override fun cancelGeneration() {
        synchronized(handleLock) {
            val h = modelHandle
            if (h != 0L) nativeCancel(h)
        }
        _isGenerating.value = false
    }

    override fun release() {
        // Ask any in-flight generation to stop, then wait for it to release
        // the inference mutex before freeing the model. Freeing while ggml
        // compute threads are mid-decode is a native use-after-free crash.
        synchronized(handleLock) {
            val h = modelHandle
            if (h != 0L) nativeCancel(h)
        }
        runBlocking {
            inferenceMutex.withLock {
                val h = synchronized(handleLock) {
                    modelHandle.also { modelHandle = 0L }
                }
                if (h != 0L) { try { nativeFree(h) } catch (_: Exception) {} }
            }
        }
        _isReady.value = false
        _isGenerating.value = false
    }

    override suspend fun countTokens(text: String): Int {
        if (!available || modelHandle == 0L) return estimateTokens(text)
        return inferenceMutex.withLock {
            val h = modelHandle
            if (h == 0L) estimateTokens(text)
            else withContext(Dispatchers.IO) { nativeCountTokens(h, text) }
        }
    }

    // ── JNI declarations ────────────────────────────────────────────────────
    private external fun nativeInit()
    private external fun nativeLoadModel(path: String, nCtx: Int, nThreads: Int, nGpuLayers: Int): Long
    private external fun nativeGenerate(
        handle: Long, prompt: String, maxTokens: Int, temperature: Float, topP: Float
    ): String
    private external fun nativeGenerateStreaming(
        handle: Long, prompt: String, maxTokens: Int, temperature: Float, topP: Float
    )
    private external fun nativeCountTokens(handle: Long, text: String): Int
    private external fun nativeCancel(handle: Long)
    private external fun nativeHasGpuBackend(): Boolean
    private external fun nativeFree(handle: Long)
}
