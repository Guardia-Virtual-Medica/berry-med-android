// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.tele

import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Cómo está la sala de LiveKit, para Testing y el log. */
enum class RoomState(val label: String) {
    DISCONNECTED("desconectada"),
    CONNECTING("conectando"),
    CONNECTED("conectada"),
    ERROR("error"),
}

data class TeleStatus(
    val room: RoomState = RoomState.DISCONNECTED,
    /** El instrumento que se está publicando; uno solo a la vez, como en el kiosco. */
    val instrument: Instrument? = null,
    val encounterId: String? = null,
    /** Si la sala la abrió la prueba manual de Testing (servidor y token a mano). */
    val manual: Boolean = false,
    /** El último error, o vacío. */
    val message: String = "",
)

/**
 * Lo que el streamer le pide al mundo: la API, LiveKit, los equipos. Todo
 * bloquea y se llama desde el hilo del streamer. Así la máquina de estados se
 * prueba sola, con un falso.
 */
interface TeleBackend {
    /** null si [instrument] se puede usar ahora; si no, el motivo (Panel Local abierto, sin permiso). */
    fun blocked(instrument: Instrument): String?

    fun fetchToken(encounterId: String): TokenOutcome

    /** Entra a la sala. null si entró; si no, el motivo (y no queda nada abierto). */
    fun connect(credentials: TeleCredentials): String?

    /** Enciende el instrumento y publica su pista. null si quedó publicando; si no, el motivo (y no queda nada abierto). */
    fun publish(instrument: Instrument): String?

    /** Despublica y suelta el instrumento (y apaga el puerto si lo encendió el streamer). */
    fun unpublish(instrument: Instrument)

    /** Sale de la sala y suelta todo. */
    fun disconnect()
}

/**
 * Dueño de la sala de LiveKit de la teleconsulta: entra al recibir la orden
 * de un instrumento, publica uno a la vez y sale cuando no queda ninguno.
 *
 * Todo pasa en un solo hilo ([executor]), en el orden en que llegan las
 * órdenes: un Encender seguido de un Apagar nunca se cruzan. Los errores
 * van al log `[TELE]` y a [status]; nada se reintenta solo: la próxima orden
 * del kiosco vuelve a probar.
 */
