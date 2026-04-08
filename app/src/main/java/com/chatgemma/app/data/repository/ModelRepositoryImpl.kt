package com.chatgemma.app.data.repository

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.chatgemma.app.data.local.db.dao.ModelVersionDao
import com.chatgemma.app.data.local.entity.ModelVersionEntity
import com.chatgemma.app.data.remote.api.HuggingFaceApi
import com.chatgemma.app.data.remote.dto.HfModelDto
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
            val now = System.currentTimeMillis()
            val newModels = mutableListOf<ModelVersion>()

            // 1. Fetch Google-official models from HuggingFace (google org)
            val googleDtos = try {
                huggingFaceApi.searchModels(
                    search = "gemma",
                    sort = "likes",
                    limit = 30,
                    author = "google"
                )
            } catch (_: Exception) { emptyList() }

            // 2. Fetch community models sorted by downloads
            val communityDtos = try {
                huggingFaceApi.searchModels(
                    search = "gemma",
                    sort = "downloads",
                    limit = 30
                )
            } catch (_: Exception) { emptyList() }

            // Combine: google models first, then community (skip duplicates)
            val googleIds = googleDtos.map { it.modelId }.toSet()
            val combined: List<Pair<HfModelDto, String>> =
                googleDtos.map { it to "google" } +
                communityDtos.filter { it.modelId !in googleIds }.map { it to "community" }

            combined.forEach { (dto, source) ->
                if (dto.modelId.isBlank()) return@forEach
                val existing = modelVersionDao.getModelById(dto.modelId)
                if (existing == null) {
                    val model = buildModelVersion(dto, source, now)
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

    // ── Model metadata parsing ──────────────────────────────────────────────

    private fun buildModelVersion(dto: HfModelDto, source: String, now: Long): ModelVersion {
        val id = dto.modelId
        val gen = parseGemmaGeneration(id)
        val params = parseParamCount(id)
        val quant = parseQuantization(id, dto.tags)
        val ctx = parseContextLength(gen)
        val sizeBytes = dto.safetensors?.total?.takeIf { it > 0L }
            ?: estimateSizeBytes(params, quant)
        return ModelVersion(
            id = id,
            displayName = formatDisplayName(id),
            sizeBytes = sizeBytes,
            downloadedAt = null,
            localPath = null,
            isActive = false,
            lastChecked = now,
            releaseDate = dto.lastModified ?: "",
            quantization = quant,
            contextLength = ctx,
            downloadUrl = "https://huggingface.co/$id",
            isMobileSuitable = isMobileSuitable(sizeBytes, quant, params),
            source = source,
            gemmaGeneration = gen,
            paramCount = params
        )
    }

    private fun parseGemmaGeneration(modelId: String): Int {
        val lower = modelId.lowercase()
        return when {
            lower.contains("gemma-4") || lower.contains("gemma4") -> 4
            lower.contains("gemma-3") || lower.contains("gemma3") -> 3
            lower.contains("gemma-2") || lower.contains("gemma2") -> 2
            lower.contains("gemma") -> 1
            else -> 0
        }
    }

    private fun parseParamCount(modelId: String): String {
        val lower = modelId.lowercase()
        return when {
            lower.contains("27b")  -> "27B"
            lower.contains("12b")  -> "12B"
            lower.contains("9b")   -> "9B"
            lower.contains("7b")   -> "7B"
            lower.contains("4b")   -> "4B"
            lower.contains("2b")   -> "2B"
            lower.contains("1.1b") -> "1.1B"
            lower.contains("0.5b") -> "0.5B"
            lower.contains("1b")   -> "1B"
            else -> ""
        }
    }

    private fun parseQuantization(modelId: String, tags: List<String>): String {
        val lower = modelId.lowercase()
        return when {
            lower.contains("int4") || lower.contains("q4") || lower.contains("gguf") -> "int4"
            lower.contains("int8") || lower.contains("q8")                           -> "int8"
            tags.any { it.contains("int4", ignoreCase = true) }                      -> "int4"
            tags.any { it.contains("int8", ignoreCase = true) }                      -> "int8"
            else -> "fp16"
        }
    }

    private fun parseContextLength(generation: Int): Int = when (generation) {
        4    -> 1_048_576   // Gemma 4: 1M context
        3    -> 131_072     // Gemma 3: 128k context
        else -> 8_192       // Gemma 1/2: 8k context
    }

    private fun isMobileSuitable(sizeBytes: Long, quantization: String, paramCount: String): Boolean {
        val mobileQuant = quantization in listOf("int4", "int8")
        return if (sizeBytes > 0L) {
            sizeBytes <= 5L * 1024 * 1024 * 1024 && mobileQuant
        } else {
            paramCount in listOf("0.5B", "1B", "1.1B", "2B", "4B") && mobileQuant
        }
    }

    private fun estimateSizeBytes(paramCount: String, quantization: String): Long {
        val paramBytes = when (paramCount) {
            "0.5B" -> 500_000_000L
            "1B", "1.1B" -> 1_100_000_000L
            "2B"   -> 2_000_000_000L
            "4B"   -> 4_000_000_000L
            "7B"   -> 7_000_000_000L
            "9B"   -> 9_000_000_000L
            "12B"  -> 12_000_000_000L
            "27B"  -> 27_000_000_000L
            else   -> 0L
        }
        val multiplier = when (quantization) {
            "int4" -> 0.5
            "int8" -> 1.0
            else   -> 2.0
        }
        return (paramBytes * multiplier).toLong()
    }

    private fun formatDisplayName(modelId: String): String {
        // "google/gemma-3-4b-it" → "Gemma 3 4B IT"
        return modelId.substringAfterLast("/")
            .split("-")
            .joinToString(" ") { part ->
                when {
                    part.matches(Regex("\\d+b", RegexOption.IGNORE_CASE)) ->
                        part.uppercase()
                    else -> part.replaceFirstChar { it.uppercase() }
                }
            }
    }

    // ── Download / delete ───────────────────────────────────────────────────

    override fun enqueueDownload(modelId: String) {
        val model = runCatching {
            kotlinx.coroutines.runBlocking { modelVersionDao.getModelById(modelId) }
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
        model.localPath?.let { path -> File(path).takeIf { it.exists() }?.delete() }
        modelVersionDao.markDeleted(modelId)
    }

    override suspend fun markDownloaded(modelId: String, localPath: String) {
        modelVersionDao.markDownloaded(modelId, localPath, System.currentTimeMillis())
    }

    // ── Mappers ─────────────────────────────────────────────────────────────

    private fun ModelVersionEntity.toDomain() = ModelVersion(
        id, displayName, sizeBytes, downloadedAt, localPath,
        isActive, lastChecked, releaseDate, quantization, contextLength, downloadUrl,
        isMobileSuitable, source, gemmaGeneration, paramCount
    )

    private fun ModelVersion.toEntity() = ModelVersionEntity(
        id, displayName, sizeBytes, downloadedAt, localPath,
        isActive, lastChecked, releaseDate, quantization, contextLength, downloadUrl,
        isMobileSuitable, source, gemmaGeneration, paramCount
    )
}
