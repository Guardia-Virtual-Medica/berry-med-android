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
    /** La sala del token, como `encounter-{id}`; null si no se pudo leer. */
    val roomName: String? = null,
    /** El instrumento que se está publicando; uno solo a la vez, como en el kiosco. */
    val instrument: Instrument? = null,
    /** Si la sala la abrió la prueba manual de Testing (servidor y token a mano). */
    val manual: Boolean = false,
    /** Cuándo se vio el token en el kiosco, en hora de la tablet; 0 si no hay. */
    val tokenSeenAtMillis: Long = 0L,
    /** Cuándo vence el token para entrar, del JWT; null si no se pudo leer. */
    val tokenExpiresAtMillis: Long? = null,
    /** El último error, o vacío. */
    val message: String = "",
)

/**
 * Lo que el streamer le pide al mundo: LiveKit y los equipos. Todo bloquea y
 * se llama desde el hilo del streamer. Así la máquina de estados se prueba
 * sola, con un falso.
 */
interface TeleBackend {
    /** null si [instrument] se puede usar ahora; si no, el motivo (Panel Local abierto, sin permiso). */
    fun blocked(instrument: Instrument): String?

    /** Entra a la sala. null si entró; si no, el motivo (y no queda nada abierto). */
    fun connect(credentials: TeleCredentials): String?

    /** Enciende el instrumento y publica su pista. null si quedó publicando; si no, el motivo (y el instrumento queda suelto). */
    fun publish(instrument: Instrument): String?

    /** Despublica y suelta el instrumento (y apaga el puerto si lo encendió el streamer). */
    fun unpublish(instrument: Instrument)

    /** Sale de la sala y suelta todo. */
    fun disconnect()
}

/**
 * Dueño de la sala de LiveKit de la teleconsulta, como la app del proveedor:
 * el kiosco corre dentro de Berry Med y, al crear el encuentro, navega a una
 * dirección con `serverUrl` y `token`. Apenas aparece el token se entra a la
 * sala, sin publicar nada, y se queda: el token vence a los 15 minutos para
 * entrar, pero LiveKit lo renueva mientras está conectado, y los instrumentos
 * se encienden más tarde. `start_oth`/`start_steth` publican y `stop_*`
 * despublican, uno a la vez. Se sale con un token nuevo (otro paciente),
 * cuando el kiosco vuelve al inicio o a las [MAX_ROOM_MILLIS] en la sala.
 *
 * Todo pasa en un solo hilo ([executor]), en el orden en que llegan las
 * cosas. Los errores van al log `[TELE]` y a [status]; nunca el token.
 */
