package com.th4rv4.zukinav.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.th4rv4.zukinav.trips.Route
import com.th4rv4.zukinav.trips.RoutePoint
import com.th4rv4.zukinav.util.Prefs

/**
 * A ride's route on a plain dark background, Strava style: coloured by
 * speed, by engine temperature, or in the accent colour. [zoomable] lets
 * the full-screen view pinch, pan and double-tap to reset.
 */
class RouteView(ctx: Context, private val thumbnail: Boolean = false, private val zoomable: Boolean = false) : View(ctx) {

    private val d = resources.displayMetrics.density
    private var points: List<RoutePoint> = emptyList()
    private var xy: List<Pair<Double, Double>> = emptyList()
    private var mode = Prefs.ROUTE_SPEED
    private var warmC = 0
    private var hotC = 0

    private val grid = Paint().apply { strokeWidth = 1f * d }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.argb(140, 0, 0, 0)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 11 * d }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    // Zoom and pan for the full-screen map.
    private val view = Matrix()
    private var scale = 1f
    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(g: ScaleGestureDetector): Boolean {
            val next = (scale * g.scaleFactor).coerceIn(1f, 12f)
            val f = next / scale
            scale = next
            view.postScale(f, f, g.focusX, g.focusY)
            invalidate()
            return true
        }
    })
    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scale <= 1f) return false
            view.postTranslate(-dx, -dy)
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            view.reset()
            scale = 1f
            invalidate()
            return true
        }
    })

    fun set(points: List<RoutePoint>, mode: Int, warmC: Int, hotC: Int) {
        this.points = points
        this.xy = Route.project(points)
        this.mode = mode
        this.warmC = warmC
        this.hotC = hotC
        contentDescription = if (points.size < 2) "No route recorded" else "Route of this ride"
        invalidate()
    }

    /** Where the trip stopped, drawn as amber dots. */
    var stops: List<Pair<Double, Double>> = emptyList()
        set(v) { field = v; invalidate() }

    fun hasEngine(): Boolean = points.any { it.engineC != null }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!zoomable) return super.onTouchEvent(event)
        parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val wf = width.toFloat()
        val hf = height.toFloat()
        canvas.drawColor(if (Palette.isLight) Color.rgb(236, 237, 242) else Color.rgb(18, 19, 24))
        if (!thumbnail) drawGrid(canvas)
        if (xy.size < 2) {
            if (thumbnail) {
                label.color = Palette.MUTED
                label.textSize = 10 * d
                canvas.drawText("No route", wf / 2f, hf / 2f + 4 * d, label)
            }
            return
        }
        val pad = (if (thumbnail) 8 else 26) * d
        val minX = xy.minOf { it.first }
        val maxX = xy.maxOf { it.first }
        val minY = xy.minOf { it.second }
        val maxY = xy.maxOf { it.second }
        val spanX = (maxX - minX).coerceAtLeast(1.0)
        val spanY = (maxY - minY).coerceAtLeast(1.0)
        val s = minOf((wf - 2 * pad) / spanX, (hf - 2 * pad) / spanY)
        val ox = (wf - spanX * s) / 2 - minX * s
        val oy = (hf - spanY * s) / 2 - minY * s
        val pts = FloatArray(xy.size * 2)
        xy.forEachIndexed { i, (x, y) ->
            pts[2 * i] = (x * s + ox).toFloat()
            pts[2 * i + 1] = (y * s + oy).toFloat()
        }
        if (zoomable) view.mapPoints(pts)

        val w = (if (thumbnail) 3f else 4f) * d
        path.reset()
        path.moveTo(pts[0], pts[1])
        for (i in 1 until xy.size) path.lineTo(pts[2 * i], pts[2 * i + 1])
        if (!thumbnail) {
            shadow.strokeWidth = w + 3 * d
            canvas.drawPath(path, shadow)
        }
        line.strokeWidth = w
        if (thumbnail || mode == Prefs.ROUTE_PLAIN || (mode == Prefs.ROUTE_ENGINE && !hasEngine())) {
            line.color = Palette.ACCENT
            canvas.drawPath(path, line)
        } else {
            val top = (points.maxOf { it.speedKmh }).coerceAtLeast(30)
            for (i in 1 until xy.size) {
                val p = points[i]
                line.color = if (mode == Prefs.ROUTE_SPEED) speedColor(p.speedKmh, top) else engineColor(p.engineC)
                canvas.drawLine(pts[2 * i - 2], pts[2 * i - 1], pts[2 * i], pts[2 * i + 1], line)
            }
        }
        if (thumbnail) return

        // Start (green) and end (white ring).
        val r = 6 * d
        dot.color = Color.rgb(18, 19, 24)
        canvas.drawCircle(pts[0], pts[1], r + 2 * d, dot)
        dot.color = Palette.OK
        canvas.drawCircle(pts[0], pts[1], r, dot)
        val ex = pts[pts.size - 2]
        val ey = pts[pts.size - 1]
        dot.color = Color.rgb(18, 19, 24)
        canvas.drawCircle(ex, ey, r + 2 * d, dot)
        dot.color = Color.WHITE
        canvas.drawCircle(ex, ey, r, dot)
        dot.color = Color.rgb(18, 19, 24)
        canvas.drawCircle(ex, ey, r * 0.4f, dot)

        // Stops of a merged trip.
        if (stops.isNotEmpty()) {
            // Same flat projection as the route itself.
            val lat0 = (points.minOf { it.lat } + points.maxOf { it.lat }) / 2
            val k = Math.cos(Math.toRadians(lat0)) * 111_320.0
            val sp = FloatArray(stops.size * 2)
            for (i in stops.indices) {
                val (lat, lng) = stops[i]
                sp[2 * i] = (lng * k * s + ox).toFloat()
                sp[2 * i + 1] = (-lat * 111_320.0 * s + oy).toFloat()
            }
            if (zoomable) view.mapPoints(sp)
            for (i in stops.indices) {
                dot.color = Color.rgb(18, 19, 24)
                canvas.drawCircle(sp[2 * i], sp[2 * i + 1], r * 0.85f + 2 * d, dot)
                dot.color = Palette.WARN
                canvas.drawCircle(sp[2 * i], sp[2 * i + 1], r * 0.85f, dot)
            }
        }

        // Top speed marker.
        val f = Route.fastest(points)
        if (f >= 0 && points[f].speedKmh > 0) {
            val fx = pts[2 * f]
            val fy = pts[2 * f + 1]
            val text = "${points[f].speedKmh} km/h"
            label.textSize = 11 * d
            val tw = label.measureText(text)
            val by = (fy - 14 * d).coerceAtLeast(14 * d)
            val box = RectF(fx - tw / 2 - 8 * d, by - 12 * d, fx + tw / 2 + 8 * d, by + 6 * d)
            pill.color = Color.argb(210, 11, 11, 13)
            canvas.drawRoundRect(box, 9 * d, 9 * d, pill)
            label.color = Color.rgb(255, 138, 138)
            canvas.drawText(text, fx, by + 1 * d, label)
            dot.color = Color.rgb(255, 138, 138)
            canvas.drawCircle(fx, fy, 3 * d, dot)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        grid.color = if (Palette.isLight) Color.rgb(225, 227, 233) else Color.rgb(27, 29, 36)
        val wf = width.toFloat()
        val hf = height.toFloat()
        val step = 28 * d
        var x = step / 2
        while (x < wf) { canvas.drawLine(x, 0f, x, hf, grid); x += step }
        var y = step / 2
        while (y < hf) { canvas.drawLine(0f, y, wf, y, grid); y += step }
    }

    private fun engineColor(c: Int?): Int = when {
        c == null -> Palette.MUTED
        hotC > 0 && c >= hotC -> SPEED_COLORS[3]
        warmC > 0 && c < warmC -> SPEED_COLORS[0]
        hotC > 0 && c >= hotC - 8 -> SPEED_COLORS[2]
        else -> SPEED_COLORS[1]
    }

    companion object {
        /** Slow to fast: blue, green, amber, red. Also used for the legend. */
        val SPEED_COLORS = intArrayOf(Color.rgb(63, 182, 255), Color.rgb(95, 211, 141), Color.rgb(245, 184, 74), Color.rgb(255, 107, 107))

        fun speedColor(kmh: Int, top: Int): Int {
            val f = (kmh.toFloat() / top).coerceIn(0f, 1f) * (SPEED_COLORS.size - 1)
            val i = f.toInt().coerceAtMost(SPEED_COLORS.size - 2)
            return blend(SPEED_COLORS[i], SPEED_COLORS[i + 1], f - i)
        }

        private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
        )
    }
}
