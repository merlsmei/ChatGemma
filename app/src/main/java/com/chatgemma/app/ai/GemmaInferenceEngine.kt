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

    suspend fun initialize(modelPath: String, params: InferenceParams)
    fun generateStream(prompt: String, images: List<Bitmap> = emptyList(), params: InferenceParams): Flow<String>
    suspend fun generateFull(prompt: String, images: List<Bitmap> = emptyList(), params: InferenceParams): String
    fun cancelGeneration()
    fun release()
    suspend fun countTokens(text: String): Int
}

/**
 * Heuristic token estimate for when no real tokenizer is available.
 * CJK characters tokenize to roughly one token each, while Latin text
 * averages ~4 characters per token — a flat length/4 undercounts Chinese
 * conversations by 4-8x.
 */
internal fun estimateTokens(text: String): Int {
    var cjk = 0
    for (c in text) {
        val code = c.code
        if (code in 0x2E80..0x9FFF ||   // CJK radicals, kana, CJK unified
            code in 0xAC00..0xD7AF ||   // Hangul syllables
            code in 0xF900..0xFAFF) {   // CJK compatibility ideographs
            cjk++
        }
    }
    return (cjk + (text.length - cjk) / 4).coerceAtLeast(1)
}
