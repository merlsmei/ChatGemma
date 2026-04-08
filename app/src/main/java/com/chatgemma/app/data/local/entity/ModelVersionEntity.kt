package com.chatgemma.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "model_versions")
data class ModelVersionEntity(
    @PrimaryKey val id: String,         // e.g. "google/gemma-3-4b-it"
    val displayName: String,
    val sizeBytes: Long,
    val downloadedAt: Long?,            // null = not downloaded
    val localPath: String?,             // null = not downloaded
    val isActive: Boolean,
    val lastChecked: Long,
    val releaseDate: String,
    val quantization: String,           // "int4" | "int8" | "fp16"
    val contextLength: Int,
    val downloadUrl: String
)
