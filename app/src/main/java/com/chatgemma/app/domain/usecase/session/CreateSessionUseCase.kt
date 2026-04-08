package com.chatgemma.app.domain.usecase.session

import com.chatgemma.app.data.repository.ChatRepository
import com.chatgemma.app.domain.model.Branch
import com.chatgemma.app.domain.model.Session
import java.util.UUID
import javax.inject.Inject

class CreateSessionUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend operator fun invoke(title: String = "New Chat"): Pair<Session, Branch> {
        val now = System.currentTimeMillis()
        val session = Session(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now
        )
        val branch = Branch(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            parentMessageId = null,
            label = "Main",
            createdAt = now
        )
        chatRepository.createSession(session, branch)
        return Pair(session, branch)
    }
}
