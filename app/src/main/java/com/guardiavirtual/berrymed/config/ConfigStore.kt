// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.config

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import java.io.IOException

// Un archivo corrupto vuelve a la configuración vacía en vez de cerrar la app en cada arranque.
private val Context.store by preferencesDataStore(
    name = "berry_config",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class ConfigStore(private val context: Context) {
    // Un error de lectura se reintenta: si el flujo terminara, el tótem no
    // vería ningún guardado posterior hasta reiniciar.
    val flow: Flow<BerryConfig> = context.store.data
        .map { it.toConfig() }
        .retryWhen { cause, _ ->
            if (cause !is IOException) return@retryWhen false
            delay(5_000)
            true
        }
        .catch { emit(BerryConfig()) }

    suspend fun load(): BerryConfig = context.store.data.first().toConfig()

    suspend fun save(config: BerryConfig) {
        context.store.edit { prefs ->
            prefs[ENVIRONMENT] = config.environment
            prefs[API_URL] = config.apiBaseUrl
            prefs[TOTEM] = config.totemId
            prefs[ENDPOINT] = config.apiEndpoint
            prefs[USER] = config.apiUsername
            prefs[PASSWORD] = config.apiPassword
            prefs[PUSHER_KEY] = config.pusherKey
            prefs[CLUSTER] = config.pusherCluster
            prefs[CHANNEL] = config.publicChannel
            prefs[START] = config.startEventName
            prefs[STOP] = config.stopEventName
            prefs[ECG_GAIN] = config.ecgGain
            prefs[ECG_MODE] = config.ecgMode
            prefs[RESP_GAIN] = config.respGain
            prefs[NIBP_MODE] = config.nibpMode
            prefs[NIBP_TARGET] = config.nibpTargetPressureMmhg
            prefs[YKUSH_OTOSCOPE] = config.ykushOtoscopePort
            prefs[YKUSH_STETHOSCOPE] = config.ykushStethoscopePort
            prefs[YKUSH_HEADSET] = config.ykushHeadsetPort
            prefs[LOCAL_PIN] = config.localPanelPin
            prefs[STETHO_INPUT] = config.stethoInputName
            prefs[HEADSET] = config.headsetName
            prefs[KIOSK_URL] = config.kioskUrl
        }
    }

    private fun Preferences.toConfig(): BerryConfig {
        val saved = this[ENVIRONMENT]
        if (saved != null) return toConfig(Environment.fromKey(saved), keepAddresses = true)
        // Guardada antes del selector: la dirección y la clave se cargaban a mano y
        // ahí estaban los errores. Se toman las del entorno que corresponde.
        return toConfig(Environment.guess(this[API_URL]), keepAddresses = false)
    }

    /** Lo que nunca se guardó, o lo anterior al selector, toma el valor del entorno. */
    private fun Preferences.toConfig(environment: Environment, keepAddresses: Boolean): BerryConfig {
        val savedKey = this[PUSHER_KEY]?.takeIf { keepAddresses }
        val replacement = savedKey?.let(Environment::replacementFor)
        val pusherKey = replacement?.pusherKey ?: savedKey ?: environment.pusherKey
        val pusherCluster = replacement?.pusherCluster ?: this[CLUSTER]?.takeIf { keepAddresses } ?: environment.pusherCluster
        return toConfig(environment, keepAddresses, pusherKey, pusherCluster)
    }

    private fun Preferences.toConfig(
        environment: Environment,
        keepAddresses: Boolean,
        pusherKey: String,
        pusherCluster: String,
    ): BerryConfig = BerryConfig(
        environment = environment.key,
        apiBaseUrl = this[API_URL]?.takeIf { keepAddresses } ?: environment.apiBaseUrl,
        totemId = this[TOTEM].orEmpty(),
        apiEndpoint = this[ENDPOINT] ?: "metrics",
        apiUsername = this[USER].orEmpty(),
        apiPassword = this[PASSWORD].orEmpty(),
        pusherKey = pusherKey,
        pusherCluster = pusherCluster,
        publicChannel = this[CHANNEL].orEmpty(),
        startEventName = this[START] ?: "start-monitoring",
        stopEventName = this[STOP] ?: "stop-monitoring",
        ecgGain = this[ECG_GAIN] ?: "x1",
        ecgMode = this[ECG_MODE] ?: "monitor",
        respGain = this[RESP_GAIN] ?: "x1",
        nibpMode = this[NIBP_MODE] ?: "adulto",
        nibpTargetPressureMmhg = this[NIBP_TARGET].orEmpty(),
        ykushOtoscopePort = this[YKUSH_OTOSCOPE] ?: 1,
        ykushStethoscopePort = this[YKUSH_STETHOSCOPE] ?: 2,
        ykushHeadsetPort = this[YKUSH_HEADSET] ?: 3,
        localPanelPin = this[LOCAL_PIN] ?: DEFAULT_LOCAL_PIN,
        stethoInputName = this[STETHO_INPUT]?.takeIf { it.isNotBlank() } ?: DEFAULT_STETHO_INPUT,
        headsetName = this[HEADSET]?.takeIf { it.isNotBlank() } ?: DEFAULT_HEADSET,
        kioskUrl = this[KIOSK_URL].orEmpty(),
    )

    companion object {
        private val ENVIRONMENT = stringPreferencesKey("environment")
        private val API_URL = stringPreferencesKey("api_url")
        private val TOTEM = stringPreferencesKey("totem_id")
        private val ENDPOINT = stringPreferencesKey("api_endpoint")
        private val USER = stringPreferencesKey("api_username")
        private val PASSWORD = stringPreferencesKey("api_password")
        private val PUSHER_KEY = stringPreferencesKey("pusher_key")
        private val CLUSTER = stringPreferencesKey("pusher_cluster")
        private val CHANNEL = stringPreferencesKey("public_channel")
        private val START = stringPreferencesKey("start_event")
        private val STOP = stringPreferencesKey("stop_event")
        private val ECG_GAIN = stringPreferencesKey("ecg_gain")
        private val ECG_MODE = stringPreferencesKey("ecg_mode")
        private val RESP_GAIN = stringPreferencesKey("resp_gain")
        private val NIBP_MODE = stringPreferencesKey("nibp_mode")
        private val NIBP_TARGET = stringPreferencesKey("nibp_target")
        private val YKUSH_OTOSCOPE = intPreferencesKey("ykush_otoscope_port")
        private val YKUSH_STETHOSCOPE = intPreferencesKey("ykush_stethoscope_port")
        private val YKUSH_HEADSET = intPreferencesKey("ykush_headset_port")
        private val LOCAL_PIN = stringPreferencesKey("local_panel_pin")
        private val STETHO_INPUT = stringPreferencesKey("stetho_input_name")
        private val HEADSET = stringPreferencesKey("headset_name")
        private val KIOSK_URL = stringPreferencesKey("kiosk_url")
    }
}
