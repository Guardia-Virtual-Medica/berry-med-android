// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.tele

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KioskUrlsTest {
    private val kiosk = "https://kiosco.example.com/clinica"

    @Test
    fun theWaitingRoomUrlBringsServerAndToken() {
        val found = KioskUrls.credentials("https://kiosco.example.com/clinica/waiting-room?serverUrl=gvm-dev&token=abc.def.ghi")
        assertEquals(TeleCredentials("wss://gvm-dev.livekit.cloud", "abc.def.ghi"), found)
        val resume = KioskUrls.credentials("https://kiosco.example.com/cmd/sessionResume?sessionId=7&serverUrl=gvm&token=t1#x")
        assertEquals(TeleCredentials("wss://gvm.livekit.cloud", "t1"), resume)
    }

    @Test
    fun theProviderSchemeAlsoWorks() {
        val url = "koplak://auth?serverUrl=gvm&token=t2"
        assertTrue(KioskUrls.isProviderScheme(url))
        assertEquals(TeleCredentials("wss://gvm.livekit.cloud", "t2"), KioskUrls.credentials(url))
        assertFalse(KioskUrls.isProviderScheme("https://kiosco.example.com/?serverUrl=gvm&token=t2"))
    }

    @Test
    fun aServerThatIsAlreadyAnUrlGoesAsItIs() {
        assertEquals("ws://10.0.2.2:7880", KioskUrls.serverFor("ws://10.0.2.2:7880"))
        assertEquals("wss://lk.example.com", KioskUrls.serverFor(" wss://lk.example.com "))
        assertEquals("https://lk.example.com", KioskUrls.serverFor("https://lk.example.com"))
        assertEquals("wss://gvm-x1.livekit.cloud", KioskUrls.serverFor("gvm-x1"))
        // Codificado en la dirección.
        assertEquals(
            TeleCredentials("ws://10.0.2.2:7880", "t3"),
            KioskUrls.credentials("http://10.0.2.2:8000/waiting-room?serverUrl=ws%3A%2F%2F10.0.2.2%3A7880&token=t3"),
        )
    }

    @Test
    fun withoutServerOrTokenThereAreNoCredentials() {
        assertNull(KioskUrls.credentials("https://kiosco.example.com/clinica/waiting-room"))
        assertNull(KioskUrls.credentials("https://kiosco.example.com/clinica/waiting-room?token=t"))
        assertNull(KioskUrls.credentials("https://kiosco.example.com/clinica/waiting-room?serverUrl=gvm&token="))
    }

    @Test
    fun homeLoginAndSetupAreScreensWithoutEncounter() {
        assertTrue(KioskUrls.isHome("https://kiosco.example.com/clinica", kiosk))
        assertTrue(KioskUrls.isHome("https://kiosco.example.com/clinica/", kiosk))
        assertTrue(KioskUrls.isHome("https://kiosco.example.com/clinica/login?next=x", kiosk))
        assertTrue(KioskUrls.isHome("https://kiosco.example.com/clinica/setup", kiosk))
        assertTrue(KioskUrls.isHome("https://kiosco.example.com/", kiosk))
    }

    @Test
    fun theEncounterScreensAndOtherOriginsAreNotHome() {
        assertFalse(KioskUrls.isHome("https://kiosco.example.com/clinica/waiting-room", kiosk))
        assertFalse(KioskUrls.isHome("https://kiosco.example.com/clinica/videocall", kiosk))
        assertFalse(KioskUrls.isHome("https://kiosco.example.com/clinica?serverUrl=gvm&token=t", kiosk))
        assertFalse(KioskUrls.isHome("https://zoom.us/clinica", kiosk))
        assertFalse(KioskUrls.isHome("http://kiosco.example.com/clinica", kiosk))
    }

    @Test
    fun theOriginIsSchemeHostAndPort() {
        assertEquals("https://kiosco.example.com", KioskUrls.origin("https://Kiosco.example.com/clinica?x=1"))
        assertEquals("http://10.0.2.2:8000", KioskUrls.origin("http://10.0.2.2:8000/kiosco.html"))
        assertNull(KioskUrls.origin("no es una dirección"))
    }

    @Test
    fun theTokenGivesItsRoomAndExpiryWithoutTheSignature() {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val body = encoder.encodeToString("""{"exp":1793300000,"video":{"room":"encounter-9b2f","roomJoin":true}}""".toByteArray())
        val info = KioskUrls.tokenInfo("${encoder.encodeToString("{}".toByteArray())}.$body.firma")
        assertEquals(TokenInfo("encounter-9b2f", 1_793_300_000_000L), info)
        assertEquals("9b2f", KioskUrls.encounterOf(info.room))
        assertNull(KioskUrls.encounterOf("prueba"))
        assertEquals(TokenInfo(null, null), KioskUrls.tokenInfo("roto"))
    }

    @Test
    fun theConsoleOfTheKioskLosesItsTokens() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl"
        val text = "GET https://x/waiting-room?serverUrl=gvm&token=$jwt falló; access_token=abc"
        val clean = KioskUrls.redact(text)
        assertFalse(clean.contains(jwt))
        assertFalse(clean.contains("abc"))
        assertTrue(clean.contains("serverUrl=gvm"))
        assertFalse(TeleCredentials("wss://x", jwt).toString().contains(jwt))
    }
}
