package com.th4rv4.zukinav.ui

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import com.th4rv4.zukinav.thermal.ThermalSession
import kotlin.math.min

/** Shared scale for the thermal views: 20 to 130 °C, stretched if a reading goes past it. */
private object HeatScale {
    const val MIN = 20
    const val MAX = 130
    val stops = floatArrayOf(0f, 0.38f, 0.7f, 0.82f, 1f)
    val colors get() = intArrayOf(EngineHeatView.COLD, EngineHeatView.NORMAL, EngineHeatView.NORMAL, EngineHeatView.WARM, EngineHeatView.HOT)
}

/**
 * Big engine temperature gauge for the Thermals tab: a 270° arc in the
 * cold-to-hot colours with a glowing marker at the reading, and the number in
 * the middle in [numberColor].
 */
class ThermalGaugeView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val stroke = 12 * d
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dotGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(8 * d, BlurMaskFilter.Blur.NORMAL) }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = DashFonts.numbers
    }
    private val unit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.2f
    }
    private val oval = RectF()
    private var tempC: Int? = null
    var numberColor = Color.WHITE

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun set(c: Int?, color: Int) {
        tempC = c
        numberColor = color
        contentDescription = c?.let { "Engine $it degrees Celsius" } ?: "Engine temperature: bike not connected"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height * 1.18f)
        val r = size / 2 - stroke
        val cx = width / 2f
        val cy = r + stroke
        oval.set(cx - r, cy - r, cx + r, cy + r)
        // Gradient around the arc: SweepGradient would start at 3 o'clock, so a
        // left-to-right linear one reads the same on a gauge that opens at the bottom.
        arc.shader = LinearGradient(cx - r, 0f, cx + r, 0f, HeatScale.colors, HeatScale.stops, Shader.TileMode.CLAMP)
        arc.alpha = if (tempC == null) 60 else 235
        canvas.drawArc(oval, 135f, 270f, false, arc)

        val t = tempC
        if (t != null) {
            val top = maxOf(HeatScale.MAX, t + 5)
            val frac = ((t - HeatScale.MIN).toFloat() / (top - HeatScale.MIN)).coerceIn(0f, 1f)
            val angle = Math.toRadians((135 + 270 * frac).toDouble())
            val x = cx + r * Math.cos(angle).toFloat()
            val y = cy + r * Math.sin(angle).toFloat()
            if (!Palette.isLight) {
                dotGlow.color = Color.WHITE
                canvas.drawCircle(x, y, stroke * 0.75f, dotGlow)
            }
            dot.color = if (Palette.isLight) Palette.TEXT else Color.WHITE
            canvas.drawCircle(x, y, stroke * 0.55f, dot)
        }
        number.color = if (t == null) Palette.MUTED else numberColor
        number.textSize = r * 0.62f
        canvas.drawText(t?.toString() ?: "--", cx, cy + number.textSize * 0.32f, number)
        unit.color = Palette.MUTED
        unit.textSize = 11 * d
        canvas.drawText("°C", cx, cy + number.textSize * 0.32f + 18 * d, unit)
    }
}

/**
 * Temp Monitor: engine temperature over the last 30 minutes as a line, with
 * the rider's warm-up and heat limits as dashed lines.
 */
class TempGraphView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * d
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val limit = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
        pathEffect = DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.RIGHT
        textSize = 10 * d
    }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 12 * d
    }
    private var samples: List<ThermalSession.Sample> = emptyList()
    private var warmC = 0
    private var hotC = 0

    fun set(s: List<ThermalSession.Sample>, warm: Int, hot: Int) {
        samples = s
        warmC = warm
        hotC = hot
        contentDescription = if (s.isEmpty()) "No engine readings yet" else
            "Engine temperature over the last ${((s.last().at - s.first().at) / 60_000) + 1} minutes"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (samples.size < 2) {
            empty.color = Palette.MUTED
            canvas.drawText("Readings appear here while you ride", w / 2, h / 2, empty)
            return
        }
        val temps = samples.map { it.tempC }
        var lo = minOf(temps.min(), if (warmC > 0) warmC else Int.MAX_VALUE) - 5
        var hi = maxOf(temps.max(), hotC) + 5
        if (hi - lo < 20) { hi += 10; lo -= 10 }
        val t0 = samples.first().at
        val span = (samples.last().at - t0).coerceAtLeast(1).toFloat()
        val pad = 4 * d
        fun y(c: Int) = pad + (h - 2 * pad) * (1 - (c - lo).toFloat() / (hi - lo))
        fun x(at: Long) = (at - t0) / span * w

        fun limitLine(c: Int, color: Int, text: String) {
            if (c <= 0) return
            limit.color = color
            val yy = y(c)
            canvas.drawLine(0f, yy, w, yy, limit)
            label.color = color
            canvas.drawText(text, w - 2 * d, yy - 4 * d, label)
        }
        limitLine(hotC, EngineHeatView.HOT, "Hot $hotC")
        limitLine(warmC, EngineHeatView.COLD, "Warm-Up $warmC")

        val path = Path()
        samples.forEachIndexed { i, s ->
            if (i == 0) path.moveTo(x(s.at), y(s.tempC)) else path.lineTo(x(s.at), y(s.tempC))
        }
        val area = Path(path).apply {
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        fill.shader = LinearGradient(0f, 0f, 0f, h, Accents.withAlpha(Palette.ACCENT_MID, 0x40), Accents.withAlpha(Palette.ACCENT_MID, 0), Shader.TileMode.CLAMP)
        canvas.drawPath(area, fill)
        line.color = Palette.ACCENT_MID
        canvas.drawPath(path, line)
    }
}

/** One bar per recent trip: its highest engine temperature. Tapping a bar calls [onPick] with its index. */
class TripHeatBarsView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = DashFonts.numbers
        textSize = 11 * d
    }
    private val rect = RectF()
    private var values: List<Int> = emptyList()
    private var colorOf: (Int) -> Int = { Palette.ACCENT_MID }
    var onPick: ((Int) -> Unit)? = null

    fun set(v: List<Int>, color: (Int) -> Int) {
        values = v
        colorOf = color
        contentDescription = "Highest engine temperature of recent trips: " + v.joinToString { "$it degrees" }
        invalidate()
    }

    private fun barWidth(): Float {
        val gap = 8 * d
        return (width - gap * (values.size - 1)) / values.size.coerceAtLeast(1)
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val gap = 8 * d
        val w = barWidth()
        val top = 16 * d
        val lo = (values.min() - 15).coerceAtLeast(0)
        val hi = values.max()
        values.forEachIndexed { i, v ->
            val frac = ((v - lo).toFloat() / (hi - lo).coerceAtLeast(1)).coerceIn(0.15f, 1f)
            val left = i * (w + gap)
            val barTop = top + (height - top) * (1 - frac)
            rect.set(left, barTop, left + w, height.toFloat())
            bar.shader = LinearGradient(0f, barTop, 0f, height.toFloat(), colorOf(v), Palette.ACCENT, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(rect, 5 * d, 5 * d, bar)
            label.color = Palette.MUTED
            canvas.drawText(v.toString(), left + w / 2, barTop - 4 * d, label)
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (values.isEmpty() || onPick == null) return false
        if (e.action == MotionEvent.ACTION_UP) {
            val gap = 8 * d
            val i = (e.x / (barWidth() + gap)).toInt().coerceIn(0, values.size - 1)
            performClick()
            onPick?.invoke(i)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
