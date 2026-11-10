package com.th4rv4.zukinav.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min
import kotlin.math.roundToInt

/** Fonts for the live dash panel; system fonts, so nothing extra is bundled. */
object DashFonts {
    val numbers: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
}

private fun Context.dpf(v: Float) = v * resources.displayMetrics.density

private fun strokePaint(width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeWidth = width
    strokeCap = Paint.Cap.ROUND
}

private fun withAlpha(color: Int, alpha: Float) =
    Color.argb((Color.alpha(color) * alpha).roundToInt(), Color.red(color), Color.green(color), Color.blue(color))

/**
 * Speedometer: a 270° arc that fills through the three accent colours, with a
 * soft glow, and the speed in large digits. Null speed shows "--".
 */
class SpeedGaugeView(ctx: Context) : View(ctx) {

    private val stroke = ctx.dpf(10f)
    private val track = strokePaint(stroke)
    private val arc = strokePaint(stroke)
    private val glow = strokePaint(stroke).apply { maskFilter = BlurMaskFilter(ctx.dpf(7f), BlurMaskFilter.Blur.NORMAL) }
    private val digits = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = DashFonts.numbers }
    private val unit = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; letterSpacing = 0.2f }
    private val oval = RectF()

    private var shown = 0f
    private var speed: Int? = null
    private var animator: ValueAnimator? = null

    /** Top of the scale, km/h. */
    var max = 160f

    init {
        // Blur needs software drawing on older Android versions.
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    fun setSpeed(kmh: Int?) {
        speed = kmh
        val target = (kmh ?: 0).toFloat()
        animator?.cancel()
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = 450
            interpolator = DecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
        contentDescription = if (kmh == null) "Speed unknown" else "$kmh km/h"
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val size = min(w, h).toFloat()
        val pad = stroke + context.dpf(8f)
        val left = (w - size) / 2 + pad
        val top = (h - size) / 2 + pad
        oval.set(left, top, left + size - 2 * pad, top + size - 2 * pad)
        val shader = LinearGradient(
            oval.left, oval.bottom, oval.right, oval.top,
            intArrayOf(Palette.ACCENT, Palette.ACCENT_MID, Palette.ACCENT_END),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP,
        )
        arc.shader = shader
        glow.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        track.color = Palette.TRACK
        canvas.drawArc(oval, 135f, 270f, false, track)
        val sweep = 270f * (shown / max).coerceIn(0f, 1f)
        if (sweep > 0.5f) {
            glow.alpha = 150
            if (!Palette.isLight) canvas.drawArc(oval, 135f, sweep, false, glow)
            canvas.drawArc(oval, 135f, sweep, false, arc)
        }
        val cx = oval.centerX()
        val cy = oval.centerY()
        digits.color = Palette.TEXT
        digits.textSize = oval.height() * 0.40f
        canvas.drawText(if (speed == null) "--" else shown.roundToInt().toString(), cx, cy + digits.textSize * 0.33f, digits)
        unit.color = Palette.MUTED
        unit.textSize = oval.height() * 0.085f
        canvas.drawText("KM/H", cx, cy + digits.textSize * 0.33f + unit.textSize * 2f, unit)
    }
}

/**
 * The next turn: its arrow in a frosted circle, ringed by the distance to
 * the turn. The ring is full at 500 m or more and empties as the turn gets
 * closer, the same scale as the bar on the dash.
 */
class TurnRingView(ctx: Context) : View(ctx) {

    private val stroke = ctx.dpf(5f)
    private val track = strokePaint(stroke)
    private val ring = strokePaint(stroke)
    private val glow = strokePaint(stroke).apply { maskFilter = BlurMaskFilter(ctx.dpf(4f), BlurMaskFilter.Blur.NORMAL) }
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dpf(1f) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()

