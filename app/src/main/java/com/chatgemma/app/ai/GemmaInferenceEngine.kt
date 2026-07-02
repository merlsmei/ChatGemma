package com.chatgemma.app.ai

import android.graphics.Bitmap
import com.chatgemma.app.domain.model.InferenceParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface GemmaInferenceEngine {
    val isReady: StateFlow<Boolean>
    val isGenerating: StateFlow<Boolean>
    /** Whether the currently loaded model is actually running on GPU (reflects fallback, not just the request). */
    val isUsingGpu: StateFlow<Boolean>

    /** Whether the currently loaded model can consume image input passed to [generateStream]. */
    val visionCapable: Boolean get() = false

    /**
     * Marker string the caller must place in the prompt text once per attached image
     * (engines that consume images out-of-band return null).
     */
    fun imageMarker(): String? = null

    suspend fun initialize(modelPath: String, params: InferenceParams)
    fun generateStream(prompt: String, images: List<Bitmap> = emptyList(), params: InferenceParams): Flow<String>
    suspend fun generateFull(prompt: String, images: List<Bitmap> = emptyList(), params: InferenceParams): String
    fun cancelGeneration()
    fun release()
    suspend fun countTokens(text: String): Int
}
