// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BerryConfigTest {
    private val ready = BerryConfig(pusherKey = "clave", publicChannel = "totem.01")

    @Test
    fun theCallSoundsThroughTheHeadsetUnlessTheSpeakerWasChosen() {
        assertEquals(CALL_AUDIO_HEADSET, BerryConfig().callAudioOutput)
        assertEquals(CALL_AUDIO_SPEAKER, callAudioOutputOrDefault(CALL_AUDIO_SPEAKER))
        assertEquals(CALL_AUDIO_HEADSET, callAudioOutputOrDefault(CALL_AUDIO_HEADSET))
        assertEquals(CALL_AUDIO_HEADSET, callAudioOutputOrDefault("bluetooth"))
        assertEquals(CALL_AUDIO_HEADSET, callAudioOutputOrDefault(""))
        assertEquals(CALL_AUDIO_HEADSET, callAudioOutputOrDefault(null))
    }

    @Test
    fun aPublicChannelIsReady() {
        assertTrue(ready.pusherReady())
        assertTrue(ready.missingFields().none { "público" in it })
    }

    @Test
    fun aUrlWithoutSchemeIsNotPostedAndTheScreenSaysWhy() {
        val config = BerryConfig(apiBaseUrl = "api.ejemplo.com/v1", totemId = "t1", apiUsername = "u", apiPassword = "p")
        assertFalse(config.postReady())
        assertTrue(config.missingFields().any { "https://" in it })
        assertTrue(config.copy(apiBaseUrl = " HTTPS://api.ejemplo.com/v1").postReady())
        assertTrue(config.copy(apiBaseUrl = "http://10.0.0.5/v1").postReady())
    }

    @Test
    fun aPrivateOrPresenceChannelIsNotConnectedAndTheScreenSaysWhy() {
        for (channel in listOf("private-totem.01", "presence-totem.01")) {
            val config = ready.copy(publicChannel = channel)
            assertFalse(config.pusherReady())
            assertTrue(config.missingFields().any { "canal público" in it })
        }
    }

    @Test
    fun twoDevicesCannotShareAHubPortAndTheScreenSaysWhy() {
        assertFalse(ready.ykushPortsShared())
        assertTrue(ready.missingFields().none { "YKUSH3" in it })
        val shared = ready.copy(ykushOtoscopePort = 2, ykushStethoscopePort = 2)
        assertTrue(shared.ykushPortsShared())
        assertTrue(shared.missingFields().any { "YKUSH3" in it })
        assertTrue(ready.copy(ykushHeadsetPort = 1).ykushPortsShared())
    }

    @Test
    fun theLocalPanelPinIgnoresSpacesAndFallsBackTo1515() {
        assertTrue(pinAccepted("2468", "2468"))
        assertTrue(pinAccepted(" 2468 ", "2468 "))
        assertTrue(pinAccepted("1515", ""))
        assertTrue(pinAccepted("1515", null))
        assertFalse(pinAccepted("1515", "2468"))
        assertFalse(pinAccepted("", "2468"))
        assertFalse(pinAccepted(null, null))
    }

    @Test
    fun theKioskUrlIsEmptyOrHttpWithAHost() {
        assertTrue(kioskUrlAccepted(""))
        assertTrue(kioskUrlAccepted("   "))
        assertTrue(kioskUrlAccepted("https://kiosco.example.com/clinica"))
        assertTrue(kioskUrlAccepted(" HTTP://10.0.2.2:8000/kiosco.html "))
        assertFalse(kioskUrlAccepted("kiosco.example.com/clinica"))
        assertFalse(kioskUrlAccepted("ftp://kiosco.example.com"))
        assertFalse(kioskUrlAccepted("https://"))
        assertFalse(kioskUrlAccepted("https://kiosco example.com"))
    }
}
