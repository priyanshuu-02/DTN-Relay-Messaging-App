package com.dtn.mesh.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dtn.mesh.database.entity.GroupEntity
import kotlinx.coroutines.flow.Flow

/** Joined groups/channels and their (wrapped) channel keys. */
@Dao
interface GroupDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(group: GroupEntity)

    @Query("SELECT * FROM groups ORDER BY created_at_ms ASC")
    suspend fun getAll(): List<GroupEntity>

    @Query("SELECT * FROM groups ORDER BY created_at_ms ASC")
    fun observeAll(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups WHERE channel = :channel")
    suspend fun getByChannel(channel: Int): GroupEntity?

    @Query("DELETE FROM groups WHERE channel = :channel")
    suspend fun delete(channel: Int)
}
