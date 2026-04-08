package com.chatgemma.app.ai

import android.graphics.Bitmap
import com.chatgemma.app.domain.model.InferenceParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface GemmaInferenceEngine {
    val isReady: StateFlow<Boolean>
    val isGenerating: StateFlow<Boolean>

    suspend fun initialize(modelPath: String, params: InferenceParams)
    fun generateStream(prompt: String, images: List<Bitmap> = emptyList(), params: InferenceParams): Flow<String>
    suspend fun generateFull(prompt: String, images: List<Bitmap> = emptyList(), params: InferenceParams): String
    fun cancelGeneration()
    fun release()
    suspend fun countTokens(text: String): Int
}
