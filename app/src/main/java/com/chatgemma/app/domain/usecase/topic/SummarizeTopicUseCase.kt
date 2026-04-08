package com.chatgemma.app.domain.usecase.topic

import com.chatgemma.app.ai.GemmaInferenceEngine
import com.chatgemma.app.ai.PromptBuilder
import com.chatgemma.app.data.repository.ChatRepository
import com.chatgemma.app.data.repository.TopicRepository
import com.chatgemma.app.domain.model.InferenceParams
import javax.inject.Inject

class SummarizeTopicUseCase @Inject constructor(
    private val chatRepository: ChatRepository,
    private val topicRepository: TopicRepository,
    private val gemmaEngine: GemmaInferenceEngine
) {
    suspend operator fun invoke(topicId: String, branchId: String) {
        val messages = chatRepository.getMessagesByTopic(topicId, branchId)
        if (messages.isEmpty()) return

        val prompt = PromptBuilder.buildSummarizePrompt(messages)
        val summary = gemmaEngine.generateFull(
            prompt = prompt,
            params = InferenceParams(temperature = 0.3f, maxTokens = 256)
        )

        topicRepository.updateTopicSummary(topicId, summary.trim())
    }
}
