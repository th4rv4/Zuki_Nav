package com.th4rv4.zukinav.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.th4rv4.zukinav.trips.Route
import com.th4rv4.zukinav.trips.RoutePoint
import com.th4rv4.zukinav.trips.Trip
import com.th4rv4.zukinav.trips.TripFormat
import com.th4rv4.zukinav.util.AppLog
import java.io.File
import java.util.Locale

/**
 * The trip's route as a PNG to share, Strava style: the line and the chosen
 * stats on a transparent (or dark / light) background, 1080 × 1440.
 */
object ShareImage {

    const val WIDTH = 1080
    const val HEIGHT = 1440

    const val BG_TRANSPARENT = 0
    const val BG_DARK = 1
    const val BG_LIGHT = 2

    const val LINE_ACCENT = 0
    const val LINE_SPEED = 1
    /** White on dark and transparent backgrounds, black on the light one. */
    const val LINE_WHITE = 2

    /** The stat switches, in the order they appear on the image. Max Engine Temp starts off. */
    val STATS = listOf(
        Stat("distance", "Distance", true),
        Stat("time", "Time", true),
        Stat("avg", "Avg Speed", true),
        Stat("top", "Top Speed", true),
        Stat("engine", "Max Engine Temp", false),
    )

    data class Stat(val key: String, val label: String, val default: Boolean)

    data class Options(
        val background: Int,
        val line: Int,
        val showStats: Boolean,
        val stats: Set<String>,
        val showName: Boolean,
    )

    /** The label and value of a stat for this trip, or null if the trip doesn't have it. */
    fun value(t: Trip, key: String): String? = when (key) {
        "distance" -> TripFormat.distance(t.distanceKm)
        "time" -> TripFormat.shortDuration(t.ridingSeconds)
        "avg" -> t.averageKmh?.let { "${it.toInt()} km/h" }
        "top" -> "${t.topSpeedKmh} km/h"
        "engine" -> t.maxEngineC?.let { "$it °C" }
        else -> null
    }

