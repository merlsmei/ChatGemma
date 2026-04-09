package com.chatgemma.app.domain.model

data class ArchivedTopic(
    val id: String,
    val sessionId: String,
    val originalTopicId: String,
    val label: String,
    val messages: List<Message>,
    val archivedAt: Long,
    val tokensSaved: Int
)
