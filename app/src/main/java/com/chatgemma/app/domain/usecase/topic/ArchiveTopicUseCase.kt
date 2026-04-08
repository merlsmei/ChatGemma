package com.chatgemma.app.domain.usecase.topic

import com.chatgemma.app.data.repository.ChatRepository
import com.chatgemma.app.data.repository.TopicRepository
import com.chatgemma.app.domain.model.ArchivedTopic
import com.google.gson.Gson
import java.util.UUID
import javax.inject.Inject

class ArchiveTopicUseCase @Inject constructor(
    private val chatRepository: ChatRepository,
    private val topicRepository: TopicRepository,
    private val gson: Gson
) {
    suspend operator fun invoke(topicId: String, branchId: String): ArchivedTopic {
        val topic = topicRepository.getTopicById(topicId)
            ?: error("Topic $topicId not found")
        val messages = chatRepository.getMessagesByTopic(topicId, branchId)
        val tokensSaved = messages.sumOf { it.tokenCount }

        val archived = ArchivedTopic(
            id = UUID.randomUUID().toString(),
            sessionId = topic.sessionId,
            originalTopicId = topicId,
            label = topic.label,
            messages = messages,
            archivedAt = System.currentTimeMillis(),
            tokensSaved = tokensSaved
        )
        topicRepository.insertArchivedTopic(archived, gson)

        // Remove messages from active context
        chatRepository.deleteMessagesByTopic(topicId)

        return archived
    }
}
