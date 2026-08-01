package com.chatgemma.app.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.chatgemma.app.data.local.db.dao.*
import com.chatgemma.app.data.local.entity.*

@Database(
    entities = [
        SessionEntity::class,
        BranchEntity::class,
        MessageEntity::class,
        TopicEntity::class,
        ArchivedTopicEntity::class,
        ModelVersionEntity::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun branchDao(): BranchDao
    abstract fun messageDao(): MessageDao
    abstract fun topicDao(): TopicDao
    abstract fun archivedTopicDao(): ArchivedTopicDao
    abstract fun modelVersionDao(): ModelVersionDao

    companion object {
        const val DATABASE_NAME = "chatgemma_db"

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN systemPrompt TEXT")
            }
        }
    }
}