    private var art: TurnArt.Art? = null
    private var shown = 0f
    private var animator: ValueAnimator? = null

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    /** [art] null when there is no route. */
    fun set(art: TurnArt.Art?, metersToTurn: Double?) {
        this.art = art
        val target = if (art == null || metersToTurn == null) 0f else (metersToTurn / 500.0).toFloat().coerceIn(0f, 1f)
        animator?.cancel()
        animator = ValueAnimator.ofFloat(shown, target).apply {
            duration = 400
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }
            start()
        }
        alpha = if (art == null) 0.35f else 1f
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val size = min(w, h).toFloat()
        val pad = stroke / 2 + context.dpf(3f)
        val left = (w - size) / 2 + pad
        val top = (h - size) / 2 + pad
        oval.set(left, top, left + size - 2 * pad, top + size - 2 * pad)
        val shader = LinearGradient(
            oval.left, oval.top, oval.right, oval.bottom,
            Palette.ACCENT_MID, Palette.ACCENT, Shader.TileMode.CLAMP,
        )
        ring.shader = shader
        glow.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        track.color = Palette.TRACK
        canvas.drawOval(oval, track)
        val sweep = 360f * shown
        if (sweep > 0.5f) {
            glow.alpha = 150
            if (!Palette.isLight) canvas.drawArc(oval, -90f, sweep, false, glow)
            canvas.drawArc(oval, -90f, sweep, false, ring)
        }
        val cx = oval.centerX()
        val cy = oval.centerY()
        val r = oval.width() / 2 - stroke * 1.5f
        inner.color = Palette.GLASS
        innerEdge.color = Palette.GLASS_BORDER
        canvas.drawCircle(cx, cy, r, inner)
        canvas.drawCircle(cx, cy, r, innerEdge)

        val a = art
        if (a == null) {
            dot.color = Palette.MUTED
            canvas.drawCircle(cx, cy, context.dpf(4f), dot)
            return
        }
        val d = context.getDrawable(a.res)?.mutate() ?: return
        d.setTint(Palette.TEXT)
        val half = (r * 0.62f).roundToInt()
        d.setBounds((cx - half).roundToInt(), (cy - half).roundToInt(), (cx + half).roundToInt(), (cy + half).roundToInt())
        canvas.save()
        canvas.rotate(a.rotation, cx, cy)
        if (a.mirrored) canvas.scale(-1f, 1f, cx, cy)
        d.draw(canvas)
        canvas.restore()
    }
}

/**
 * Fuel (or battery) as glowing cells, one per bar on the dash. Green with
 * plenty left, amber at 2, red and pulsing on the last one.
 */
class FuelCellsView(ctx: Context) : View(ctx) {

    private val gap = ctx.dpf(5f)
    private val cellHeight = ctx.dpf(9f)
    private val radius = ctx.dpf(4.5f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(ctx.dpf(6f), BlurMaskFilter.Blur.NORMAL) }
    private val shine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var filled = 0
    private var total = 6
    private var pulse = 1f
    private var pulser: ValueAnimator? = null

    init {
        setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    }

    /** The colour the cells (and the pump icon) use for this level. */
    val levelColor: Int
        get() = when {
            filled <= 1 -> 0xFFFF5A4E.toInt()
            filled == 2 -> 0xFFFFB547.toInt()
            else -> 0xFF4ADE9A.toInt()
        }

    fun set(filled: Int, total: Int) {
        this.filled = filled.coerceIn(0, total)
        this.total = total
        val low = filled <= 1
        if (low && pulser == null) {
            pulser = ValueAnimator.ofFloat(1f, 0.35f).apply {
                duration = 1_100
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
                start()
            }
        } else if (!low) {
            pulser?.cancel()
            pulser = null
            pulse = 1f
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        pulser?.cancel()
        pulser = null
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (cellHeight + context.dpf(12f)).roundToInt()
        setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val cellW = (w - gap * (total - 1)) / total
        val top = (height - cellHeight) / 2
        val color = levelColor
        for (i in 0 until total) {
            val left = i * (cellW + gap)
            rect.set(left, top, left + cellW, top + cellHeight)
            if (i < filled) {
                glow.color = withAlpha(color, 0.55f * pulse)
                if (!Palette.isLight) canvas.drawRoundRect(rect, radius, radius, glow)
                fill.color = withAlpha(color, pulse)
                canvas.drawRoundRect(rect, radius, radius, fill)
                shine.shader = LinearGradient(0f, rect.top, 0f, rect.centerY(), 0x59FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
                canvas.drawRoundRect(rect, radius, radius, shine)
            } else {
                fill.color = Palette.TRACK
                canvas.drawRoundRect(rect, radius, radius, fill)
            }
        }
    }
}
