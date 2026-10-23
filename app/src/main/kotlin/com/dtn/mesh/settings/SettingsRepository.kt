package com.dtn.mesh.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
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

    /** Discover mode: broadcast our name + keys to everyone (off: only to added contacts). */
    val discoverable: Flow<Boolean> = store.data.map { it[KEY_DISCOVER] ?: false }

    /** DiMaxProp λ (distance weight) and Q-learning η (progress-reward scale), 0..1. */
    val diMaxPropLambda: Flow<Double> = store.data.map { (it[KEY_LAMBDA] ?: DEFAULT_LAMBDA.toFloat()).toDouble() }
    val qShapingEta: Flow<Double> = store.data.map { (it[KEY_ETA] ?: DEFAULT_ETA.toFloat()).toDouble() }

    suspend fun displayNameOnce(): String = displayName.first()
    suspend fun internetEnabledOnce(): Boolean = internetEnabled.first()
    suspend fun brokerUrlOnce(): String = brokerUrl.first()
    suspend fun discoverableOnce(): Boolean = discoverable.first()

    suspend fun setDisplayName(name: String) { store.edit { it[KEY_NAME] = name } }
    suspend fun setInternetEnabled(enabled: Boolean) { store.edit { it[KEY_INET] = enabled } }
    suspend fun setBrokerUrl(url: String) { store.edit { it[KEY_BROKER] = url } }
    suspend fun setDiscoverable(on: Boolean) { store.edit { it[KEY_DISCOVER] = on } }
    suspend fun setDiMaxPropLambda(v: Double) { store.edit { it[KEY_LAMBDA] = v.coerceIn(0.0, 1.0).toFloat() } }
    suspend fun setQShapingEta(v: Double) { store.edit { it[KEY_ETA] = v.coerceIn(0.0, 1.0).toFloat() } }

    companion object {
        /** TLS by default: hides topics/presence from the network path (payloads are E2E anyway). */
        const val DEFAULT_BROKER = com.dtn.mesh.receiver.RelayEndpoints.DEFAULT_URL
        const val DEFAULT_LAMBDA = 0.5
        const val DEFAULT_ETA = 0.2
        private val KEY_NAME = stringPreferencesKey("display_name")
        private val KEY_INET = booleanPreferencesKey("internet_enabled")
        private val KEY_BROKER = stringPreferencesKey("broker_url")
        private val KEY_DISCOVER = booleanPreferencesKey("discoverable")
        private val KEY_LAMBDA = floatPreferencesKey("dimaxprop_lambda")
        private val KEY_ETA = floatPreferencesKey("q_shaping_eta")
    }
}
