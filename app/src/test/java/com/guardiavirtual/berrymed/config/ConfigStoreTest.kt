// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.config

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class ConfigStoreTest {
    private val legacyHeadset = intPreferencesKey("ykush_headset_port")
    private val thermometer = intPreferencesKey("ykush_thermometer_port")
    private val otoscope = intPreferencesKey("ykush_otoscope_port")

    @Test
    fun aConfigSavedBeforeThePlan13PutsTheThermometerOnPort3() {
        val saved = preferencesOf(
            stringPreferencesKey("environment") to Environment.Prod.key,
            otoscope to 1,
            intPreferencesKey("ykush_stethoscope_port") to 2,
            legacyHeadset to 1,
        )
        val config = ConfigStore.read(saved)
        assertEquals(3, config.ykushThermometerPort)
        assertEquals(1, config.ykushOtoscopePort)
        assertEquals(2, config.ykushStethoscopePort)
    }

    @Test
    fun theSavedThermometerPortIsRead() {
        val config = ConfigStore.read(preferencesOf(thermometer to 2, legacyHeadset to 3))
        assertEquals(2, config.ykushThermometerPort)
    }

    @Test
    fun savingWritesTheThermometerAndDropsTheOldHeadsetKey() {
        val prefs = mutablePreferencesOf(legacyHeadset to 3, otoscope to 1)
        ConfigStore.write(prefs, BerryConfig(ykushThermometerPort = 3))
        assertEquals(3, prefs[thermometer])
        assertNull(prefs[legacyHeadset])
        assertFalse(legacyHeadset in prefs)
        assertEquals(BerryConfig(ykushThermometerPort = 3), ConfigStore.read(prefs))
    }

    @Test
    fun theLastSaveTimeIsKeptAndACopyWithoutItDoesNotEraseIt() {
        val prefs = mutablePreferencesOf()
        assertEquals(0L, ConfigStore.read(prefs).savedAt)
        ConfigStore.write(prefs, BerryConfig(savedAt = 1_763_000_000_000L))
        assertEquals(1_763_000_000_000L, ConfigStore.read(prefs).savedAt)
        // Lo que se guarda sin pasar por USB-config (el sonido del panel lateral) parte de lo leído.
        ConfigStore.write(prefs, ConfigStore.read(prefs).copy(callAudioOutput = "parlante"))
        assertEquals(1_763_000_000_000L, ConfigStore.read(prefs).savedAt)
    }
}
