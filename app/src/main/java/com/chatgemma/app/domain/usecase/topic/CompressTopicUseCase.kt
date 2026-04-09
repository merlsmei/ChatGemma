package com.chatgemma.app.domain.usecase.topic

import com.chatgemma.app.ai.GemmaInferenceEngine
import com.chatgemma.app.ai.PromptBuilder
import com.chatgemma.app.data.repository.ChatRepository
import com.chatgemma.app.data.repository.TopicRepository
import com.chatgemma.app.domain.model.InferenceParams
import javax.inject.Inject

class CompressTopicUseCase @Inject constructor(
    private val chatRepository: ChatRepository,
    private val topicRepository: TopicRepository,
    private val gemmaEngine: GemmaInferenceEngine
) {
    suspend operator fun invoke(topicId: String, branchId: String) {
        val topic = topicRepository.getTopicById(topicId) ?: return
        val messages = chatRepository.getMessagesByTopic(topicId, branchId)
        if (messages.isEmpty()) return

        val prompt = PromptBuilder.buildCompressPrompt(messages)
        val compressed = gemmaEngine.generateFull(
            prompt = prompt,
            params = InferenceParams(temperature = 0.3f, maxTokens = 512)
        )

        // Replace all topic messages with a single compressed summary message
        val firstMessage = messages.first()
        val newTokenCount = (compressed.length / 4).coerceAtLeast(1)
        chatRepository.updateMessageContent(firstMessage.id, compressed, newTokenCount)

        // Remove remaining messages in this topic (keep only the first)
        messages.drop(1).forEach { msg ->
            chatRepository.deleteMessage(msg.id)
        }
    }
}
