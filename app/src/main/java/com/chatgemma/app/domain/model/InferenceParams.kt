package com.chatgemma.app.domain.model

data class InferenceParams(
    val modelId: String = "",
    val temperature: Float = 0.8f,
    val maxTokens: Int = 1024,
    val topK: Int = 40,
    val topP: Float = 0.95f,
    val randomSeed: Int = 0,
    val gpuLayers: Int = 99  // 0 = CPU only, 99 = full GPU offload (default on; crash sentinel auto-disables if needed)
)
