package com.chatgemma.app.domain.model

data class ModelVersion(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val downloadedAt: Long?,
    val localPath: String?,
    val isActive: Boolean,
    val lastChecked: Long,
    val releaseDate: String,
    val quantization: String,
    val contextLength: Int,
    val downloadUrl: String,
    val isMobileSuitable: Boolean = false,
    val source: String = "community",   // "google" | "community"
    val gemmaGeneration: Int = 0,       // 4, 3, 2, 1, 0=unknown
    val paramCount: String = ""         // "1B", "2B", "4B", "9B", "12B", "27B", ""
) {
    val isDownloaded: Boolean get() = localPath != null
    val sizeMb: Float get() = sizeBytes / (1024f * 1024f)
    val sizeGb: Float get() = sizeBytes / (1024f * 1024f * 1024f)

    /** Model format derived from file path, download URL, model ID, or quantization. */
    val format: String get() {
        val path = localPath ?: downloadUrl
        return when {
            path.endsWith(".gguf", ignoreCase = true) ||
            path.endsWith(".ggml", ignoreCase = true) -> "GGUF"
            path.endsWith(".task", ignoreCase = true)  -> "MediaPipe"
            // Infer from model ID or quantization before download
            id.contains("gguf", ignoreCase = true)     -> "GGUF"
            quantization.startsWith("Q", ignoreCase = true) -> "GGUF"
            quantization == "GGUF"                     -> "GGUF"
            id.contains("mediapipe", ignoreCase = true) ||
            id.contains("-task", ignoreCase = true)    -> "MediaPipe"
            else -> "GGUF"  // HuggingFace models default to GGUF
        }
    }

    /** Estimated RAM needed at runtime (model stays mapped in memory). */
    val ramRequiredGb: Float get() = when (quantization) {
        "int4" -> sizeGb * 1.25f
        "int8" -> sizeGb * 1.5f
        else   -> sizeGb * 2.0f
    }
}
