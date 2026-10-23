package com.dtn.mesh.service

import android.content.Context
import android.util.Log
import com.dtn.mesh.learning.DoubleQLearningEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the Q-learning tables (and the exploration rate) across app restarts in
 * `filesDir/q_tables_v2.bin`. Before this the engine started from an empty table, fully random
 * (ε = 1), on every launch, so it never got past exploring.
 */
@Singleton
class QTableStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val file: File get() = File(context.filesDir, FILE_NAME)

    /** Load saved tables into [engine]. False when there is nothing (compatible) to load. */
    fun load(engine: DoubleQLearningEngine): Boolean = runCatching {
        val f = file
        f.exists() && engine.loadBytes(f.readBytes())
    }.onFailure { Log.w(TAG, "Q-table load failed", it) }.getOrDefault(false)

    /** Save [engine]'s tables (atomic replace). Call off the main thread. */
    fun save(engine: DoubleQLearningEngine) {
        runCatching {
            val tmp = File(context.filesDir, "$FILE_NAME.tmp")
            tmp.writeBytes(engine.toBytes())
            val f = file
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }.onFailure { Log.w(TAG, "Q-table save failed", it) }
    }

    private companion object {
        const val TAG = "QTableStore"
        const val FILE_NAME = "q_tables_v2.bin"
    }
}
