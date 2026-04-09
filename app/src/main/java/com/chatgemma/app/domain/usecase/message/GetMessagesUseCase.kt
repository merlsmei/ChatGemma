package com.chatgemma.app.domain.usecase.message

import com.chatgemma.app.data.repository.ChatRepository
import com.chatgemma.app.domain.model.Message
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetMessagesUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    operator fun invoke(sessionId: String, branchId: String): Flow<List<Message>> =
        chatRepository.getMessages(sessionId, branchId)
}
