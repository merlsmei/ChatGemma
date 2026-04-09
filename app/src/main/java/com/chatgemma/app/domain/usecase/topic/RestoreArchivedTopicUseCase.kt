package com.chatgemma.app.domain.usecase.topic

import com.chatgemma.app.data.repository.ChatRepository
import com.chatgemma.app.data.repository.TopicRepository
import javax.inject.Inject

class RestoreArchivedTopicUseCase @Inject constructor(
    private val chatRepository: ChatRepository,
    private val topicRepository: TopicRepository
) {
    suspend operator fun invoke(archivedTopicId: String) {
        val archived = topicRepository.getArchivedTopicById(archivedTopicId) ?: return
        // Re-insert messages into active context
        chatRepository.insertMessages(archived.messages)
        // Delete from archive
        topicRepository.deleteArchivedTopic(archivedTopicId)
    }
}
