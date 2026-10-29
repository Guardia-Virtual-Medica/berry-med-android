// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.guardiavirtual.berrymed.protocol.LiveFeed

/**
 * Una onda sin escala fija (el fonocardiograma), con barrido y la altura
 * ajustada a lo que hay en pantalla. Cuántas muestras entran se fija a mano.
 * Sirvió también para las ondas de SpO₂ y respiración, cuya escala la
 * plaqueta no documenta; el diseño nuevo ya no las muestra.
 */
class WaveformView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var trace = SweepTrace(DEFAULT_SAMPLES)
    private var lines = FloatArray((DEFAULT_SAMPLES - 1) * 4)
    private var channel: LiveFeed.Channel? = null

    var samplesPerScreen: Int = DEFAULT_SAMPLES
        set(value) {
            field = maxOf(2, value)
            trace = SweepTrace(field)
            lines = FloatArray((field - 1) * 4)
            invalidate()
        }

    var traceColor: Int = Color.rgb(61, 220, 151)
        set(value) {
            field = value
            tracePaint.color = value
        }

    /** El grosor del trazo, en píxeles. */
    var traceWidth: Float = 2f
        set(value) {
            field = value
            tracePaint.strokeWidth = value
        }

    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = traceColor
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
    }

    fun attach(channel: LiveFeed.Channel) {
        this.channel = channel
        restart()
    }

    /** Al desbloquear el panel: el servicio vació el canal y la pantalla empieza en blanco. */
    fun restart() {
        trace.reset()
        invalidate()
    }

    fun tick() {
        val source = channel ?: return
        if (trace.feed(source) > 0) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val range = trace.range() ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val margin = h * 0.12f
        val span = range.second - range.first
        val pxPerSample = w / trace.samplesPerScreen
        // Una onda plana (sin dedo, sin respiración) va al medio y no pegada abajo.
        val yOf: (Float) -> Float = if (span <= 0f) {
            { h / 2f }
        } else {
            { v -> h - margin - (v - range.first) / span * (h - 2 * margin) }
        }
        val count = trace.segments(
            lines,
            xOf = { i -> i * pxPerSample },
            yOf = yOf,
            gap = GAP_SAMPLES,
        )
        if (count > 0) canvas.drawLines(lines, 0, count, tracePaint)
    }

    companion object {
        const val DEFAULT_SAMPLES = 250
        const val GAP_SAMPLES = 12
    }
}
