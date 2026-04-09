package com.chatgemma.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "branches",
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
data class BranchEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val parentMessageId: String?,   // null = root branch
    val label: String,
    val createdAt: Long
)
