package com.chatgemma.app.domain.usecase.topic

import com.chatgemma.app.data.repository.TopicRepository
import javax.inject.Inject

class DeleteArchivedTopicUseCase @Inject constructor(
    private val topicRepository: TopicRepository
) {
    suspend operator fun invoke(archivedTopicId: String) {
        topicRepository.deleteArchivedTopic(archivedTopicId)
    }
}
