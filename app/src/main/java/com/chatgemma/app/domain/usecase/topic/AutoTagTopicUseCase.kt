package com.chatgemma.app.domain.usecase.topic

import com.chatgemma.app.ai.GemmaInferenceEngine
import com.chatgemma.app.ai.PromptBuilder
import com.chatgemma.app.data.repository.ChatRepository
import com.chatgemma.app.data.repository.TopicRepository
import com.chatgemma.app.domain.model.InferenceParams
import com.chatgemma.app.domain.model.Message
import com.chatgemma.app.domain.model.Topic
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject

class AutoTagTopicUseCase @Inject constructor(
    private val topicRepository: TopicRepository,
    private val chatRepository: ChatRepository,
    private val gemmaEngine: GemmaInferenceEngine
) {
    private val topicColors = listOf(
        "#FF6B6B", "#4ECDC4", "#45B7D1", "#96CEB4", "#FFEAA7",
        "#DDA0DD", "#98D8C8", "#F7DC6F", "#BB8FCE", "#85C1E9"
    )

    suspend operator fun invoke(sessionId: String, branchId: String): Topic? {
        if (!gemmaEngine.isReady.value) return null

        val recentMessages = chatRepository.getRecentMessages(sessionId, branchId, 10)
        if (recentMessages.isEmpty()) return null

        return try {
            val prompt = PromptBuilder.buildAutoTagPrompt(recentMessages)
            val label = gemmaEngine.generateFull(
                prompt = prompt,
                params = InferenceParams(temperature = 0.3f, maxTokens = 16)
            ).trim().take(50).trimEnd { it == '.' || it == ',' }

            if (label.isBlank()) return null

            // Reuse existing topic with the same label if it exists
            val existing = topicRepository.getTopicByLabel(sessionId, label)
            if (existing != null) return existing

            val colorIndex = topicRepository.getTopicCount(sessionId) % topicColors.size
            val topic = Topic(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                label = label,
                colorHex = topicColors[colorIndex],
                isAutoTagged = true,
                createdAt = System.currentTimeMillis()
            )
            topicRepository.insertTopic(topic)
            topic
        } catch (e: Exception) {
            null
        }
    }
}
