package com.chatgemma.app.data.repository

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.chatgemma.app.data.local.db.dao.ModelVersionDao
import com.chatgemma.app.data.local.entity.ModelVersionEntity
import com.chatgemma.app.data.remote.api.HuggingFaceApi
import com.chatgemma.app.domain.model.ModelVersion
import com.chatgemma.app.worker.ModelDownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelVersionDao: ModelVersionDao,
    private val huggingFaceApi: HuggingFaceApi
) : ModelRepository {

    override fun getAllModels(): Flow<List<ModelVersion>> =
        modelVersionDao.getAllModels().map { list -> list.map { it.toDomain() } }

    override fun getDownloadedModels(): Flow<List<ModelVersion>> =
        modelVersionDao.getDownloadedModels().map { list -> list.map { it.toDomain() } }

    override suspend fun getActiveModel(): ModelVersion? =
        modelVersionDao.getActiveModel()?.toDomain()

    override suspend fun getModelById(id: String): ModelVersion? =
        modelVersionDao.getModelById(id)?.toDomain()

    override suspend fun setActiveModel(modelId: String) {
        modelVersionDao.deactivateAllModels()
        modelVersionDao.activateModel(modelId)
    }

    override suspend fun checkForUpdates(): List<ModelVersion> {
        return try {
            val remote = huggingFaceApi.searchModels(
                search = "gemma",
                sort = "lastModified",
                limit = 30,
                filter = "pytorch"
            )
            val now = System.currentTimeMillis()
            val newModels = mutableListOf<ModelVersion>()

            remote.forEach { dto ->
                val existing = modelVersionDao.getModelById(dto.modelId)
                if (existing == null) {
                    val model = ModelVersion(
                        id = dto.modelId,
                        displayName = dto.modelId.substringAfterLast("/"),
                        sizeBytes = dto.safetensors?.total ?: 0L,
                        downloadedAt = null,
                        localPath = null,
                        isActive = false,
                        lastChecked = now,
                        releaseDate = dto.lastModified ?: "",
                        quantization = when {
                            dto.modelId.contains("int4", ignoreCase = true) -> "int4"
                            dto.modelId.contains("int8", ignoreCase = true) -> "int8"
                            else -> "fp16"
                        },
                        contextLength = 8192,
                        downloadUrl = "https://huggingface.co/${dto.modelId}/resolve/main/model.task"
                    )
                    modelVersionDao.insertModel(model.toEntity())
                    newModels.add(model)
                } else {
                    modelVersionDao.updateModel(existing.copy(lastChecked = now))
                }
            }
            newModels
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun enqueueDownload(modelId: String) {
        val model = runCatching {
            modelVersionDao.let { dao ->
                // We need a blocking call here; use runBlocking carefully
                kotlinx.coroutines.runBlocking { dao.getModelById(modelId) }
            }
        }.getOrNull() ?: return

        val inputData = Data.Builder()
            .putString(ModelDownloadWorker.KEY_MODEL_ID, modelId)
            .putString(ModelDownloadWorker.KEY_DOWNLOAD_URL, model.downloadUrl)
            .build()

        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(inputData)
            .addTag("download_$modelId")
            .build()

        WorkManager.getInstance(context).enqueue(request)
    }

    override suspend fun deleteModel(modelId: String) {
        val model = modelVersionDao.getModelById(modelId) ?: return
        model.localPath?.let { path ->
            val file = File(path)
            if (file.exists()) file.delete()
        }
        modelVersionDao.markDeleted(modelId)
    }

    override suspend fun markDownloaded(modelId: String, localPath: String) {
        modelVersionDao.markDownloaded(modelId, localPath, System.currentTimeMillis())
    }

    // --- Mappers ---

    private fun ModelVersionEntity.toDomain() = ModelVersion(
        id, displayName, sizeBytes, downloadedAt, localPath,
        isActive, lastChecked, releaseDate, quantization, contextLength, downloadUrl
    )

    private fun ModelVersion.toEntity() = ModelVersionEntity(
        id, displayName, sizeBytes, downloadedAt, localPath,
        isActive, lastChecked, releaseDate, quantization, contextLength, downloadUrl
    )
}
