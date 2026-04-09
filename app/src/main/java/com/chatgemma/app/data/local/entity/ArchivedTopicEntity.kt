package com.chatgemma.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "archived_topics",
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
data class ArchivedTopicEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val originalTopicId: String,
    val label: String,
    val messagesJson: String,   // JSON snapshot of archived messages
    val archivedAt: Long,
    val tokensSaved: Int
)
