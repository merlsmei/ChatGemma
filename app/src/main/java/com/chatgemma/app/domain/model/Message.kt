package com.chatgemma.app.domain.model

data class Message(
    val id: String,
    val sessionId: String,
    val branchId: String,
    val role: String,               // "user" | "model"
    val textContent: String?,
    val mediaType: String? = null,  // "image" | "video" | "audio" | null
    val mediaUri: String? = null,
    val mediaThumbUri: String? = null,
    val topicId: String? = null,
    val createdAt: Long,
    val tokenCount: Int = 0,
    val inferenceParamsJson: String? = null
)
