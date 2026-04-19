package com.chatgemma.app.data.repository

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import com.chatgemma.app.data.local.db.dao.ModelVersionDao
import com.chatgemma.app.data.local.entity.ModelVersionEntity
import com.chatgemma.app.data.remote.api.HuggingFaceApi
import com.chatgemma.app.data.remote.dto.HfModelDto
import com.chatgemma.app.domain.model.ModelVersion
import com.chatgemma.app.worker.ModelDownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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

            // Clear any locally "downloaded" models whose file is missing or invalid (<1 MB)
            cleanupInvalidDownloads()

            // 1. Fetch Google-official models
            val googleDtos = try {
                huggingFaceApi.searchModels(
                    search = "gemma",
                    sort = "likes",
                    limit = 30,
                    author = "google"
                )
            } catch (_: Exception) { emptyList() }

            // 2. Fetch community GGUF models (most popular quantized Gemma)
            val communityDtos = try {
                huggingFaceApi.searchModels(
                    search = "gemma gguf",
                    sort = "downloads",
                    limit = 30
                )
            } catch (_: Exception) { emptyList() }

            // 3. Fetch MediaPipe .task models via tag filter and keyword search
            val mediapipeByTag = try {
                huggingFaceApi.searchModels(
                    search = "gemma",
                    filter = "mediapipe",
                    sort = "downloads",
                    limit = 20
                )
            } catch (_: Exception) { emptyList() }

            val mediapipeByName = try {
                huggingFaceApi.searchModels(
                    search = "gemma mediapipe",
                    sort = "downloads",
                    limit = 15
                )
            } catch (_: Exception) { emptyList() }

            // Merge both MediaPipe searches, deduplicate
            val mediapipeIds = mutableSetOf<String>()
            val mediapipeDtos = (mediapipeByTag + mediapipeByName).filter { mediapipeIds.add(it.modelId) }

            // 4. Fetch LiteRT-LM models (Gemma 4 on-device with GPU)
            val litertByTag = try {
                huggingFaceApi.searchModels(
                    search = "gemma litert",
                    sort = "downloads",
                    limit = 20
                )
            } catch (_: Exception) { emptyList() }

            val litertByAuthor = try {
                huggingFaceApi.searchModels(
                    search = "gemma litertlm",
                    sort = "downloads",
                    limit = 15,
                    author = "litert-community"
                )
            } catch (_: Exception) { emptyList() }

            // Merge LiteRT searches, deduplicate
            val litertIds = mutableSetOf<String>()
            val litertDtos = (litertByTag + litertByAuthor).filter { litertIds.add(it.modelId) }

            // Combine: google first, then community GGUF, mediapipe, litert (skip duplicates)
            val googleIds = googleDtos.map { it.modelId }.toSet()
            val communityIds = communityDtos.map { it.modelId }.toSet()
            val mpIds = mediapipeDtos.map { it.modelId }.toSet()
            val combined: List<Pair<HfModelDto, String>> =
                googleDtos.map { it to "google" } +
                communityDtos.filter { it.modelId !in googleIds }.map { it to "community" } +
                mediapipeDtos.filter { it.modelId !in googleIds && it.modelId !in communityIds }.map { it to "community" } +
                litertDtos.filter { it.modelId !in googleIds && it.modelId !in communityIds && it.modelId !in mpIds }.map { it to "community" }

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
        val format = detectFormat(id, dto.tags, quant)
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
            downloadUrl = "https://huggingface.co/$id",  // resolved at download time
            isMobileSuitable = isMobileSuitable(sizeBytes, quant, params),
            source = source,
            gemmaGeneration = gen,
            paramCount = params,
            modelFormat = format
        )
    }

    /** Clears localPath for any "downloaded" model whose file is missing or too small to be real. */
    private suspend fun cleanupInvalidDownloads() {
        val all = modelVersionDao.getAllModelsList()
        all.filter { it.localPath != null }.forEach { entity ->
            val file = entity.localPath?.let { java.io.File(it) }
            val isInvalid = file == null || !file.exists() || file.length() < 1_024 * 1_024
            if (isInvalid) {
                modelVersionDao.markDeleted(entity.id)
            }
        }
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
            lower.contains("31b")  -> "31B"
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
            lower.contains("q4_k_m")                                   -> "Q4_K_M"
            lower.contains("q4_k_s")                                   -> "Q4_K_S"
            lower.contains("q4_0") || lower.contains("q4-0")          -> "Q4_0"
            lower.contains("q5_k_m")                                   -> "Q5_K_M"
            lower.contains("q5_0") || lower.contains("q5-0")          -> "Q5_0"
            lower.contains("q8_0") || lower.contains("q8-0")          -> "Q8_0"
            lower.contains("int4") || lower.contains("q4")            -> "int4"
            lower.contains("int8") || lower.contains("q8")            -> "int8"
            lower.contains("gguf")                                     -> "GGUF"
            tags.any { it.contains("int4", ignoreCase = true) }       -> "int4"
            tags.any { it.contains("int8", ignoreCase = true) }       -> "int8"
            else -> "fp16"
        }
    }

    private fun parseContextLength(generation: Int): Int = when (generation) {
        4    -> 1_048_576   // Gemma 4: 1M context
        3    -> 131_072     // Gemma 3: 128k context
        else -> 8_192       // Gemma 1/2: 8k context
    }

    private fun detectFormat(modelId: String, tags: List<String>, quantization: String): String {
        val lower = modelId.lowercase()
        return when {
            // LiteRT-LM: tags or ID patterns
            tags.any { it.equals("litert", ignoreCase = true) ||
                        it.equals("litertlm", ignoreCase = true) }           -> "LiteRT"
            lower.contains("litert-lm") || lower.contains("litertlm")       -> "LiteRT"
            lower.contains(".litertlm")                                       -> "LiteRT"
            // MediaPipe: tags or ID patterns
            tags.any { it.equals("mediapipe", ignoreCase = true) }           -> "MediaPipe"
            lower.contains("mediapipe")                                       -> "MediaPipe"
            lower.contains("-task")                                           -> "MediaPipe"
            Regex("(gpu|cpu)[-_](int[48])").containsMatchIn(lower)           -> "MediaPipe"
            // GGUF signals
            lower.contains("gguf")                                            -> "GGUF"
            quantization.startsWith("Q", ignoreCase = true)                  -> "GGUF"
            quantization == "GGUF"                                            -> "GGUF"
            else -> "GGUF"
        }
    }

    private fun isMobileSuitable(sizeBytes: Long, quantization: String, paramCount: String): Boolean {
        // Threshold sized for a 12 GB device: model + KV cache + Android OS overhead
        val mobileQuant = quantization.lowercase().let {
            it.contains("q4") || it.contains("q5") || it.contains("q8") ||
            it.contains("int4") || it.contains("int8") || it == "gguf"
        }
        return if (sizeBytes > 0L) {
            sizeBytes <= 10L * 1024 * 1024 * 1024 && mobileQuant  // ≤ 10 GB on 12 GB device
        } else {
            paramCount in listOf("0.5B", "1B", "1.1B", "2B", "4B", "7B", "9B") && mobileQuant
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
            "31B"  -> 31_000_000_000L
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

    /** Pick the best downloadable file from a sibling list (Q4_K_M GGUF preferred, then .litertlm, then .task). */
    private fun findDownloadableFile(siblings: List<com.chatgemma.app.data.remote.dto.HfSibling>): com.chatgemma.app.data.remote.dto.HfSibling? =
        siblings.firstOrNull { it.rfilename.contains("Q4_K_M", ignoreCase = true) && it.rfilename.endsWith(".gguf") }
            ?: siblings.firstOrNull { it.rfilename.contains("Q4_0",   ignoreCase = true) && it.rfilename.endsWith(".gguf") }
            ?: siblings.firstOrNull { it.rfilename.contains("Q4",     ignoreCase = true) && it.rfilename.endsWith(".gguf") }
            ?: siblings.firstOrNull { it.rfilename.contains("Q5_K_M", ignoreCase = true) && it.rfilename.endsWith(".gguf") }
            ?: siblings.firstOrNull { it.rfilename.endsWith(".gguf") }
            ?: siblings.firstOrNull { it.rfilename.endsWith(".litertlm") }
            ?: siblings.firstOrNull { it.rfilename.endsWith(".task") }

    /**
     * Search HuggingFace for a community GGUF conversion of the given model.
     * Returns (communityModelId, sibling) or null if none found.
     */
    private suspend fun findCommunityGguf(modelId: String): Pair<String, com.chatgemma.app.data.remote.dto.HfSibling>? {
        val baseName = modelId.substringAfterLast("/")
        val results = try {
            huggingFaceApi.searchModels(
                search = "$baseName gguf",
                sort = "downloads",
                limit = 5
            )
        } catch (_: Exception) { return null }

        for (dto in results) {
            if (dto.modelId == modelId) continue          // skip the original
            val info = try { huggingFaceApi.getModelFiles(dto.modelId) } catch (_: Exception) { continue }
            val file = findDownloadableFile(info.siblings)
            if (file != null) return dto.modelId to file
        }
        return null
    }

    override suspend fun enqueueDownload(modelId: String) {
        withContext(Dispatchers.IO) {
            // Fetch the model's file list to find a downloadable file
            val modelInfo = try {
                huggingFaceApi.getModelFiles(modelId)
            } catch (e: Exception) {
                throw IllegalStateException("Could not fetch model file list: ${e.message}")
            }

            // Try the original repo first, then fall back to community GGUF conversions
            val directFile = findDownloadableFile(modelInfo.siblings)
            val (downloadRepoId, downloadFile) = if (directFile != null) {
                modelId to directFile
            } else {
                findCommunityGguf(modelId)
                    ?: throw IllegalStateException(
                        "No downloadable model file (.gguf or .task) found for \"$modelId\"."
                    )
            }

            val downloadUrl = "https://huggingface.co/$downloadRepoId/resolve/main/${downloadFile.rfilename}"

            modelVersionDao.getModelById(modelId) ?: return@withContext

            val inputData = Data.Builder()
                .putString(ModelDownloadWorker.KEY_MODEL_ID, modelId)
                .putString(ModelDownloadWorker.KEY_DOWNLOAD_URL, downloadUrl)
                .build()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(inputData)
                .addTag("download_$modelId")
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueue(request)
        }
    }

    override suspend fun deleteModel(modelId: String) {
        val model = modelVersionDao.getModelById(modelId) ?: return
        model.localPath?.let { path -> File(path).takeIf { it.exists() }?.delete() }
        modelVersionDao.markDeleted(modelId)
    }

    override suspend fun markDownloaded(modelId: String, localPath: String) {
        modelVersionDao.markDownloaded(modelId, localPath, System.currentTimeMillis())
    }

    override fun getModelsDirectory(): File {
        return File(context.getExternalFilesDir(null) ?: context.filesDir, "models")
            .also { it.mkdirs() }
    }

    /**
     * Link a local file to a model. Returns null on success, or the display name
     * of the model already using that file.
     */
    override suspend fun linkLocalFile(modelId: String, filePath: String): String? {
        val existing = modelVersionDao.getModelByLocalPath(filePath)
        if (existing != null && existing.id != modelId) {
            return existing.displayName
        }
        modelVersionDao.markDownloaded(modelId, filePath, System.currentTimeMillis())
        return null
    }

    // ── Mappers ─────────────────────────────────────────────────────────────

    private fun ModelVersionEntity.toDomain() = ModelVersion(
        id, displayName, sizeBytes, downloadedAt, localPath,
        isActive, lastChecked, releaseDate, quantization, contextLength, downloadUrl,
        isMobileSuitable, source, gemmaGeneration, paramCount, modelFormat
    )

    private fun ModelVersion.toEntity() = ModelVersionEntity(
        id, displayName, sizeBytes, downloadedAt, localPath,
        isActive, lastChecked, releaseDate, quantization, contextLength, downloadUrl,
        isMobileSuitable, source, gemmaGeneration, paramCount, modelFormat
    )
}
