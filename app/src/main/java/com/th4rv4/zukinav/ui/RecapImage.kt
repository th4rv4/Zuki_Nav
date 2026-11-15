package com.th4rv4.zukinav.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.th4rv4.zukinav.trips.MonthRecap
import com.th4rv4.zukinav.trips.RoutePoint
import com.th4rv4.zukinav.trips.TripFormat
import java.time.format.TextStyle
import java.util.Locale

/** A month's riding as a 1080 × 1440 image: totals on top, route doodles below. */
object RecapImage {

    /** The stat switches, in the order they appear. */
    val STATS = listOf(
        ShareImage.Stat("km", "Distance", true),
        ShareImage.Stat("rides", "Rides", true),
        ShareImage.Stat("riding", "Riding Time", true),
        ShareImage.Stat("longest", "Longest Ride", true),
        ShareImage.Stat("top", "Top Speed", false),
    )

    /** How many route doodles fit. */
    const val MAX_ROUTES = 6

    fun value(r: MonthRecap, key: String): String? = when (key) {
        "km" -> if (r.km < 10) String.format(Locale.US, "%.1f km", r.km) else "${r.km.toInt()} km"
        "rides" -> r.rides.toString()
        "riding" -> TripFormat.shortDuration(r.ridingSeconds)
        "longest" -> TripFormat.distance(r.longestKm)
        "top" -> r.topSpeedKmh.takeIf { it > 0 }?.let { "$it km/h" }
        else -> null
    }

    fun render(r: MonthRecap, routes: List<List<RoutePoint>>, background: Int, stats: Set<String>, showRoutes: Boolean, scale: Float = 1f): Bitmap {
        val w = (ShareImage.WIDTH * scale).toInt()
        val h = (ShareImage.HEIGHT * scale).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val u = w / 1080f
        val light = background == ShareImage.BG_LIGHT
        val shadow = background == ShareImage.BG_TRANSPARENT
        when (background) {
            ShareImage.BG_DARK -> c.drawColor(Color.rgb(14, 15, 20))
            ShareImage.BG_LIGHT -> c.drawColor(Color.rgb(244, 245, 248))
            else -> c.drawColor(Color.TRANSPARENT)
        }
        val ink = if (light) Color.rgb(20, 20, 26) else Color.WHITE
        val soft = if (light) Color.rgb(90, 90, 100) else Color.rgb(225, 225, 235)
        fun Paint.shade(r: Float) { if (shadow) setShadowLayer(r * u, 0f, 2f * u, Color.argb(150, 0, 0, 0)) }

        val left = 90f * u
        var y = 110f * u
        val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 30f * u
            letterSpacing = 0.16f
            color = soft
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            shade(6f)
        }
        c.drawText("ZUKI NAV · ${r.month.year}", left, y, cap)
        y += 120f * u
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 110f * u
            color = ink
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            shade(8f)
        }
        c.drawText(r.month.month.getDisplayName(TextStyle.FULL, Locale.US), left - 4f * u, y, title)
        y += 70f * u

        val shown = STATS.filter { it.key in stats }.mapNotNull { s -> value(r, s.key)?.let { s.label to it } }
        if (shown.isNotEmpty()) {
            val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 74f * u
                color = ink
                typeface = DashFonts.numbers
                shade(8f)
            }
            shown.chunked(2).forEach { row ->
                row.forEachIndexed { i, (label, v) ->
                    val x = if (i == 0) left else w / 2f + 20f * u
                    c.drawText(label.uppercase(Locale.US), x, y + 30f * u, cap)
                    c.drawText(v, x, y + 110f * u, num)
                }
                y += 160f * u
            }
            y += 10f * u
        }

        val doodles = if (showRoutes) routes.filter { it.size >= 2 }.take(MAX_ROUTES) else emptyList()
        if (doodles.isNotEmpty()) {
            val cols = 3
            val gap = 24f * u
            val cell = (w - 2 * left - gap * (cols - 1)) / cols
            val rowsNeeded = (doodles.size + cols - 1) / cols
            val bottomLimit = h - 130f * u
            val size = minOf(cell, (bottomLimit - y - gap * (rowsNeeded - 1)) / rowsNeeded)
            if (size > 60f * u) {
                val tile = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = when {
                        light -> Color.rgb(230, 232, 238)
                        shadow -> Color.argb(70, 0, 0, 0)
                        else -> Color.rgb(28, 30, 38)
                    }
                }
                doodles.forEachIndexed { i, pts ->
                    val x = left + (i % cols) * (size + gap)
                    val top = y + (i / cols) * (size + gap)
                    c.drawRoundRect(RectF(x, top, x + size, top + size), 22f * u, 22f * u, tile)
                    val pad = size * 0.16f
                    ShareImage.drawRoute(c, pts, x + pad, top + pad, x + size - pad, top + size - pad,
                        ShareImage.LINE_WHITE, 7f * u, false, light)
                }
                y += rowsNeeded * size + (rowsNeeded - 1) * gap
            }
        }

        val more = r.rides - doodles.size
        if (showRoutes && doodles.isNotEmpty() && more > 0) {
            val small = Paint(cap).apply { letterSpacing = 0.06f; typeface = Typeface.DEFAULT }
            c.drawText("+$more more ${if (more == 1) "ride" else "rides"}", left, y + 70f * u, small)
        }
        return bmp
    }
}
