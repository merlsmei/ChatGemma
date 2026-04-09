package com.chatgemma.app.data.local.db.dao

import androidx.room.*
import com.chatgemma.app.data.local.entity.ArchivedTopicEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ArchivedTopicDao {
    @Query("SELECT * FROM archived_topics WHERE sessionId = :sessionId ORDER BY archivedAt DESC")
    fun getArchivedTopics(sessionId: String): Flow<List<ArchivedTopicEntity>>

    @Query("SELECT * FROM archived_topics WHERE id = :id")
    suspend fun getArchivedTopicById(id: String): ArchivedTopicEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArchivedTopic(topic: ArchivedTopicEntity)

    @Query("DELETE FROM archived_topics WHERE id = :id")
    suspend fun deleteArchivedTopic(id: String)

    @Query("SELECT COALESCE(SUM(tokensSaved), 0) FROM archived_topics WHERE sessionId = :sessionId")
    suspend fun getTotalTokensSaved(sessionId: String): Int
}
