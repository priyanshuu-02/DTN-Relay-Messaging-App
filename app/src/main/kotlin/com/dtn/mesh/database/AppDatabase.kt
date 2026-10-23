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
    // v4: contacts.is_added (Discover mode) + the DiMaxProp / hop / relay-buffer Q-learning state
    // columns and shaping_reward on forwarding_decisions.
    version = 4,
    exportSchema = true,
    // Both steps are purely additive, so Room generates them from the exported schemas; 3 → 4 also
    // runs [Migration3To4.onPostMigrate] to keep existing friends in the chat list. The destructive
    // fallback in DatabaseModule remains only as a safety net for an unknown version jump.
    autoMigrations = [
        androidx.room.AutoMigration(from = 2, to = 3),
        androidx.room.AutoMigration(from = 3, to = 4, spec = AppDatabase.Migration3To4::class),
    ],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    /**
     * Contacts we have already chatted with 1:1, or verified in person by QR, are friends: they
     * stay in the chat list. Everyone else (seen nearby or on the relay only) moves to Discovered.
     */
    class Migration3To4 : androidx.room.migration.AutoMigrationSpec {
        override fun onPostMigrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL(MARK_FRIENDS_ADDED_SQL)
        }
    }

    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun encounterDao(): EncounterDao
    abstract fun forwardingDecisionDao(): ForwardingDecisionDao
    abstract fun chatDao(): ChatDao
    abstract fun seenBundleDao(): SeenBundleDao
    abstract fun groupDao(): GroupDao

    companion object {
        const val DATABASE_NAME = "dtn_mesh_relay.db"

        /** v3 → v4: 1:1 chat partners (conv_key = their node id) and QR-verified contacts are added. */
        const val MARK_FRIENDS_ADDED_SQL = """
            UPDATE contacts SET is_added = 1
            WHERE key_verified = 1
               OR node_id IN (
                   SELECT DISTINCT conv_key FROM chat_messages
                   WHERE conv_key NOT LIKE 'grp:%' AND conv_key != '^all'
               )
        """
    }
}
