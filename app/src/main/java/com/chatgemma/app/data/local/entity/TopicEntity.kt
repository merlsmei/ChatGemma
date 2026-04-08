package com.chatgemma.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "topics",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class TopicEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val label: String,
    val colorHex: String,
    val isAutoTagged: Boolean,
    val createdAt: Long,
    val summary: String? = null
)