    fun render(t: Trip, points: List<RoutePoint>, o: Options, scale: Float = 1f): Bitmap {
        val w = (WIDTH * scale).toInt()
        val h = (HEIGHT * scale).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val u = w / 1080f
        val light = o.background == BG_LIGHT
        when (o.background) {
            BG_DARK -> c.drawColor(Color.rgb(14, 15, 20))
            BG_LIGHT -> c.drawColor(Color.rgb(244, 245, 248))
            else -> c.drawColor(Color.TRANSPARENT)
        }
        val ink = if (light) Color.rgb(20, 20, 26) else Color.WHITE
        val soft = if (light) Color.rgb(90, 90, 100) else Color.rgb(225, 225, 235)
        val shadow = o.background == BG_TRANSPARENT

        // Stats, two to a row, at the top.
        var top = 90f * u
        val shown = if (o.showStats) STATS.filter { it.key in o.stats }.mapNotNull { s -> value(t, s.key)?.let { s.label to it } } else emptyList()
        if (shown.isNotEmpty()) {
            val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                textSize = 30f * u
                letterSpacing = 0.14f
                color = soft
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                if (shadow) setShadowLayer(6f * u, 0f, 2f * u, Color.argb(150, 0, 0, 0))
            }
            val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                textSize = 76f * u
                color = ink
                typeface = DashFonts.numbers
                if (shadow) setShadowLayer(8f * u, 0f, 3f * u, Color.argb(160, 0, 0, 0))
            }
            val rows = shown.chunked(2)
            rows.forEach { row ->
                row.forEachIndexed { i, (label, value) ->
                    val cx = if (row.size == 1) w / 2f else w * (if (i == 0) 0.29f else 0.71f)
                    c.drawText(label.uppercase(Locale.US), cx, top + 30f * u, cap)
                    c.drawText(value, cx, top + 112f * u, num)
                }
                top += 170f * u
            }
            top += 20f * u
        }

        // Name at the bottom.
        var bottom = h - 70f * u
        if (o.showName) {
            val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                textSize = 32f * u
                letterSpacing = 0.16f
                color = soft
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                if (shadow) setShadowLayer(6f * u, 0f, 2f * u, Color.argb(150, 0, 0, 0))
            }
            val text = (TripFormat.title(t) + " · ZUKI NAV").uppercase(Locale.US)
            val fitted = if (name.measureText(text) > w - 120f * u) "ZUKI NAV" else text
            c.drawText(fitted, w / 2f, bottom, name)
            bottom -= 70f * u
        }

        drawRoute(c, points, 90f * u, top + 30f * u, w - 90f * u, bottom, o.line, 14f * u, shadow, light)
        return bmp
    }

    internal fun drawRoute(
        c: Canvas, points: List<RoutePoint>, l: Float, t: Float, r: Float, b: Float,
        line: Int, width: Float, shadow: Boolean, light: Boolean,
    ) {
        val xy = Route.project(points)
        if (xy.size < 2 || r <= l || b <= t) return
        val minX = xy.minOf { it.first }
        val maxX = xy.maxOf { it.first }
        val minY = xy.minOf { it.second }
        val maxY = xy.maxOf { it.second }
        val spanX = (maxX - minX).coerceAtLeast(1.0)
        val spanY = (maxY - minY).coerceAtLeast(1.0)
        val boxW = (r - l).toDouble()
        val boxH = (b - t).toDouble()
        val s = minOf(boxW / spanX, boxH / spanY)
        val ox = l + (boxW - spanX * s) / 2 - minX * s
        val oy = t + (boxH - spanY * s) / 2 - minY * s
        val px = FloatArray(xy.size) { (xy[it].first * s + ox).toFloat() }
        val py = FloatArray(xy.size) { (xy[it].second * s + oy).toFloat() }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = width
        }
        val path = Path().apply {
            moveTo(px[0], py[0])
            for (i in 1 until px.size) lineTo(px[i], py[i])
        }
        if (shadow) {
            val under = Paint(paint).apply { color = Color.argb(90, 0, 0, 0); strokeWidth = width * 1.6f }
            c.drawPath(path, under)
        }
        if (line == LINE_SPEED) {
            val topKmh = points.maxOf { it.speedKmh }.coerceAtLeast(30)
            for (i in 1 until px.size) {
                paint.color = RouteView.speedColor(points[i].speedKmh, topKmh)
                c.drawLine(px[i - 1], py[i - 1], px[i], py[i], paint)
            }
        } else {
            paint.color = when {
                line != LINE_WHITE -> Palette.ACCENT_MID
                light -> Color.rgb(20, 20, 26)
                else -> Color.WHITE
            }
            c.drawPath(path, paint)
        }
    }

    /** Writes the PNG for sharing and returns its content:// address. */
    fun forSharing(ctx: Context, bmp: Bitmap): Uri? = try {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, FILE_NAME)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Uri.parse("content://${ShareFileProvider.authority(ctx)}/$FILE_NAME")
    } catch (e: Exception) {
        AppLog.d("Share", "Could not write the image: $e")
        null
    }

    fun share(ctx: Context, bmp: Bitmap): Boolean {
        val uri = forSharing(ctx, bmp) ?: return false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share ride").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        return true
    }

    /** Saves to Pictures/Zuki Nav in the Gallery. */
    fun save(ctx: Context, bmp: Bitmap, name: String): Boolean = try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Zuki Nav")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) false else {
            ctx.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            true
        }
    } catch (e: Exception) {
        AppLog.d("Share", "Could not save the image: $e")
        false
    }

    private const val FILE_NAME = "zuki-nav-ride.png"
}

/** Hands a shared ride image or log file to other apps, read-only, from the app's cache. */
class ShareFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun file(uri: Uri): File? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.startsWith(".")) return null
        return File(File(ctx.cacheDir, "share"), name).takeIf { it.exists() }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = file(uri) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String =
        if (uri.lastPathSegment?.endsWith(".txt") == true) "text/plain" else "image/png"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = file(uri)
        val cols = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols).apply { if (f != null) addRow(arrayOf<Any>(f.name, f.length())) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int = 0

    companion object {
        fun authority(ctx: Context) = "${ctx.packageName}.share"
    }
}
