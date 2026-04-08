package com.chatgemma.app.domain.model

data class Branch(
    val id: String,
    val sessionId: String,
    val parentMessageId: String?,
    val label: String,
    val createdAt: Long
)
