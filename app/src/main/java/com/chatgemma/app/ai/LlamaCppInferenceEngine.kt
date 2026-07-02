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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlamaCppInferenceEngine @Inject constructor() : GemmaInferenceEngine {

    private var modelHandle: Long = 0L
    private var hasVision = false
    private val _isReady = MutableStateFlow(false)
    private val _isGenerating = MutableStateFlow(false)
    private val _isUsingGpu = MutableStateFlow(false)
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

        // Marker consumed by llama.cpp's mtmd_tokenize (mtmd_default_marker())
        const val MEDIA_MARKER = "<__media__>"
        // Vision encoders resize to ~896px anyway; cap the RGB buffer we ship over JNI
        private const val MAX_IMAGE_DIM = 1024

        /** Sidecar mmproj file convention: `<model>.gguf` → `<model>.gguf.mmproj.gguf`. */
        fun mmprojFileFor(modelPath: String) = java.io.File("$modelPath.mmproj.gguf")
    }

    override val visionCapable: Boolean get() = hasVision

    override fun imageMarker(): String? = if (hasVision) MEDIA_MARKER else null

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
            val mmproj = mmprojFileFor(modelPath).takeIf { it.exists() && it.canRead() }
            val handle = nativeLoadModel(modelPath, nCtx, nThreads, params.gpuLayers, mmproj?.absolutePath)
            if (handle == 0L) throw IllegalStateException(
                "llama.cpp could not load $modelPath (${"%.1f".format(file.length() / (1024f * 1024f))} MB). " +
                "The model format may be unsupported. Try a different GGUF quantization."
            )
            modelHandle = handle
            hasVision = nativeHasVision(handle)
            _isUsingGpu.value = params.gpuLayers > 0
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
        val h = modelHandle.takeIf { it != 0L }
            ?: throw IllegalStateException("Model not loaded")
        inferenceMutex.withLock {
            _isGenerating.value = true
            try {
                tokenSink = { token -> trySend(token) }
                withContext(Dispatchers.IO) {
                    val payload = buildImagePayload(images)
                    nativeGenerateStreaming(h, prompt,
                        params.maxTokens, params.temperature, params.topP,
                        payload?.rgb, payload?.widths, payload?.heights)
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
                val payload = buildImagePayload(images)
                nativeGenerate(h, prompt,
                    params.maxTokens, params.temperature, params.topP,
                    payload?.rgb, payload?.widths, payload?.heights)
            }
        } finally {
            _isGenerating.value = false
        }
    }

    private class ImagePayload(val rgb: Array<ByteArray>, val widths: IntArray, val heights: IntArray)

    // Converts bitmaps to the raw RGB buffers mtmd_bitmap_init expects.
    // Returns null when there is nothing to send (no images, or text-only model).
    private fun buildImagePayload(images: List<Bitmap>): ImagePayload? {
        if (images.isEmpty() || !hasVision) return null
        val scaled = images.map { bmp ->
            val maxDim = maxOf(bmp.width, bmp.height)
            if (maxDim <= MAX_IMAGE_DIM) bmp
            else {
                val scale = MAX_IMAGE_DIM.toFloat() / maxDim
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * scale).toInt().coerceAtLeast(1),
                    (bmp.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            }
        }
        return ImagePayload(
            rgb = scaled.map { it.toRgbBytes() }.toTypedArray(),
            widths = IntArray(scaled.size) { scaled[it].width },
            heights = IntArray(scaled.size) { scaled[it].height }
        )
    }

    private fun Bitmap.toRgbBytes(): ByteArray {
        val bmp = if (config == Bitmap.Config.ARGB_8888) this else copy(Bitmap.Config.ARGB_8888, false)
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val out = ByteArray(pixels.size * 3)
        var j = 0
        for (p in pixels) {
            out[j++] = ((p shr 16) and 0xFF).toByte()
            out[j++] = ((p shr 8) and 0xFF).toByte()
            out[j++] = (p and 0xFF).toByte()
        }
        return out
    }

    override fun cancelGeneration() { _isGenerating.value = false }

    override fun release() {
        val h = modelHandle
        if (h != 0L) { try { nativeFree(h) } catch (_: Exception) {} }
        modelHandle = 0L
        hasVision = false
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

    // ── JNI declarations ────────────────────────────────────────────────────
    private external fun nativeInit()
    private external fun nativeLoadModel(
        path: String, nCtx: Int, nThreads: Int, nGpuLayers: Int, mmprojPath: String?
    ): Long
    private external fun nativeHasVision(handle: Long): Boolean
    private external fun nativeGenerate(
        handle: Long, prompt: String, maxTokens: Int, temperature: Float, topP: Float,
        images: Array<ByteArray>?, widths: IntArray?, heights: IntArray?
    ): String
    private external fun nativeGenerateStreaming(
        handle: Long, prompt: String, maxTokens: Int, temperature: Float, topP: Float,
        images: Array<ByteArray>?, widths: IntArray?, heights: IntArray?
    )
    private external fun nativeCountTokens(handle: Long, text: String): Int
    private external fun nativeFree(handle: Long)
}
