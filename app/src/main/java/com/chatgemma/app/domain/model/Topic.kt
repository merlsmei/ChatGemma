package com.chatgemma.app.domain.model

data class Topic(
    val id: String,
    val sessionId: String,
    val label: String,
    val colorHex: String,
    val isAutoTagged: Boolean,
    val createdAt: Long,
    val summary: String? = null
)
