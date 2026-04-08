package com.chatgemma.app.data.local.db.dao

import androidx.room.*
import com.chatgemma.app.data.local.entity.TopicEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TopicDao {
    @Query("SELECT * FROM topics WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun getTopicsForSession(sessionId: String): Flow<List<TopicEntity>>

    @Query("SELECT * FROM topics WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getTopicsForSessionList(sessionId: String): List<TopicEntity>

    @Query("SELECT * FROM topics WHERE id = :id")
    suspend fun getTopicById(id: String): TopicEntity?

    @Query("SELECT * FROM topics WHERE sessionId = :sessionId AND label = :label LIMIT 1")
    suspend fun getTopicByLabel(sessionId: String, label: String): TopicEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTopic(topic: TopicEntity)

    @Update
    suspend fun updateTopic(topic: TopicEntity)

    @Query("UPDATE topics SET summary = :summary WHERE id = :id")
    suspend fun updateTopicSummary(id: String, summary: String)

    @Query("DELETE FROM topics WHERE id = :id")
    suspend fun deleteTopic(id: String)
}
