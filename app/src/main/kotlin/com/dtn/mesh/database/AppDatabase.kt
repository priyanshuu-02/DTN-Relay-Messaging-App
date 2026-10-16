package com.dtn.mesh.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.dtn.mesh.database.converter.Converters
import com.dtn.mesh.database.dao.ChatDao
import com.dtn.mesh.database.dao.ContactDao
import com.dtn.mesh.database.dao.EncounterDao
import com.dtn.mesh.database.dao.ForwardingDecisionDao
import com.dtn.mesh.database.dao.GroupDao
import com.dtn.mesh.database.dao.MessageDao
import com.dtn.mesh.database.dao.SeenBundleDao
import com.dtn.mesh.database.entity.ChatMessageEntity
import com.dtn.mesh.database.entity.ContactEntity
import com.dtn.mesh.database.entity.EncounterEntity
import com.dtn.mesh.database.entity.ForwardingDecisionEntity
import com.dtn.mesh.database.entity.GroupEntity
import com.dtn.mesh.database.entity.MessageEntity
import com.dtn.mesh.database.entity.SeenBundleEntity

/**
 * Room database for the DTN Mesh Relay Layer.
 *
 * Schema version 1: Initial schema with message buffer, contact state, and encounter history.
 *
 * Export schema = true for migration tooling in future versions.
 */
@Database(
    entities = [
        MessageEntity::class,
        ContactEntity::class,
        EncounterEntity::class,
        ForwardingDecisionEntity::class,
        ChatMessageEntity::class,
        SeenBundleEntity::class,
        GroupEntity::class,
    ],
    // v2: ContactEntity.custom_name. v3: crypto identity columns on contacts + new tables
    // (chat_messages, seen_bundles, groups) for the encryption/chat/group work.
    // fallbackToDestructiveMigration is still set in DatabaseModule, so a schema bump wipes the
    // local DB on upgrade (acceptable for this research build — identity keys live in a file and
    // survive; peers re-announce their keys). Wire a real Migration before any field deployment.
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun encounterDao(): EncounterDao
    abstract fun forwardingDecisionDao(): ForwardingDecisionDao
    abstract fun chatDao(): ChatDao
    abstract fun seenBundleDao(): SeenBundleDao
    abstract fun groupDao(): GroupDao

    companion object {
        const val DATABASE_NAME = "dtn_mesh_relay.db"
    }
}