class TeleStreamer(
    private val backend: TeleBackend,
    private val log: (String) -> Unit,
    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "berry-tele").apply { isDaemon = true }
    },
) {
    @Volatile var status: TeleStatus = TeleStatus()
        private set

    /** Una orden de Pusher. Sólo cuenta si la sala no la tiene tomada la prueba de Testing. */
    fun order(order: TeleOrder) {
        run("orden ${order.instrument.label}") {
            if (status.manual) {
                log("[TELE] Llegó ${describe(order)} con la prueba de Testing publicando: se ignora")
                return@run
            }
            if (order.start) start(order.instrument, order.encounterId) else stopOrder(order)
        }
    }

    /** La prueba manual de Testing: un servidor y un token hechos a mano, sin pedirle nada a la API. */
    fun startManual(serverUrl: String, token: String, instrument: Instrument) {
        run("prueba manual") {
            val credentials = TeleCredentials(serverUrl.trim(), "", token.trim())
            if (status.room == RoomState.CONNECTED && !status.manual) {
                closeAll("se abrió la prueba manual de Testing")
            }
            if (status.room != RoomState.CONNECTED) {
                if (credentials.serverUrl.isEmpty() || credentials.token.isEmpty()) {
                    fail("Faltan el servidor o el token", manual = true)
                    return@run
                }
                if (!join(credentials, encounterId = null, manual = true)) return@run
            }
            publishOnly(instrument)
        }
    }

    /** Cortar la prueba manual: lo que esté publicando y la sala. */
    fun stopManual() {
        run("cortar la prueba") {
            if (!status.manual) return@run
            closeAll("se cortó la prueba de Testing")
        }
    }

    /**
     * Cierra todo: fin de la consulta (`stop-monitoring`) o el Panel Local que
     * se abre. La prueba de Testing sólo se corta con [includeManual].
     */
    fun stopAll(reason: String, includeManual: Boolean = false) {
        run("cerrar") {
            if (status.manual && !includeManual) return@run
            if (status.room == RoomState.DISCONNECTED && status.instrument == null) return@run
            closeAll(reason)
        }
    }

    /** LiveKit avisa que la sala se cayó y no pudo volver: se suelta todo y queda el error. */
    fun roomLost(reason: String) {
        run("sala perdida") {
            if (status.room != RoomState.CONNECTED) return@run
            log("[TELE] Se cortó la sala: $reason")
            status.instrument?.let(backend::unpublish)
            backend.disconnect()
            status = TeleStatus(room = RoomState.ERROR, manual = status.manual, message = "Se cortó la sala: $reason")
        }
    }

    private fun start(instrument: Instrument, encounterId: String) {
        val blocked = backend.blocked(instrument)
        if (blocked != null) {
            // No hace nada: queda en el log y, para Testing, como último error.
            log("[TELE] No se enciende el ${instrument.label}: $blocked")
            status = status.copy(message = blocked)
            return
        }
        if (status.room == RoomState.CONNECTED && status.encounterId != encounterId) {
            closeAll("llegó una orden de otro encuentro")
        }
        if (status.room != RoomState.CONNECTED) {
            status = TeleStatus(room = RoomState.CONNECTING, encounterId = encounterId)
            log("[TELE] Pidiendo el token del encuentro $encounterId")
            when (val token = backend.fetchToken(encounterId)) {
                is TokenOutcome.Failed -> {
                    fail(token.message, encounterId = encounterId)
                    return
                }
                is TokenOutcome.Ok -> if (!join(token.credentials, encounterId, manual = false)) return
            }
        }
        publishOnly(instrument)
    }

    /** Con la sala abierta: publica [instrument] y, si había otro, primero lo despublica. */
    private fun publishOnly(instrument: Instrument) {
        val current = status.instrument
        if (current == instrument) {
            log("[TELE] El ${instrument.label} ya se está publicando")
            return
        }
        if (current != null) {
            log("[TELE] Se despublica el ${current.label} para publicar el ${instrument.label}")
            backend.unpublish(current)
            status = status.copy(instrument = null)
        }
        val blocked = backend.blocked(instrument)
        val reason = blocked ?: backend.publish(instrument)
        if (reason != null) {
            log("[TELE] No se pudo publicar el ${instrument.label}: $reason")
            backend.disconnect()
            status = status.copy(room = RoomState.ERROR, instrument = null, message = reason)
            return
        }
        status = status.copy(instrument = instrument, message = "")
        log("[TELE] Publicando el ${instrument.label}")
    }

    private fun join(credentials: TeleCredentials, encounterId: String?, manual: Boolean): Boolean {
        status = TeleStatus(room = RoomState.CONNECTING, encounterId = encounterId, manual = manual)
        val reason = backend.connect(credentials)
        if (reason != null) {
            fail(reason, encounterId = encounterId, manual = manual)
            return false
        }
        status = status.copy(room = RoomState.CONNECTED)
        log("[TELE] En la sala ${credentials.room.ifEmpty { "(prueba manual)" }}")
        return true
    }

    private fun stopOrder(order: TeleOrder) {
        val current = status
        if (current.encounterId != null && current.encounterId != order.encounterId) {
            log("[TELE] Llegó ${describe(order)} de otro encuentro: se ignora")
            return
        }
        if (current.instrument != order.instrument) {
            // Sin pistas no se queda en la sala, aunque el apagado sea de otro instrumento.
            if (current.room == RoomState.CONNECTED && current.instrument == null) closeAll("sin instrumentos")
            return
        }
        closeAll("se apagó el ${order.instrument.label}")
    }

    /** Despublica lo que haya y sale de la sala. */
    private fun closeAll(reason: String) {
        status.instrument?.let(backend::unpublish)
        backend.disconnect()
        status = TeleStatus()
        log("[TELE] Sala cerrada: $reason")
    }

    private fun fail(message: String, encounterId: String? = null, manual: Boolean = false) {
        log("[TELE] $message")
        status = TeleStatus(room = RoomState.ERROR, encounterId = encounterId, manual = manual, message = message)
    }

    private fun describe(order: TeleOrder) =
        "${if (order.start) "encender" else "apagar"} el ${order.instrument.label}"

    /** Ningún error del streamer cierra la app: queda en el log y en el estado. */
    private fun run(task: String, block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (error: Throwable) {
                log("[TELE] Falló $task: $error")
                try {
                    status.instrument?.let(backend::unpublish)
                } catch (_: Throwable) {
                }
                try {
                    backend.disconnect()
                } catch (_: Throwable) {
                }
                status = TeleStatus(room = RoomState.ERROR, message = "Falló $task: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }
}
