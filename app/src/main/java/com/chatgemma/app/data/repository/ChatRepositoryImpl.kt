package com.chatgemma.app.data.repository

import com.chatgemma.app.data.local.db.dao.BranchDao
import com.chatgemma.app.data.local.db.dao.MessageDao
import com.chatgemma.app.data.local.db.dao.SessionDao
import com.chatgemma.app.data.local.entity.BranchEntity
import com.chatgemma.app.data.local.entity.MessageEntity
import com.chatgemma.app.data.local.entity.SessionEntity
import com.chatgemma.app.domain.model.Branch
import com.chatgemma.app.domain.model.Message
import com.chatgemma.app.domain.model.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val sessionDao: SessionDao,
    private val branchDao: BranchDao,
    private val messageDao: MessageDao
) : ChatRepository {

    override fun getAllSessions(): Flow<List<Session>> =
        sessionDao.getAllSessions().map { list -> list.map { it.toDomain() } }

    override fun getMessages(sessionId: String, branchId: String): Flow<List<Message>> =
        messageDao.getMessages(sessionId, branchId).map { list -> list.map { it.toDomain() } }

    override suspend fun getMessagesList(sessionId: String, branchId: String): List<Message> =
        messageDao.getMessagesList(sessionId, branchId).map { it.toDomain() }

    override suspend fun getMessagesByTopic(topicId: String, branchId: String): List<Message> =
        messageDao.getMessagesByTopic(topicId, branchId).map { it.toDomain() }

    override suspend fun getRecentMessages(sessionId: String, branchId: String, limit: Int): List<Message> =
        messageDao.getRecentMessages(sessionId, branchId, limit).reversed().map { it.toDomain() }

    override suspend fun getTotalTokenCount(sessionId: String, branchId: String): Int =
        messageDao.getTotalTokenCount(sessionId, branchId)

    override suspend fun getBranchCount(sessionId: String): Int {
        var count = 0
        branchDao.getBranchesForSession(sessionId).collect { count = it.size }
        return count
    }

    override fun getBranches(sessionId: String): Flow<List<Branch>> =
        branchDao.getBranchesForSession(sessionId).map { list -> list.map { it.toDomain() } }

    override suspend fun getMainBranch(sessionId: String): Branch? =
        branchDao.getMainBranch(sessionId)?.toDomain()

    override suspend fun createSession(session: Session, mainBranch: Branch) {
        sessionDao.insertSession(session.toEntity())
        branchDao.insertBranch(mainBranch.toEntity())
    }

    override suspend fun deleteSession(sessionId: String) {
        sessionDao.deleteSession(sessionId)
    }

    override suspend fun updateSessionTitle(sessionId: String, title: String) {
        sessionDao.updateSessionTitle(sessionId, title, System.currentTimeMillis())
    }

    override suspend fun createBranch(branch: Branch, messagesToCopy: List<Message>) {
        branchDao.insertBranch(branch.toEntity())
        val copiedMessages = messagesToCopy.map { msg ->
            msg.copy(
                id = UUID.randomUUID().toString(),
                branchId = branch.id
            ).toEntity()
        }
        messageDao.insertMessages(copiedMessages)
    }

    override suspend fun insertMessage(message: Message) {
        messageDao.insertMessage(message.toEntity())
        sessionDao.touchSession(message.sessionId, System.currentTimeMillis())
    }

    override suspend fun insertMessages(messages: List<Message>) {
        messageDao.insertMessages(messages.map { it.toEntity() })
    }

    override suspend fun updateMessageContent(messageId: String, text: String, tokenCount: Int) {
        messageDao.updateMessageContent(messageId, text, tokenCount)
    }

    override suspend fun updateMessageTopic(messageId: String, topicId: String?) {
        messageDao.updateMessageTopic(messageId, topicId)
    }

    override suspend fun deleteMessage(messageId: String) {
        messageDao.deleteMessage(messageId)
    }

    override suspend fun deleteMessagesByTopic(topicId: String) {
        messageDao.deleteMessagesByTopic(topicId)
    }

    // --- Mappers ---

    private fun SessionEntity.toDomain() = Session(id, title, createdAt, updatedAt)
    private fun Session.toEntity() = SessionEntity(id, title, createdAt, updatedAt)

    private fun BranchEntity.toDomain() = Branch(id, sessionId, parentMessageId, label, createdAt)
    private fun Branch.toEntity() = BranchEntity(id, sessionId, parentMessageId, label, createdAt)

    private fun MessageEntity.toDomain() = Message(
        id, sessionId, branchId, role, textContent, mediaType,
        mediaUri, mediaThumbUri, topicId, createdAt, tokenCount, inferenceParamsJson
    )

    private fun Message.toEntity() = MessageEntity(
        id, sessionId, branchId, role, textContent, mediaType,
        mediaUri, mediaThumbUri, topicId, createdAt, tokenCount, inferenceParamsJson
    )
}
