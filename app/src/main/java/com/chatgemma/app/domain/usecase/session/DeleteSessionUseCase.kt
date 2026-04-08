package com.chatgemma.app.domain.usecase.session

import com.chatgemma.app.data.repository.ChatRepository
import javax.inject.Inject

class DeleteSessionUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(sessionId: String) {
        chatRepository.deleteSession(sessionId)
    }
}
