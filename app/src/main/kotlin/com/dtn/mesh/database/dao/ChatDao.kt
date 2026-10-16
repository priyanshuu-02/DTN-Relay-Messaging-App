package com.dtn.mesh.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dtn.mesh.database.entity.ChatMessageEntity
import kotlinx.coroutines.flow.Flow

/** Persistent user-visible chat history (plaintext), independent of the forwarding buffer. */
@Dao
interface ChatDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: ChatMessageEntity)

    @Query("SELECT * FROM chat_messages WHERE id = :id")
    suspend fun getById(id: String): ChatMessageEntity?

    @Query("SELECT * FROM chat_messages ORDER BY created_at_ms ASC")
    fun observeAll(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY created_at_ms ASC")
    suspend fun getAll(): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_messages WHERE conv_key = :convKey ORDER BY created_at_ms ASC")
    fun observeConversation(convKey: String): Flow<List<ChatMessageEntity>>

    @Query("UPDATE chat_messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("DELETE FROM chat_messages WHERE conv_key = :convKey")
    suspend fun deleteConversation(convKey: String)
}
