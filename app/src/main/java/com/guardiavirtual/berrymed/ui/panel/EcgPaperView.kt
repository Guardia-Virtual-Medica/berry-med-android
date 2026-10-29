// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.guardiavirtual.berrymed.protocol.LiveFeed
import com.guardiavirtual.berrymed.protocol.Pm6750Protocol

/**
 * Las 7 derivaciones de ECG sobre papel milimetrado, con barrido. La
 * cuadrícula es la del papel real: 1 mm las líneas finas y 5 mm las gruesas,
 * medidas con la densidad de la pantalla. La velocidad es de papel (25 o 12,5
 * mm/s). La escala vertical es aproximada: la plaqueta manda bytes de 0 a 255
 * con la línea de base en 128 y no documenta cuántos milivoltios son, así que
 * se parte de [UNITS_PER_MV] y la ganancia de pantalla se cambia con un botón.
 */
class EcgPaperView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val leads = Pm6750Protocol.LEADS
    private var traces: List<SweepTrace> = emptyList()
    private var lines = FloatArray(0)
    private var feed: LiveFeed? = null

    /**
     * El papel con la cuadrícula, dibujado una sola vez por tamaño. Son
     * cientos de líneas: repetirlas en cada cuadro, a 60 por segundo, gasta
     * la tablet en algo que no cambia.
     */
    private var paper: Bitmap? = null
    private val pxPerMm = resources.displayMetrics.xdpi / 25.4f

    var mmPerSecond = 25f
        set(value) {
            field = value
            rebuild()
        }

    /** Ganancia de pantalla, x0.5, x1 o x2 sobre la escala base. */
    var displayGain = 1f

    private val paperPaint = Paint().apply { color = Color.rgb(255, 250, 243) }
    private val fineGrid = Paint().apply {
        color = Color.rgb(244, 200, 200)
        strokeWidth = 1f
    }
    private val boldGrid = Paint().apply {
        color = Color.rgb(226, 150, 150)
        strokeWidth = 1.5f
    }
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(20, 40, 40)
        strokeWidth = 2.2f
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(120, 60, 60)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
    }

    fun attach(feed: LiveFeed) {
        this.feed = feed
        restart()
    }

    /** Al desbloquear el panel: el servicio vació las ondas y el papel empieza en blanco. */
    fun restart() {
        traces.forEach(SweepTrace::reset)
        invalidate()
    }

    /** Trae lo nuevo de cada derivación y redibuja si llegó algo. */
    fun tick() {
        val source = feed ?: return
        var fresh = 0
        for ((index, lead) in leads.withIndex()) {
            val trace = traces.getOrNull(index) ?: continue
            fresh += trace.feed(source.ecg.getValue(lead))
        }
        if (fresh > 0) invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Otro tamaño, otra cuadrícula: se vuelve a dibujar en el próximo cuadro.
        paper?.recycle()
        paper = null
        rebuild()
    }

    private fun paper(): Bitmap? {
        paper?.let { return it }
        if (width <= 0 || height <= 0) return null
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paperPaint)
            drawGrid(canvas, width.toFloat(), height.toFloat())
            paper = bitmap
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        paper?.recycle()
        paper = null
    }

    private fun rebuild() {
        val n = SweepTrace.samplesPerScreen(width.toFloat(), pxPerMm, mmPerSecond, Pm6750Protocol.ECG_SAMPLE_RATE_HZ.toFloat())
        traces = leads.map { SweepTrace(n) }
        lines = FloatArray((n - 1) * 4)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val background = paper()
        if (background != null) {
            canvas.drawBitmap(background, 0f, 0f, null)
        } else {
            canvas.drawRect(0f, 0f, w, h, paperPaint)
        }
        if (traces.isEmpty()) return
        val laneHeight = h / leads.size
        // Del papel y no del ancho: así 25 mm/s son 25 mm reales aunque la última muestra no entre entera.
        val pxPerSample = pxPerMm * mmPerSecond / Pm6750Protocol.ECG_SAMPLE_RATE_HZ.toFloat()
        val pxPerUnit = pxPerMm * MM_PER_MV / UNITS_PER_MV * displayGain
        for ((index, trace) in traces.withIndex()) {
            val top = index * laneHeight
            val baseline = top + laneHeight * 0.55f
            canvas.save()
            canvas.clipRect(0f, top, w, top + laneHeight)
            val count = trace.segments(
                lines,
                xOf = { i -> i * pxPerSample },
                yOf = { v -> baseline - (v - BASELINE_UNITS) * pxPerUnit },
                gap = GAP_SAMPLES,
            )
            if (count > 0) canvas.drawLines(lines, 0, count, tracePaint)
            canvas.restore()
            canvas.drawText(leads[index], 6f * pxPerMm / 4f, top + labelPaint.textSize + 4f, labelPaint)
        }
    }

    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        var x = 0f
        var i = 0
        while (x <= w) {
            canvas.drawLine(x, 0f, x, h, if (i % 5 == 0) boldGrid else fineGrid)
            i++
            x = i * pxPerMm
        }
        var y = 0f
        i = 0
        while (y <= h) {
            canvas.drawLine(0f, y, w, y, if (i % 5 == 0) boldGrid else fineGrid)
            i++
            y = i * pxPerMm
        }
    }

    companion object {
        /** Estándar del papel de ECG: 10 mm por milivoltio. */
        const val MM_PER_MV = 10f

        /** Cuántas unidades de la plaqueta son un milivoltio. Es una estimación; la ganancia de pantalla la corrige a ojo. */
        const val UNITS_PER_MV = 40f
        const val BASELINE_UNITS = 128f

        /** El hueco adelante del cursor: un quinto de segundo. */
        const val GAP_SAMPLES = 50
    }
}
