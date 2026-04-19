package com.chatgemma.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "model_versions")
data class ModelVersionEntity(
    @PrimaryKey val id: String,
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
    // v2 fields
    val isMobileSuitable: Boolean = false,
    val source: String = "community",
    val gemmaGeneration: Int = 0,
    val paramCount: String = "",
    // v3 fields
    val modelFormat: String = "GGUF"   // "GGUF" | "MediaPipe"
)
