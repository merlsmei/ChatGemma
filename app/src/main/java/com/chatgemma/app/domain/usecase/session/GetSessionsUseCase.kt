package com.chatgemma.app.domain.usecase.session

import com.chatgemma.app.data.repository.ChatRepository
import com.chatgemma.app.domain.model.Session
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class GetSessionsUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    operator fun invoke(): Flow<List<Session>> = chatRepository.getAllSessions()
}
