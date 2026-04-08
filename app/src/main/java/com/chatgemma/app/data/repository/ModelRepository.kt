package com.chatgemma.app.data.repository

import com.chatgemma.app.domain.model.ModelVersion
import kotlinx.coroutines.flow.Flow

interface ModelRepository {
    fun getAllModels(): Flow<List<ModelVersion>>
    fun getDownloadedModels(): Flow<List<ModelVersion>>
    suspend fun getActiveModel(): ModelVersion?
    suspend fun getModelById(id: String): ModelVersion?
    suspend fun setActiveModel(modelId: String)
    suspend fun checkForUpdates(): List<ModelVersion>
    fun enqueueDownload(modelId: String)
    suspend fun deleteModel(modelId: String)
    suspend fun markDownloaded(modelId: String, localPath: String)
}
