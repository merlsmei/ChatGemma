package com.chatgemma.app.data.repository

import com.chatgemma.app.domain.model.ArchivedTopic
import com.chatgemma.app.domain.model.Topic
import com.google.gson.Gson
import kotlinx.coroutines.flow.Flow

interface TopicRepository {
    fun getTopicsForSession(sessionId: String): Flow<List<Topic>>
    fun getArchivedTopics(sessionId: String): Flow<List<ArchivedTopic>>
    suspend fun getTopicById(id: String): Topic?
    suspend fun getTopicByLabel(sessionId: String, label: String): Topic?
    suspend fun getTopicCount(sessionId: String): Int
    suspend fun getArchivedTopicById(id: String): ArchivedTopic?
    suspend fun insertTopic(topic: Topic)
    suspend fun updateTopic(topic: Topic)
    suspend fun updateTopicSummary(topicId: String, summary: String)
    suspend fun deleteTopic(topicId: String)
    suspend fun insertArchivedTopic(archived: ArchivedTopic, gson: Gson)
    suspend fun deleteArchivedTopic(id: String)
}
