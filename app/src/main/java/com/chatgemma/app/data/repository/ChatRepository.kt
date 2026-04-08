package com.chatgemma.app.data.repository

import com.chatgemma.app.domain.model.Branch
import com.chatgemma.app.domain.model.Message
import com.chatgemma.app.domain.model.Session
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun getAllSessions(): Flow<List<Session>>
    fun getMessages(sessionId: String, branchId: String): Flow<List<Message>>
    suspend fun getMessagesList(sessionId: String, branchId: String): List<Message>
    suspend fun getMessagesByTopic(topicId: String, branchId: String): List<Message>
    suspend fun getRecentMessages(sessionId: String, branchId: String, limit: Int): List<Message>
    suspend fun getTotalTokenCount(sessionId: String, branchId: String): Int
    suspend fun getBranchCount(sessionId: String): Int
    fun getBranches(sessionId: String): Flow<List<Branch>>
    suspend fun getMainBranch(sessionId: String): Branch?
    suspend fun createSession(session: Session, mainBranch: Branch)
    suspend fun deleteSession(sessionId: String)
    suspend fun updateSessionTitle(sessionId: String, title: String)
    suspend fun createBranch(branch: Branch, messagesToCopy: List<Message>)
    suspend fun insertMessage(message: Message)
    suspend fun insertMessages(messages: List<Message>)
    suspend fun updateMessageContent(messageId: String, text: String, tokenCount: Int)
    suspend fun updateMessageTopic(messageId: String, topicId: String?)
    suspend fun deleteMessage(messageId: String)
    suspend fun deleteMessagesByTopic(topicId: String)
}
