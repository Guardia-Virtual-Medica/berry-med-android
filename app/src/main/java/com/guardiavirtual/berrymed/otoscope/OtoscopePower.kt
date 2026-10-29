// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.otoscope

/**
 * Encender y apagar el otoscopio: apagado → encendiendo puerto → esperando
 * cámara → encendido → apagando. Encender el puerto del YKUSH3 es enchufar el
 * otoscopio para Android y prende su luz; apagarlo lo desenchufa.
 *
 * No hace nada por su cuenta: cada paso devuelve qué hay que hacer y quien lo
 * usa lo hace (el YKUSH3 en un hilo de trabajo, la cámara en la pantalla). Sin
 * Android y con los plazos como parámetros, para probarla sola.
 *
 * El camino del video lo decide lo que aparece primero: una cámara externa
 * para Camera2, o el dispositivo USB con permiso para UVC. Pero en una tablet
 * que lista el otoscopio como cámara, el USB aparece siempre un momento antes
 * que la cámara (Android la arma después de enchufarse el dispositivo); si se
 * eligiera UVC ahí, se le disputaría el otoscopio a la cámara del sistema.
 * Por eso, visto el USB, se espera [camera2GraceNanos] a que aparezca la cámara.
 */
class OtoscopePower(
    private val waitNanos: Long,
    private val camera2GraceNanos: Long,
) {
    enum class State { OFF, POWERING_PORT, WAITING_CAMERA, ON, POWERING_OFF }

    enum class Path { CAMERA2, UVC }

    enum class Action { PORT_ON, CLOSE_CAMERA, PORT_OFF }

    sealed interface Poll {
        /** Todavía no apareció nada: volver a mirar. */
        data object Waiting : Poll

        /** Se apagó mientras esperaba: no hay que hacer nada más. */
        data object Cancelled : Poll

        /** Apareció: abrir la imagen por [path]. */
        data class Open(val path: Path) : Poll

        /** Pasó el plazo sin cámara: ya está apagado, falta hacer [actions]. */
        data class TimedOut(val actions: List<Action>) : Poll
    }

    var state: State = State.OFF
        private set

    /** Por dónde se abrió la imagen, mientras está encendido. */
    var path: Path? = null
        private set

    private var usesHub = false
    private var deadline = 0L
    private var usbSeenAt: Long? = null

    /**
     * Encender. Devuelve qué hacer, o null si no estaba apagado (ya está
     * encendiendo, encendido o apagándose). Sin YKUSH3 se saltea el hub y se
     * espera la cámara directo.
     */
    @Synchronized
    fun switchOn(hubPresent: Boolean, now: Long): List<Action>? {
        if (state != State.OFF) return null
        usesHub = hubPresent
        path = null
        usbSeenAt = null
        if (hubPresent) {
            state = State.POWERING_PORT
            return listOf(Action.PORT_ON)
        }
        startWaiting(now)
        return emptyList()
    }

    /** El hub ya encendió el puerto. Devuelve false si mientras tanto se apagó. */
    @Synchronized
    fun portReady(now: Long): Boolean {
        if (state != State.POWERING_PORT) return false
        startWaiting(now)
        return true
    }

    private fun startWaiting(now: Long) {
        state = State.WAITING_CAMERA
        deadline = now + waitNanos
    }

    /** Mira si ya apareció el otoscopio: [cameraSeen] por Camera2, [usbReady] por USB con permiso. */
    @Synchronized
    fun poll(now: Long, cameraSeen: Boolean, usbReady: Boolean): Poll {
        if (state != State.WAITING_CAMERA) return Poll.Cancelled
        if (cameraSeen) return open(Path.CAMERA2)
        if (usbReady) {
            val seen = usbSeenAt ?: now.also { usbSeenAt = it }
            if (now - seen >= camera2GraceNanos || now >= deadline) return open(Path.UVC)
        } else {
            usbSeenAt = null
        }
        if (now < deadline) return Poll.Waiting
        val actions = if (usesHub) listOf(Action.PORT_OFF) else emptyList()
        // El puerto se apaga ahí mismo, en el hilo que esperaba: no hace falta pasar por apagando.
        state = State.OFF
        return Poll.TimedOut(actions)
    }

    private fun open(chosen: Path): Poll {
        state = State.ON
        path = chosen
        return Poll.Open(chosen)
    }

    /**
     * Apagar desde cualquier estado, también al cerrar el panel: primero se
     * cierra la cámara y después se apaga el puerto. Si quedó algo por hacer
     * en el hub, el estado queda en apagando hasta [poweredOff].
     */
    @Synchronized
    fun switchOff(): List<Action> {
        val actions = buildList {
            if (state == State.ON) add(Action.CLOSE_CAMERA)
            if (usesHub && state != State.OFF && state != State.POWERING_OFF) add(Action.PORT_OFF)
        }
        if (state != State.POWERING_OFF) {
            state = if (Action.PORT_OFF in actions) State.POWERING_OFF else State.OFF
        }
        path = null
        return actions
    }

    /** El hub ya apagó el puerto. */
    @Synchronized
    fun poweredOff() {
        if (state == State.POWERING_OFF) state = State.OFF
    }
}
