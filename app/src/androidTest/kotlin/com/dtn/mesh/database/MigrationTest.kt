package com.dtn.mesh.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room migrations against the exported schemas (app/schemas). Runs on a device:
 * `./gradlew.bat :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val testDb = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migrate3To4_keepsAllRows_andKeepsFriendsInTheChatList() {
        helper.createDatabase(testDb, 3).apply {
            fun contact(id: String, verified: Int) = execSQL(
                "INSERT INTO contacts (node_id, delivery_probability, last_aged_at_ms, path_likelihood, " +
                    "total_encounters, total_encounter_duration_ms, first_encounter_ms, last_encounter_ms, best_rssi, " +
                    "best_snr, last_rssi, last_snr, delivery_success_count, delivery_attempt_count, " +
                    "total_inter_contact_time_ms, is_online, key_verified, key_first_seen_ms, announce_name) " +
                    "VALUES (?, 0.4, 0, 0, 3, 0, 0, 0, -60, 0, -60, 0, 0, 0, 0, 0, ?, 0, ?)",
                arrayOf<Any?>(id, verified, "name-$id"),
            )
            contact("!0000000a", 0) // chatted with 1:1 → friend
            contact("!0000000b", 1) // verified in person by QR → friend
            contact("!0000000c", 0) // only sent a broadcast → Discovered
            contact("!0000000d", 0) // only in a group chat → Discovered
            fun chat(id: String, conv: String, peer: String, channel: Int) = execSQL(
                "INSERT INTO chat_messages (id, conv_key, peer_node_id, channel, direction, text, status, created_at_ms) " +
                    "VALUES (?, ?, ?, ?, 'IN', 'x', 'RECEIVED', 1)",
                arrayOf<Any?>(id, conv, peer, channel),
            )
            chat("m1", "!0000000a", "!0000000a", 0)
            chat("m2", "grp:7", "!0000000d", 7)
            chat("m3", "^all", "!0000000c", 0)
            execSQL(
                "INSERT INTO forwarding_decisions (timestamp_ms, message_id, peer_node_id, state_rssi_norm, " +
                    "state_snr_norm, state_encounter_frequency, state_encounter_duration, state_delivery_probability, " +
                    "state_remaining_ttl_fraction, state_buffer_occupancy, state_historical_success_rate, " +
                    "state_duplicate_count_norm, action, q_value_selected, epsilon, was_exploratory, routing_strategy, " +
                    "reward, update_applied) " +
                    "VALUES (1, 'm1', '!0000000a', 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0, 'FORWARD', 0, 1, 1, " +
                    "'Q-LEARNING', NULL, 0)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 4, true)

        val added = HashMap<String, Int>()
        db.query("SELECT node_id, is_added, announce_name FROM contacts").use { c ->
            while (c.moveToNext()) {
                added[c.getString(0)] = c.getInt(1)
                assertEquals("name-${c.getString(0)}", c.getString(2)) // existing data kept
            }
        }
        assertEquals(mapOf("!0000000a" to 1, "!0000000b" to 1, "!0000000c" to 0, "!0000000d" to 0), added)

        db.query("SELECT COUNT(*) FROM chat_messages").use { c ->
            c.moveToFirst()
            assertEquals(3, c.getInt(0))
        }
        db.query(
            "SELECT state_dimaxprop_cost, state_hop_count_norm, state_relay_buffer, shaping_reward, action " +
                "FROM forwarding_decisions",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1.0, c.getDouble(0), 0.0)
            assertEquals(0.0, c.getDouble(1), 0.0)
            assertEquals(0.0, c.getDouble(2), 0.0)
            assertTrue(c.isNull(3))
            assertEquals("FORWARD", c.getString(4))
        }
        db.close()
    }
}
