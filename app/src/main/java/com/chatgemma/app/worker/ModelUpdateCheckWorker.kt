package com.chatgemma.app.worker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.chatgemma.app.ChatGemmaApplication
import com.chatgemma.app.MainActivity
import com.chatgemma.app.R
import com.chatgemma.app.domain.model.ModelVersion
import com.chatgemma.app.domain.usecase.model.CheckModelUpdatesUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ModelUpdateCheckWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val checkModelUpdatesUseCase: CheckModelUpdatesUseCase
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val newModels = checkModelUpdatesUseCase()
            if (newModels.isNotEmpty()) {
                showNewModelsNotification(newModels)
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun showNewModelsNotification(models: List<ModelVersion>) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "model_manager")
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val modelNames = models.take(3).joinToString(", ") { it.displayName }
        val more = if (models.size > 3) " and ${models.size - 3} more" else ""

        val notification = NotificationCompat.Builder(context, ChatGemmaApplication.CHANNEL_MODEL_UPDATES)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("New Gemma Models Available")
            .setContentText("$modelNames$more")
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("New Gemma models on HuggingFace: $modelNames$more. Tap to view in Model Manager."))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
    }
}
