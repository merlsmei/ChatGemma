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

    /** Estimated RAM needed at runtime (model stays mapped in memory). */
    val ramRequiredGb: Float get() = when (quantization) {
        "int4" -> sizeGb * 1.25f
        "int8" -> sizeGb * 1.5f
        else   -> sizeGb * 2.0f
    }
}
