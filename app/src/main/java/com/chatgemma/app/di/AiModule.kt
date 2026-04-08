package com.chatgemma.app.di

import com.chatgemma.app.ai.GemmaInferenceEngine
import com.chatgemma.app.ai.GemmaInferenceEngineImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AiModule {

    @Binds
    @Singleton
    abstract fun bindGemmaInferenceEngine(impl: GemmaInferenceEngineImpl): GemmaInferenceEngine
}
