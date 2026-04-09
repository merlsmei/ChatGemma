package com.chatgemma.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.chatgemma.app.worker.ModelUpdateCheckWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class ChatGemmaApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        scheduleModelUpdateCheck()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            val updateChannel = NotificationChannel(
                CHANNEL_MODEL_UPDATES,
                "Model Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for new Gemma model releases on HuggingFace"
            }

            val downloadChannel = NotificationChannel(
                CHANNEL_MODEL_DOWNLOAD,
                "Model Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress notifications for model downloads"
            }

            notificationManager.createNotificationChannels(listOf(updateChannel, downloadChannel))
        }
    }

    private fun scheduleModelUpdateCheck() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<ModelUpdateCheckWorker>(7, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                WORK_MODEL_UPDATE_CHECK,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } catch (_: Exception) {
            // Non-critical: periodic update check failed to schedule
        }
    }

    companion object {
        const val CHANNEL_MODEL_UPDATES = "model_updates"
        const val CHANNEL_MODEL_DOWNLOAD = "model_download"
        const val WORK_MODEL_UPDATE_CHECK = "model_update_check"
    }
}
