package com.chatgemma.app.di

import com.chatgemma.app.worker.ModelDownloadWorker
import com.chatgemma.app.worker.ModelUpdateCheckWorker
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object WorkerModule
// Workers are auto-registered via @HiltWorker + HiltWorkerFactory from the Application class.
// No explicit provides needed; this module exists as a placeholder for future worker config.
