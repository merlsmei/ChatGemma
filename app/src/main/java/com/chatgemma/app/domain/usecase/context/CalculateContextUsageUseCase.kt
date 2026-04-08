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
     */
    suspend operator fun invoke(sessionId: String, branchId: String): Float {
        val totalTokens = chatRepository.getTotalTokenCount(sessionId, branchId)
        val activeModel = modelRepository.getActiveModel()
        val contextLength = activeModel?.contextLength ?: 8192
        return (totalTokens.toFloat() / contextLength.toFloat()).coerceIn(0f, 1f)
    }
}
