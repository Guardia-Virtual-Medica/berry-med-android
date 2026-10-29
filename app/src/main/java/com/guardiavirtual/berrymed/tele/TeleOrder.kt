// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.tele

import com.google.gson.JsonElement
import com.google.gson.JsonParser

/** Los dos instrumentos que van a la teleconsulta. */
enum class Instrument(val label: String) {
    OTOSCOPE("otoscopio"),
    STETHOSCOPE("estetoscopio"),
}

/** Una orden de Pusher para la teleconsulta: encender o apagar un instrumento de un encuentro. */
data class TeleOrder(val instrument: Instrument, val start: Boolean, val encounterId: String)

/**
 * Los eventos que manda la API cuando el kiosco toca Encender o Apagar
 * (`AbstractEncounterTotemBroadcast`), con `{encounter_external_id, totem_id}`.
 * Sin Android, para probarlo solo.
 */
object TeleOrders {
    val EVENTS: Map<String, Pair<Instrument, Boolean>> = linkedMapOf(
        "start_oth" to (Instrument.OTOSCOPE to true),
        "stop_oth" to (Instrument.OTOSCOPE to false),
        "start_steth" to (Instrument.STETHOSCOPE to true),
        "stop_steth" to (Instrument.STETHOSCOPE to false),
    )

    /**
     * La orden de [event] con el cuerpo [body], o null si el evento no es de
     * los instrumentos o el cuerpo no trae `encounter_external_id`. Laravel a
     * veces manda el JSON como un string dentro de otro: se desenvuelve.
     */
    fun parse(event: String, body: String?): TeleOrder? {
        val (instrument, start) = EVENTS[event] ?: return null
        val id = encounterId(body) ?: return null
        return TeleOrder(instrument, start, id)
    }

    fun encounterId(body: String?): String? {
        var element: JsonElement = try {
            JsonParser.parseString(body ?: return null)
        } catch (_: Exception) {
            return null
        }
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            element = try {
                JsonParser.parseString(element.asString)
            } catch (_: Exception) {
                return null
            }
        }
        if (!element.isJsonObject) return null
        val value = element.asJsonObject.get("encounter_external_id") ?: return null
        if (!value.isJsonPrimitive) return null
        return value.asString.trim().ifEmpty { null }
    }
}
