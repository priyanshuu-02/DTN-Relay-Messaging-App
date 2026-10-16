package com.dtn.mesh.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "dtn_settings")

/**
 * User settings backed by Jetpack DataStore: display name (shown in announces), the optional
 * internet-relay transport (off by default), and the MQTT broker URL.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store = context.dataStore

    val displayName: Flow<String> = store.data.map { it[KEY_NAME] ?: "" }
    val internetEnabled: Flow<Boolean> = store.data.map { it[KEY_INET] ?: false }
    val brokerUrl: Flow<String> = store.data.map { it[KEY_BROKER] ?: DEFAULT_BROKER }

    suspend fun displayNameOnce(): String = displayName.first()
    suspend fun internetEnabledOnce(): Boolean = internetEnabled.first()
    suspend fun brokerUrlOnce(): String = brokerUrl.first()

    suspend fun setDisplayName(name: String) { store.edit { it[KEY_NAME] = name } }
    suspend fun setInternetEnabled(enabled: Boolean) { store.edit { it[KEY_INET] = enabled } }
    suspend fun setBrokerUrl(url: String) { store.edit { it[KEY_BROKER] = url } }

    companion object {
        const val DEFAULT_BROKER = "tcp://broker.hivemq.com:1883"
        private val KEY_NAME = stringPreferencesKey("display_name")
        private val KEY_INET = booleanPreferencesKey("internet_enabled")
        private val KEY_BROKER = stringPreferencesKey("broker_url")
    }
}
