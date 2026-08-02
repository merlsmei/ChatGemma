package com.chatgemma.app.domain.usecase.context

import com.chatgemma.app.data.repository.ChatRepository
import com.chatgemma.app.data.repository.ModelRepository
import javax.inject.Inject

class CalculateContextUsageUseCase @Inject constructor(
    private val chatRepository: ChatRepository,
    private val modelRepository: ModelRepository
) {
    /**
     * Returns a value between 0.0 and 1.0 representing how full the context window is.
     *
     * @param configuredContextSize the context size the inference engine was actually
     * initialized with (n_ctx); capped by the model's own context length. When null,
     * the model's context length is used.
     */
    suspend operator fun invoke(
        sessionId: String,
        branchId: String,
        configuredContextSize: Int? = null
    ): Float {
        val totalTokens = chatRepository.getTotalTokenCount(sessionId, branchId)
        val activeModel = modelRepository.getActiveModel()
        val modelContextLength = activeModel?.contextLength ?: 8192
        val effectiveContext = configuredContextSize?.coerceIn(1, modelContextLength)
            ?: modelContextLength
        return (totalTokens.toFloat() / effectiveContext.toFloat()).coerceIn(0f, 1f)
    }
}
