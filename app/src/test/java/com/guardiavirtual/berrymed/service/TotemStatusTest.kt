// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.service

import com.guardiavirtual.berrymed.config.BerryConfig
import com.guardiavirtual.berrymed.i18n.es
import com.guardiavirtual.berrymed.i18n.inLanguage
import com.guardiavirtual.berrymed.net.PostOutcome
import kotlin.test.Test
import kotlin.test.assertEquals

/** Los textos de estado de la pantalla principal y de la notificación: el mismo español que armaba el runtime. */
class TotemStatusTest {
    @Test
    fun theNotificationSaysWhatIsMissingFirst() {
        assertEquals("Falta el permiso USB", es(TotemStatus.notification(permissionMissing = true, berryUp = true, localMode = true, active = true)))
        assertEquals("Esperando la plaqueta", es(TotemStatus.notification(permissionMissing = false, berryUp = false, localMode = true, active = true)))
        assertEquals("Panel Local abierto", es(TotemStatus.notification(permissionMissing = false, berryUp = true, localMode = true, active = true)))
        assertEquals(
            "Plaqueta conectada, enviando mediciones",
            es(TotemStatus.notification(permissionMissing = false, berryUp = true, localMode = false, active = true)),
        )
        assertEquals(
            "Plaqueta conectada, sesión detenida",
            es(TotemStatus.notification(permissionMissing = false, berryUp = true, localMode = false, active = false)),
        )
        // Es la misma clave que muestra el servicio al arrancar: no cambia la notificación sin motivo.
        assertEquals(TotemStatus.WAITING_BOARD, TotemStatus.notification(false, berryUp = false, localMode = false, active = false))
    }

    @Test
    fun theLastDataHasOneDecimalWithAComma() {
        assertEquals("Último dato: sin datos", es(TotemStatus.lastData(null)))
        assertEquals("Último dato: hace 0,4 s", es(TotemStatus.lastData(0.4)))
        assertEquals("Último dato: hace 12,3 s", es(TotemStatus.lastData(12.34)))
    }

    @Test
    fun theStatusLineIsWholeSentencesSeparatedBySpaces() {
        assertEquals(
            "Termómetro sin conectar. Hub YKUSH3 sin conectar. La sesión está detenida.",
            es(TotemState().others),
        )
        assertEquals(
            "Termómetro conectado. Hub YKUSH3 presente. La sesión está enviando.",
            es(TotemStatus.others(true, thermoMeasured = true, hubPlugged = true, active = true, missing = emptyList(), permissionMissing = false)),
        )
        val missing = BerryConfig(apiBaseUrl = "", totemId = "", publicChannel = "private-x").missingFields()
        assertEquals(
            "Termómetro conectado, sin mediciones todavía. Hub YKUSH3 sin conectar. La sesión está detenida. " +
                "Falta configurar: URL de la API, tótem, un canal público (sin private- ni presence-). " +
                "Falta aceptar el permiso USB: tocá la ruedita y «Pedir permiso USB».",
            es(TotemStatus.others(true, thermoMeasured = false, hubPlugged = false, active = false, missing = missing, permissionMissing = true)),
        )
    }

    @Test
    fun theLastPostSaysHowItWent() {
        assertEquals("Todavía no se envió", es(TotemState().lastPost))
        assertEquals("Último envío correcto", es(TotemStatus.lastPost(PostOutcome.Ok, active = true)))
        assertEquals("Esperando que se abra una toma de signos", es(TotemStatus.lastPost(PostOutcome.Http(404), active = false)))
        assertEquals("La plataforma no tiene la toma abierta (404)", es(TotemStatus.lastPost(PostOutcome.Http(404), active = true)))
        assertEquals("La API respondió 500", es(TotemStatus.lastPost(PostOutcome.Http(500), active = true)))
        assertEquals("No se pudo enviar: timeout", es(TotemStatus.lastPost(PostOutcome.Failed("timeout"), active = true)))
    }

    @Test
    fun droppedPacketsAreCountedInTheLastPost() {
        val ok = TotemStatus.lastPost(PostOutcome.Ok, active = true)
        assertEquals(ok, TotemStatus.withDropped(ok, 0))
        assertEquals("Último envío correcto · 1 paquete descartado por cola llena", es(TotemStatus.withDropped(ok, 1)))
        assertEquals("Último envío correcto · 12 paquetes descartados por cola llena", es(TotemStatus.withDropped(ok, 12)))
    }

    @Test
    fun theStateStartsWaitingForTheBoard() {
        val state = TotemState()
        assertEquals("Esperando la plaqueta", es(state.notification))
        assertEquals("Último dato: sin datos", es(state.lastData))
        assertEquals("—", state.heartRate)
    }

    /** Lo mismo leído en inglés y en portugués: cada idioma con su decimal y su plural. */
    @Test
    fun theSameTextsInOtherLanguages() {
        val sending = TotemStatus.notification(permissionMissing = false, berryUp = true, localMode = false, active = true)
        assertEquals("Board connected, sending measurements", sending.inLanguage("en"))
        assertEquals("Placa conectada, enviando medições", sending.inLanguage("pt"))
        assertEquals("Last data: 12.3 s ago", TotemStatus.lastData(12.34).inLanguage("en"))
        assertEquals("Último dado: há 12,3 s", TotemStatus.lastData(12.34).inLanguage("pt"))
        assertEquals("Thermometer not connected. YKUSH3 hub not connected. The session is stopped.", TotemState().others.inLanguage("en"))
        assertEquals("Termômetro não conectado. Hub YKUSH3 não conectado. A sessão está parada.", TotemState().others.inLanguage("pt"))
        val ok = TotemStatus.lastPost(PostOutcome.Ok, active = true)
        assertEquals("Last send succeeded · 1 packet dropped because the queue was full", TotemStatus.withDropped(ok, 1).inLanguage("en"))
        assertEquals("Last send succeeded · 12 packets dropped because the queue was full", TotemStatus.withDropped(ok, 12).inLanguage("en"))
        assertEquals("Último envio bem-sucedido · 12 pacotes descartados por fila cheia", TotemStatus.withDropped(ok, 12).inLanguage("pt"))
        assertEquals("The API responded 500", TotemStatus.lastPost(PostOutcome.Http(500), active = true).inLanguage("en"))
    }
}
