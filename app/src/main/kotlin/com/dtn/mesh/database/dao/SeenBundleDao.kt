package com.dtn.mesh.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dtn.mesh.database.entity.SeenBundleEntity

/** Persistent dedup / tombstone store — prevents resurrection of delivered/expired bundles. */
@Dao
interface SeenBundleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: SeenBundleEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM seen_bundles WHERE message_id = :messageId)")
    suspend fun exists(messageId: String): Boolean

    @Query("SELECT * FROM seen_bundles WHERE message_id = :messageId")
    suspend fun get(messageId: String): SeenBundleEntity?

    /** Remove tombstones whose TTL has fully elapsed (safe to forget). */
    @Query("DELETE FROM seen_bundles WHERE expires_at_ms < :nowMs")
    suspend fun purgeExpired(nowMs: Long): Int

    @Query("SELECT COUNT(*) FROM seen_bundles")
    suspend fun count(): Int
}
