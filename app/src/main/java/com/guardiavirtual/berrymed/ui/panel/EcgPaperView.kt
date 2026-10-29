// SPDX-License-Identifier: GPL-3.0-or-later
package com.guardiavirtual.berrymed.ui.panel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.guardiavirtual.berrymed.R
import com.guardiavirtual.berrymed.protocol.LiveFeed
import com.guardiavirtual.berrymed.protocol.Pm6750Protocol
import kotlin.math.ceil

/**
 * El ECG con barrido, como un monitor de cabecera, sobre la grilla oscura del
 * diseño. La grilla es la del papel real: 1 mm las líneas finas y 5 mm las
 * gruesas, medidas con la densidad de la pantalla, así que 25 mm/s son 25 mm
 * de verdad. La escala vertical es aproximada: la plaqueta manda bytes de 0 a
 * 255 con la línea de base en 128 y no documenta cuántos milivoltios son, así
 * que se parte de [UNITS_PER_MV] y la ganancia (5, 10 o 20 mm/mV) la ajusta.
 *
 * Lee siempre las siete derivaciones aunque muestre menos: así, con el ECG
 * congelado, pasar de 7 a II muestra la II que ya estaba.
 *
 * En modo [compact] es la tira de abajo del dispositivo abierto: la II sola,
 * una grilla fija de 20 × 14 dp y la amplitud acotada al 38 % del alto.
 */
