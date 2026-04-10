package com.chatgemma.app.data.local.db.dao

import androidx.room.*
import com.chatgemma.app.data.local.entity.ModelVersionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelVersionDao {
    @Query("SELECT * FROM model_versions ORDER BY releaseDate DESC")
    fun getAllModels(): Flow<List<ModelVersionEntity>>

    @Query("SELECT * FROM model_versions ORDER BY releaseDate DESC")
    suspend fun getAllModelsList(): List<ModelVersionEntity>

    @Query("SELECT * FROM model_versions WHERE localPath IS NOT NULL ORDER BY releaseDate DESC")
    fun getDownloadedModels(): Flow<List<ModelVersionEntity>>

    @Query("SELECT * FROM model_versions WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveModel(): ModelVersionEntity?

    @Query("SELECT * FROM model_versions WHERE id = :id")
    suspend fun getModelById(id: String): ModelVersionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModel(model: ModelVersionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModels(models: List<ModelVersionEntity>)

    @Update
    suspend fun updateModel(model: ModelVersionEntity)

    @Query("UPDATE model_versions SET isActive = 0")
    suspend fun deactivateAllModels()

    @Query("UPDATE model_versions SET isActive = 1 WHERE id = :id")
    suspend fun activateModel(id: String)

    @Query("UPDATE model_versions SET localPath = :path, downloadedAt = :time WHERE id = :id")
    suspend fun markDownloaded(id: String, path: String, time: Long)

    @Query("UPDATE model_versions SET localPath = NULL, downloadedAt = NULL WHERE id = :id")
    suspend fun markDeleted(id: String)

    @Query("DELETE FROM model_versions WHERE id = :id")
    suspend fun deleteModel(id: String)

    @Query("SELECT MAX(lastChecked) FROM model_versions")
    suspend fun getLastCheckedTime(): Long?

    @Query("SELECT * FROM model_versions WHERE localPath = :path LIMIT 1")
    suspend fun getModelByLocalPath(path: String): ModelVersionEntity?
}
