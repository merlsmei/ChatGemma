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
    val downloadUrl: String
) {
    val isDownloaded: Boolean get() = localPath != null
    val sizeMb: Float get() = sizeBytes / (1024f * 1024f)
    val sizeGb: Float get() = sizeBytes / (1024f * 1024f * 1024f)
}
