package com.chatgemma.app.domain.usecase.session

import com.chatgemma.app.data.repository.ChatRepository
import com.chatgemma.app.domain.model.Branch
import com.chatgemma.app.domain.model.Message
import java.util.UUID
import javax.inject.Inject

class RollbackToMessageUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    /**
     * Creates a new branch starting from [messageId], copying all messages
     * from the parent branch up to and including that message.
     * Returns the newly created branch.
     */
    suspend operator fun invoke(sessionId: String, branchId: String, messageId: String): Branch {
        val messages = chatRepository.getMessagesList(sessionId, branchId)
        val cutoffIndex = messages.indexOfFirst { it.id == messageId }
        if (cutoffIndex < 0) error("Message $messageId not found in branch $branchId")

        val messagesToCopy = messages.subList(0, cutoffIndex + 1)
        val now = System.currentTimeMillis()
        val branchCount = chatRepository.getBranchCount(sessionId)

        val newBranch = Branch(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            parentMessageId = messageId,
            label = "Branch ${branchCount + 1}",
            createdAt = now
        )
        chatRepository.createBranch(newBranch, messagesToCopy)
        return newBranch
    }
}
