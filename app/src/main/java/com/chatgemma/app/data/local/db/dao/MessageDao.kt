package com.chatgemma.app.data.local.db.dao

import androidx.room.*
import com.chatgemma.app.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("""
        SELECT * FROM messages
        WHERE sessionId = :sessionId AND branchId = :branchId
        ORDER BY createdAt ASC
    """)
    fun getMessages(sessionId: String, branchId: String): Flow<List<MessageEntity>>

    @Query("""
        SELECT * FROM messages
        WHERE sessionId = :sessionId AND branchId = :branchId
        ORDER BY createdAt ASC
    """)
    suspend fun getMessagesList(sessionId: String, branchId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getMessageById(id: String): MessageEntity?

    @Query("""
        SELECT * FROM messages
        WHERE sessionId = :sessionId AND branchId = :branchId
        AND createdAt <= :upToTimestamp
        ORDER BY createdAt ASC
    """)
    suspend fun getMessagesUpTo(sessionId: String, branchId: String, upToTimestamp: Long): List<MessageEntity>

    @Query("""
        SELECT COALESCE(SUM(tokenCount), 0) FROM messages
        WHERE sessionId = :sessionId AND branchId = :branchId
    """)
    suspend fun getTotalTokenCount(sessionId: String, branchId: String): Int

    @Query("""
        SELECT * FROM messages
        WHERE topicId = :topicId AND branchId = :branchId
        ORDER BY createdAt ASC
    """)
    suspend fun getMessagesByTopic(topicId: String, branchId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("UPDATE messages SET topicId = :topicId WHERE id = :messageId")
    suspend fun updateMessageTopic(messageId: String, topicId: String?)

    @Query("UPDATE messages SET textContent = :text, tokenCount = :tokenCount WHERE id = :id")
    suspend fun updateMessageContent(id: String, text: String, tokenCount: Int)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun deleteMessage(id: String)

    @Query("DELETE FROM messages WHERE topicId = :topicId")
    suspend fun deleteMessagesByTopic(topicId: String)

    @Query("""
        SELECT * FROM messages
        WHERE sessionId = :sessionId AND branchId = :branchId
        ORDER BY createdAt DESC LIMIT :limit
    """)
    suspend fun getRecentMessages(sessionId: String, branchId: String, limit: Int): List<MessageEntity>
}
