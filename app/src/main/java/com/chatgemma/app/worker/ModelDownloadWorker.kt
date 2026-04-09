package com.chatgemma.app.worker

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chatgemma.app.ChatGemmaApplication
import com.chatgemma.app.data.repository.ModelRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val modelRepository: ModelRepository,
    private val okHttpClient: OkHttpClient
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo =
        createForegroundInfo("Preparing download…", 0)

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Missing model ID"))
        val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Missing download URL"))

        return try {
            setForeground(createForegroundInfo("Downloading $modelId", 0))
            val localPath = downloadModel(modelId, downloadUrl) { progress ->
                setProgressAsync(workDataOf(KEY_PROGRESS to progress))
                setForeground(createForegroundInfo("Downloading $modelId", progress))
            }
            modelRepository.markDownloaded(modelId, localPath)
            Result.success(workDataOf(KEY_LOCAL_PATH to localPath))
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unknown error")))
        }
    }

    private suspend fun downloadModel(
        modelId: String,
        url: String,
        onProgress: suspend (Int) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val modelsDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "models")
            .also { it.mkdirs() }
        val ext = url.substringAfterLast('.').takeIf { it.length in 2..5 } ?: "task"
        val fileName = "${modelId.replace("/", "_")}.$ext"
        val destFile = File(modelsDir, fileName)

        val request = Request.Builder().url(url).build()
        val response = okHttpClient.newCall(request).execute()

        if (!response.isSuccessful) error("HTTP ${response.code}: ${response.message}")

        val contentType = response.header("Content-Type", "") ?: ""
        if (contentType.contains("text/html", ignoreCase = true)) {
            response.close()
            error("Got HTML response instead of model file. Check download URL or authentication.")
        }

        val body = response.body ?: error("Empty response body")
        val totalBytes = body.contentLength()

        body.byteStream().use { input ->
            FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(8 * 1024)
                var downloaded = 0L
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (totalBytes > 0) {
                        val progress = ((downloaded * 100) / totalBytes).toInt()
                        onProgress(progress)
                    }
                }
            }
        }
        destFile.absolutePath
    }

    private fun createForegroundInfo(title: String, progress: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(
            context, ChatGemmaApplication.CHANNEL_MODEL_DOWNLOAD
        )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_DOWNLOAD_URL = "download_url"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"
        const val KEY_LOCAL_PATH = "local_path"
        private const val NOTIFICATION_ID = 1002
    }
}
