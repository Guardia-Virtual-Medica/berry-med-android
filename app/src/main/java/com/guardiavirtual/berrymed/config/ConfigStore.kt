package com.guardiavirtual.berrymed.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "berry_config")

class ConfigStore(private val context: Context) {
    val configFlow: Flow<TotemConfig> = context.dataStore.data.map { prefs ->
        TotemConfig(
            apiBaseUrl = prefs[KEY_API_BASE] ?: "",
            totemId = prefs[KEY_TOTEM_ID] ?: "",
            apiEndpoint = prefs[KEY_API_ENDPOINT] ?: "metrics",
            apiUsername = prefs[KEY_API_USER] ?: "",
            apiPassword = prefs[KEY_API_PASS] ?: "",
            pusherKey = prefs[KEY_PUSHER_KEY] ?: "",
            pusherCluster = prefs[KEY_PUSHER_CLUSTER] ?: "us2",
            publicChannel = prefs[KEY_PUBLIC_CHANNEL] ?: "",
            startEventName = prefs[KEY_START_EVENT] ?: "start-monitoring",
            stopEventName = prefs[KEY_STOP_EVENT] ?: "stop-monitoring",
            maxSessionMinutes = prefs[KEY_MAX_SESSION] ?: 30,
            ecgGain = prefs[KEY_ECG_GAIN] ?: "x1",
            ecgMode = prefs[KEY_ECG_MODE] ?: "monitor",
            respGain = prefs[KEY_RESP_GAIN] ?: "x1",
            nibpMode = prefs[KEY_NIBP_MODE] ?: "adulto",
            nibpTargetPressureMmhg = prefs[KEY_NIBP_TARGET]?.takeIf { it.isNotBlank() }?.toIntOrNull(),
            thermometerEnabled = prefs[KEY_THERM_ENABLED] ?: true,
        )
    }

    suspend fun save(config: TotemConfig) {
        context.dataStore.edit { prefs ->
            prefs[KEY_API_BASE] = config.apiBaseUrl.trim()
            prefs[KEY_TOTEM_ID] = config.totemId.trim()
            prefs[KEY_API_ENDPOINT] = config.apiEndpoint.trim()
            prefs[KEY_API_USER] = config.apiUsername
            prefs[KEY_API_PASS] = config.apiPassword
            prefs[KEY_PUSHER_KEY] = config.pusherKey
            prefs[KEY_PUSHER_CLUSTER] = config.pusherCluster
            prefs[KEY_PUBLIC_CHANNEL] = config.publicChannel
            prefs[KEY_START_EVENT] = config.startEventName
            prefs[KEY_STOP_EVENT] = config.stopEventName
            prefs[KEY_MAX_SESSION] = config.maxSessionMinutes
            prefs[KEY_ECG_GAIN] = config.ecgGain
            prefs[KEY_ECG_MODE] = config.ecgMode
            prefs[KEY_RESP_GAIN] = config.respGain
            prefs[KEY_NIBP_MODE] = config.nibpMode
            prefs[KEY_NIBP_TARGET] = config.nibpTargetPressureMmhg?.toString() ?: ""
            prefs[KEY_THERM_ENABLED] = config.thermometerEnabled
        }
    }

    companion object {
        private val KEY_API_BASE = stringPreferencesKey("api_base_url")
        private val KEY_TOTEM_ID = stringPreferencesKey("totem_id")
        private val KEY_API_ENDPOINT = stringPreferencesKey("api_endpoint")
        private val KEY_API_USER = stringPreferencesKey("api_username")
        private val KEY_API_PASS = stringPreferencesKey("api_password")
        private val KEY_PUSHER_KEY = stringPreferencesKey("pusher_key")
        private val KEY_PUSHER_CLUSTER = stringPreferencesKey("pusher_cluster")
        private val KEY_PUBLIC_CHANNEL = stringPreferencesKey("public_channel")
        private val KEY_START_EVENT = stringPreferencesKey("start_event")
        private val KEY_STOP_EVENT = stringPreferencesKey("stop_event")
        private val KEY_MAX_SESSION = intPreferencesKey("max_session_minutes")
        private val KEY_ECG_GAIN = stringPreferencesKey("ecg_gain")
        private val KEY_ECG_MODE = stringPreferencesKey("ecg_mode")
        private val KEY_RESP_GAIN = stringPreferencesKey("resp_gain")
        private val KEY_NIBP_MODE = stringPreferencesKey("nibp_mode")
        private val KEY_NIBP_TARGET = stringPreferencesKey("nibp_target")
        private val KEY_THERM_ENABLED = booleanPreferencesKey("thermometer_enabled")
    }
}
