package com.chatgemma.app.data.repository

import com.chatgemma.app.data.local.db.dao.ArchivedTopicDao
import com.chatgemma.app.data.local.db.dao.TopicDao
import com.chatgemma.app.data.local.entity.ArchivedTopicEntity
import com.chatgemma.app.data.local.entity.TopicEntity
import com.chatgemma.app.domain.model.ArchivedTopic
import com.chatgemma.app.domain.model.Message
import com.chatgemma.app.domain.model.Topic
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TopicRepositoryImpl @Inject constructor(
    private val topicDao: TopicDao,
    private val archivedTopicDao: ArchivedTopicDao
) : TopicRepository {

    override fun getTopicsForSession(sessionId: String): Flow<List<Topic>> =
        topicDao.getTopicsForSession(sessionId).map { list -> list.map { it.toDomain() } }

    override fun getArchivedTopics(sessionId: String): Flow<List<ArchivedTopic>> =
        archivedTopicDao.getArchivedTopics(sessionId).map { list ->
            list.map { it.toDomain(Gson()) }
        }

    override suspend fun getTopicById(id: String): Topic? =
        topicDao.getTopicById(id)?.toDomain()

    override suspend fun getTopicByLabel(sessionId: String, label: String): Topic? =
        topicDao.getTopicByLabel(sessionId, label)?.toDomain()

    override suspend fun getTopicCount(sessionId: String): Int =
        topicDao.getTopicsForSession(sessionId).first().size

    override suspend fun getArchivedTopicById(id: String): ArchivedTopic? =
        archivedTopicDao.getArchivedTopicById(id)?.toDomain(Gson())

    override suspend fun insertTopic(topic: Topic) =
        topicDao.insertTopic(topic.toEntity())

    override suspend fun updateTopic(topic: Topic) =
        topicDao.updateTopic(topic.toEntity())

    override suspend fun updateTopicSummary(topicId: String, summary: String) =
        topicDao.updateTopicSummary(topicId, summary)

    override suspend fun deleteTopic(topicId: String) =
        topicDao.deleteTopic(topicId)

    override suspend fun insertArchivedTopic(archived: ArchivedTopic, gson: Gson) {
        archivedTopicDao.insertArchivedTopic(archived.toEntity(gson))
    }

    override suspend fun deleteArchivedTopic(id: String) =
        archivedTopicDao.deleteArchivedTopic(id)

    // --- Mappers ---

    private fun TopicEntity.toDomain() =
        Topic(id, sessionId, label, colorHex, isAutoTagged, createdAt, summary)

    private fun Topic.toEntity() =
        TopicEntity(id, sessionId, label, colorHex, isAutoTagged, createdAt, summary)

    private fun ArchivedTopicEntity.toDomain(gson: Gson): ArchivedTopic {
        val type = object : TypeToken<List<Message>>() {}.type
        val messages: List<Message> = gson.fromJson(messagesJson, type) ?: emptyList()
        return ArchivedTopic(id, sessionId, originalTopicId, label, messages, archivedAt, tokensSaved)
    }

    private fun ArchivedTopic.toEntity(gson: Gson) = ArchivedTopicEntity(
        id = id,
        sessionId = sessionId,
        originalTopicId = originalTopicId,
        label = label,
        messagesJson = gson.toJson(messages),
        archivedAt = archivedAt,
        tokensSaved = tokensSaved
    )
}