class EcgPaperView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val allLeads = Pm6750Protocol.LEADS
    private var traces: List<SweepTrace> = emptyList()
    private var lines = FloatArray(0)
    private var feed: LiveFeed? = null

    /**
     * La grilla, dibujada una sola vez por tamaño. Son cientos de líneas:
     * repetirlas en cada cuadro, a 60 por segundo, gasta la tablet en algo
     * que no cambia.
     */
    private var paper: Bitmap? = null
    private val density = resources.displayMetrics.density
    private val pxPerMm = resources.displayMetrics.xdpi / 25.4f

    var compact = false
        set(value) {
            field = value
            leads = if (value) EcgLeads.II else EcgLeads.SEVEN
            dropPaper()
        }

    /** Cambiar las derivaciones limpia y reinicia el barrido en x = 0, salvo congelado: ahí sólo reacomoda. */
    var leads = EcgLeads.SEVEN
        set(value) {
            if (field == value) return
            field = value
            if (!frozen) traces.forEach { it.wipe() }
            invalidate()
        }

    /** Otra velocidad cambia cuántas muestras entran: el barrido sigue desde x = 0. */
    var mmPerSecond = 25f
        set(value) {
            if (field == value) return
            field = value
            rebuild()
        }

    /** La ganancia en mm/mV; se aplica en el acto sobre lo que ya está dibujado. */
    var mmPerMv = MM_PER_MV
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Congelado deja de dibujar, pero el [LiveFeed] sigue llenándose y la FC
     * se sigue calculando en la plaqueta. Al reanudar el barrido arranca de
     * nuevo en x = 0, sin lo que llegó mientras tanto.
     */
    var frozen = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) {
                val source = feed
                for ((index, trace) in traces.withIndex()) {
                    trace.wipe(source?.ecg?.getValue(allLeads[index])?.count())
                }
            }
            invalidate()
        }

    private val background = Paint().apply { color = ContextCompat.getColor(context, R.color.ecgBg) }
    private val minorGrid = Paint().apply {
        color = ContextCompat.getColor(context, R.color.gridMinor)
        strokeWidth = density
    }
    private val majorGrid = Paint().apply {
        color = ContextCompat.getColor(context, R.color.gridMajor)
        strokeWidth = density
    }
    private val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ecg)
        strokeWidth = 1.6f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.muted)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
        typeface = if (isInEditMode) Typeface.DEFAULT_BOLD else ResourcesCompat.getFont(context, R.font.barlow_semi_condensed_semibold)
    }

    fun attach(feed: LiveFeed) {
        this.feed = feed
        restart()
    }

    /** Al desbloquear el panel: el servicio vació las ondas y la pantalla empieza en blanco. */
    fun restart() {
        traces.forEach(SweepTrace::reset)
        invalidate()
    }

    /** Trae lo nuevo de cada derivación y redibuja si llegó algo. Congelado no lee. */
    fun tick() {
        if (frozen) return
        val source = feed ?: return
        var fresh = 0
        for ((index, lead) in allLeads.withIndex()) {
            val trace = traces.getOrNull(index) ?: continue
            fresh += trace.feed(source.ecg.getValue(lead))
        }
        if (fresh > 0) invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Otro tamaño, otra grilla: se vuelve a dibujar en el próximo cuadro.
        dropPaper()
        rebuild()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dropPaper()
    }

    private fun dropPaper() {
        paper?.recycle()
        paper = null
        invalidate()
    }

    private fun paper(): Bitmap? {
        paper?.let { return it }
        if (width <= 0 || height <= 0) return null
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            val w = width.toFloat()
            val h = height.toFloat()
            if (compact) {
                // La tira va sobre la tarjeta: sin fondo propio y con la grilla gruesa sola.
                drawGrid(canvas, w, h, COMPACT_GRID_X_DP * density, COMPACT_GRID_Y_DP * density, majorGrid)
            } else {
                canvas.drawRect(0f, 0f, w, h, background)
                drawGrid(canvas, w, h, pxPerMm, pxPerMm, minorGrid)
                drawGrid(canvas, w, h, pxPerMm * 5f, pxPerMm * 5f, majorGrid)
            }
            paper = bitmap
        }
    }

    private fun drawGrid(canvas: Canvas, w: Float, h: Float, stepX: Float, stepY: Float, paint: Paint) {
        var i = 0
        while (i * stepX <= w) {
            canvas.drawLine(i * stepX, 0f, i * stepX, h, paint)
            i++
        }
        i = 0
        while (i * stepY <= h) {
            canvas.drawLine(0f, i * stepY, w, i * stepY, paint)
            i++
        }
    }

    private fun rebuild() {
        val n = SweepTrace.samplesPerScreen(width.toFloat(), pxPerMm, mmPerSecond, Pm6750Protocol.ECG_SAMPLE_RATE_HZ.toFloat())
        traces = if (traces.isEmpty()) allLeads.map { SweepTrace(n) } else traces.map { it.resized(n) }
        lines = FloatArray((n - 1) * 4)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val grid = paper()
        if (grid != null) canvas.drawBitmap(grid, 0f, 0f, null)
        if (traces.isEmpty()) return
        val shown = leads.leads
        val laneHeight = h / shown.size
        // Del papel y no del ancho: así 25 mm/s son 25 mm reales aunque la última muestra no entre entera.
        val pxPerSample = pxPerMm * mmPerSecond / Pm6750Protocol.ECG_SAMPLE_RATE_HZ.toFloat()
        val gap = ceil(ERASE_DP * density / pxPerSample).toInt()
        val pxPerUnit = if (compact) {
            laneHeight * COMPACT_MV_FRACTION / UNITS_PER_MV
        } else {
            pxPerMm * mmPerMv / UNITS_PER_MV
        }
        val limit = laneHeight * COMPACT_LIMIT
        for ((row, lead) in shown.withIndex()) {
            val trace = traces.getOrNull(allLeads.indexOf(lead)) ?: continue
            val top = row * laneHeight
            val baseline = top + laneHeight * BASELINE_FRACTION
            canvas.save()
            canvas.clipRect(0f, top, w, top + laneHeight)
            val count = trace.segments(
                lines,
                xOf = { i -> i * pxPerSample },
                yOf = if (compact) {
                    { v -> baseline - ((v - BASELINE_UNITS) * pxPerUnit).coerceIn(-limit, limit) }
                } else {
                    { v -> baseline - (v - BASELINE_UNITS) * pxPerUnit }
                },
                gap = gap,
            )
            if (count > 0) canvas.drawLines(lines, 0, count, tracePaint)
            canvas.restore()
            if (!compact) {
                canvas.drawText(lead, LABEL_LEFT_DP * density, top + LABEL_TOP_DP * density - labelPaint.ascent(), labelPaint)
            }
        }
    }

    companion object {
        /** Estándar del papel de ECG: 10 mm por milivoltio. Es el 10 del segmentado de ganancia. */
        const val MM_PER_MV = 10f

        /** Cuántas unidades de la plaqueta son un milivoltio. Es una estimación; la ganancia la corrige a ojo. */
        const val UNITS_PER_MV = 40f
        const val BASELINE_UNITS = 128f

        /** La línea de base al 58 % de cada fila: el QRS sube más de lo que baja. */
        const val BASELINE_FRACTION = 0.58f

        /** La franja que se borra delante del cursor. */
        const val ERASE_DP = 18f

        const val LABEL_LEFT_DP = 14f
        const val LABEL_TOP_DP = 6f

        const val COMPACT_GRID_X_DP = 20f
        const val COMPACT_GRID_Y_DP = 14f

        /** En la tira, un milivoltio ocupa el 30 % del alto y nada pasa del 38 %. */
        const val COMPACT_MV_FRACTION = 0.30f
        const val COMPACT_LIMIT = 0.38f
    }
}
