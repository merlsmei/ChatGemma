package com.chatgemma.app.data.local.db.dao

import androidx.room.*
import com.chatgemma.app.data.local.entity.BranchEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BranchDao {
    @Query("SELECT * FROM branches WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun getBranchesForSession(sessionId: String): Flow<List<BranchEntity>>

    @Query("SELECT * FROM branches WHERE id = :id")
    suspend fun getBranchById(id: String): BranchEntity?

    @Query("SELECT * FROM branches WHERE sessionId = :sessionId ORDER BY createdAt ASC LIMIT 1")
    suspend fun getMainBranch(sessionId: String): BranchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBranch(branch: BranchEntity)

    @Query("DELETE FROM branches WHERE id = :id")
    suspend fun deleteBranch(id: String)
}