class TeleStreamer(
    private val backend: TeleBackend,
    private val log: (String) -> Unit,
    private val executor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "berry-tele").apply { isDaemon = true }
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile var status: TeleStatus = TeleStatus()
        private set

    /** Encendiendo un instrumento: todavía no figura en [status], pero el puerto ya es de la teleconsulta. */
    @Volatile private var publishing = false

    /** Si la teleconsulta tiene un instrumento en uso o lo está encendiendo. */
    val usingInstrument: Boolean get() = publishing || status.instrument != null

    // Sólo desde el hilo del streamer.
    private var credentials: TeleCredentials? = null
    private var joinedAtMillis = 0L
    private var retried = false

    /** El kiosco cargó una dirección con `serverUrl` y `token`. */
    fun onKioskToken(found: TeleCredentials) {
        run("token del kiosco") {
            if (found == credentials) {
                if (status.room == RoomState.CONNECTING || status.room == RoomState.CONNECTED) return@run
                // La misma dirección se avisa más de una vez por carga: un error reciente no se reintenta enseguida.
                if (status.room == RoomState.ERROR && clock() - status.tokenSeenAtMillis < SAME_TOKEN_PAUSE_MILLIS) return@run
            }
            if (status.manual) {
                log("[TELE] El kiosco abrió un encuentro con la prueba de Testing en la sala: se corta la prueba")
            }
            if (status.room != RoomState.DISCONNECTED || status.instrument != null) closeAll("llegó un token nuevo")
            join(found, manual = false)
        }
    }

    /** El kiosco volvió al inicio, al login o al setup: el encuentro terminó. */
    fun onKioskHome() {
        run("kiosco al inicio") {
            if (status.manual || credentials == null) return@run
            closeAll("el kiosco volvió al inicio")
            credentials = null
        }
    }

    /** Una orden de Pusher. Sólo publica o despublica: la sala la maneja el token del kiosco. */
    fun order(order: TeleOrder) {
        run("orden ${order.instrument.label}") {
            if (status.manual) {
                log("[TELE] Llegó ${describe(order)} con la prueba de Testing en la sala: se ignora")
                return@run
            }
            if (status.room != RoomState.CONNECTED) {
                log("[TELE] Llegó ${describe(order)} sin sala (el kiosco no mostró un token): se ignora")
                return@run
            }
            val encounter = KioskUrls.encounterOf(status.roomName)
            if (encounter != null && encounter != order.encounterId) {
                log("[TELE] Llegó ${describe(order)} de otro encuentro: se ignora")
                return@run
            }
            if (order.start) {
                publishOnly(order.instrument)
            } else if (status.instrument == order.instrument) {
                unpublishCurrent("se apagó el ${order.instrument.label}")
            }
        }
    }

    /**
     * Despublica lo que haya y sigue en la sala: el `stop-monitoring` (llega
     * también al completar los signos, antes de la videollamada) y el Panel
     * Local que se abre. La prueba de Testing sólo con [includeManual].
     */
    fun unpublishAll(reason: String, includeManual: Boolean = false) {
        run("despublicar") {
            if (status.manual && !includeManual) return@run
            if (status.instrument != null) unpublishCurrent(reason)
        }
    }

    /** La prueba manual de Testing: un servidor y un token hechos a mano, como si los hubiera mostrado el kiosco. */
    fun startManual(serverUrl: String, token: String, instrument: Instrument) {
        run("prueba manual") {
            val server = serverUrl.trim()
            val jwt = token.trim()
            if (server.isEmpty() || jwt.isEmpty()) {
                status = status.copy(message = "Faltan el servidor o el token")
                return@run
            }
            val wanted = TeleCredentials(KioskUrls.serverFor(server), jwt)
            if (wanted != credentials || status.room != RoomState.CONNECTED) {
                if (status.room != RoomState.DISCONNECTED || status.instrument != null) closeAll("se abrió la prueba manual de Testing")
                if (!join(wanted, manual = true)) return@run
            }
            publishOnly(instrument)
        }
    }

    /** Cortar la prueba manual: lo que esté publicando y la sala. */
    fun stopManual() {
        run("cortar la prueba") {
            if (!status.manual) return@run
            closeAll("se cortó la prueba de Testing")
            credentials = null
        }
    }

    /**
     * LiveKit avisa que la sala se cayó y no pudo volver. Se suelta todo y,
     * una sola vez y si el token todavía sirve para entrar, se vuelve a entrar.
     */
    fun roomLost(reason: String) {
        run("sala perdida") {
            if (status.room != RoomState.CONNECTED) return@run
            log("[TELE] Se cortó la sala: $reason")
            status.instrument?.let(backend::unpublish)
            backend.disconnect()
            val again = credentials
            val fresh = clock() - status.tokenSeenAtMillis < TOKEN_ENTRY_MILLIS
            status = status.copy(room = RoomState.ERROR, instrument = null, message = "Se cortó la sala: $reason")
            if (again != null && fresh && !retried && !status.manual) {
                retried = true
                log("[TELE] Se vuelve a entrar con el mismo token")
                join(again, manual = false, keepRetry = true)
            }
        }
    }

    /** Se llama seguido (una vez por segundo): el tope de tiempo en la sala. */
    fun tick() {
        run("tope de la sala") {
            if (status.room != RoomState.CONNECTED || joinedAtMillis == 0L) return@run
            if (clock() - joinedAtMillis < MAX_ROOM_MILLIS) return@run
            closeAll("pasaron ${MAX_ROOM_MILLIS / 3_600_000L} h en la sala")
            credentials = null
        }
    }

    private fun join(found: TeleCredentials, manual: Boolean, keepRetry: Boolean = false): Boolean {
        val info = KioskUrls.tokenInfo(found.token)
        val seenAt = if (keepRetry) status.tokenSeenAtMillis else clock()
        credentials = found
        if (!keepRetry) retried = false
        status = TeleStatus(
            room = RoomState.CONNECTING,
            roomName = info.room,
            manual = manual,
            tokenSeenAtMillis = seenAt,
            tokenExpiresAtMillis = info.expiresAtMillis,
        )
        log("[TELE] Token visto para la sala ${info.room ?: "(sin nombre)"} en ${found.serverUrl}: se entra")
        val reason = backend.connect(found)
        if (reason != null) {
            log("[TELE] $reason")
            status = status.copy(room = RoomState.ERROR, message = reason)
            return false
        }
        joinedAtMillis = clock()
        status = status.copy(room = RoomState.CONNECTED, message = "")
        log("[TELE] En la sala ${info.room ?: "(sin nombre)"}, sin publicar")
        return true
    }

    /** Con la sala abierta: publica [instrument] y, si había otro, primero lo despublica. */
    private fun publishOnly(instrument: Instrument) {
        publishing = true
        try {
            replaceWith(instrument)
        } finally {
            publishing = false
        }
    }

    private fun replaceWith(instrument: Instrument) {
        val current = status.instrument
        if (current == instrument) {
            log("[TELE] El ${instrument.label} ya se está publicando")
            return
        }
        if (current != null) unpublishCurrent("se enciende el ${instrument.label}")
        val reason = backend.blocked(instrument) ?: backend.publish(instrument)
        if (reason != null) {
            // Sigue en la sala: la próxima orden del kiosco vuelve a probar.
            log("[TELE] No se publica el ${instrument.label}: $reason")
            status = status.copy(instrument = null, message = reason)
            return
        }
        status = status.copy(instrument = instrument, message = "")
        log("[TELE] Publicando el ${instrument.label}")
    }

    private fun unpublishCurrent(reason: String) {
        val current = status.instrument ?: return
        backend.unpublish(current)
        status = status.copy(instrument = null)
        log("[TELE] Se despublica el ${current.label}: $reason")
    }

    /** Despublica lo que haya y sale de la sala. */
    private fun closeAll(reason: String) {
        status.instrument?.let(backend::unpublish)
        backend.disconnect()
        joinedAtMillis = 0L
        status = TeleStatus()
        log("[TELE] Sala cerrada: $reason")
    }

    private fun describe(order: TeleOrder) =
        "${if (order.start) "encender" else "apagar"} el ${order.instrument.label}"

    /** Ningún error del streamer cierra la app: queda en el log y en el estado. */
    private fun run(task: String, block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (error: Throwable) {
                log("[TELE] Falló $task: ${error.javaClass.simpleName}")
                try {
                    status.instrument?.let(backend::unpublish)
                } catch (_: Throwable) {
                }
                try {
                    backend.disconnect()
                } catch (_: Throwable) {
                }
                status = status.copy(room = RoomState.ERROR, instrument = null, message = "Falló $task")
            }
        }
    }

    companion object {
        /** Lo que dura el token para entrar a la sala. */
        const val TOKEN_ENTRY_MILLIS = 15L * 60L * 1_000L

        /** Una dirección con el mismo token que falló hace menos que esto no vuelve a intentar. */
        const val SAME_TOKEN_PAUSE_MILLIS = 10_000L

        /** Tope de seguridad por si el kiosco nunca vuelve al inicio. */
        const val MAX_ROOM_MILLIS = 3L * 60L * 60L * 1_000L
    }
}
