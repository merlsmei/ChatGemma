package com.chatgemma.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BranchEntity::class,
            parentColumns = ["id"],
            childColumns = ["branchId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("sessionId"),
        Index("branchId"),
        Index(value = ["sessionId", "branchId", "createdAt"]),
        Index("topicId")
    ]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val branchId: String,
    val role: String,               // "user" | "model"
    val textContent: String?,
    val mediaType: String?,         // "image" | "video" | "audio" | null
    val mediaUri: String?,
    val mediaThumbUri: String?,
    val topicId: String?,
    val createdAt: Long,
    val tokenCount: Int = 0,
    val inferenceParamsJson: String? = null
)
