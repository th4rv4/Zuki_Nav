package com.th4rv4.zukinav.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * Accent colour presets. Each has three colours that run along the speed
 * gauge (start, middle, end); buttons use start to a blend towards end. The
 * light theme gets deeper versions so they hold up on a pale background.
 */
object Accents {

    class Preset(
        val key: String,
        val name: String,
        val dark: IntArray,
        val light: IntArray,
        /** Text colour on accent buttons in Dark and AMOLED. */
        val onDark: Int,
        /** Text colour on accent buttons in Light. */
        val onLight: Int = Color.WHITE,
    )

    const val WALLPAPER = "wallpaper"
    const val DEFAULT = "aurora"

    private fun c(vararg v: Long) = IntArray(v.size) { v[it].toInt() }
    private val WHITE = 0xFFFFFFFF.toInt()

    val presets: List<Preset> = listOf(
        Preset("aurora", "Aurora", c(0xFF2D6BFF, 0xFF7FE0FF, 0xFFFF4F8B), c(0xFF2D6BFF, 0xFF1FA0D6, 0xFFE0336F), WHITE),
        Preset("ember", "Ember", c(0xFFFF3D2E, 0xFFFF8A3D, 0xFFFFC24A), c(0xFFE02A1C, 0xFFEC6A16, 0xFFD08F0C), WHITE),
        Preset("viridian", "Viridian", c(0xFF00A884, 0xFF3DE0B5, 0xFFB6F36A), c(0xFF00866A, 0xFF10A487, 0xFF5E9A16), WHITE),
        Preset("ultraviolet", "Ultraviolet", c(0xFF6B3DFF, 0xFFB06BFF, 0xFFFF5AC8), c(0xFF5A2EE8, 0xFF8E45E6, 0xFFD43AA2), WHITE),
        Preset("solar", "Solar", c(0xFFFF9F1C, 0xFFFFD23F, 0xFFFFF3B0), c(0xFFD97E00, 0xFFC99400, 0xFFA87C08), 0xFF1E1400.toInt()),
        Preset("glacier", "Glacier", c(0xFF5AA9FF, 0xFFBDE6FF, 0xFFFFFFFF), c(0xFF2F7FE0, 0xFF3D9BDB, 0xFF6C9CC4), 0xFF0A1A2E.toInt()),
        Preset("sakura", "Sakura", c(0xFFFF5C8A, 0xFFFFA3C1, 0xFFFFE0EB), c(0xFFE0406E, 0xFFDB5F8C, 0xFFC4809A), 0xFF2A0614.toInt()),
        Preset("graphite", "Graphite", c(0xFF7C8496, 0xFFC9CFDA, 0xFFFFFFFF), c(0xFF4A5262, 0xFF656D80, 0xFF8E96A6), 0xFF0B0D12.toInt()),
    )

    /** Wallpaper colours exist from Android 12. */
    val wallpaperSupported: Boolean get() = Build.VERSION.SDK_INT >= 31

    fun name(key: String): String =
        if (key == WALLPAPER) "Wallpaper" else presets.firstOrNull { it.key == key }?.name ?: "Aurora"

    /** Start, middle, end and on-accent text colours for [key] in the given theme. */
    fun resolve(ctx: Context?, key: String, light: Boolean): IntArray {
        if (key == WALLPAPER && ctx != null && wallpaperSupported) {
            wallpaper(ctx, light)?.let { return it }
        }
        val p = presets.firstOrNull { it.key == key } ?: presets[0]
        val g = if (light) p.light else p.dark
        return intArrayOf(g[0], g[1], g[2], if (light) p.onLight else p.onDark)
    }

    @android.annotation.SuppressLint("NewApi") // Only called on Android 12 and up.
    private fun wallpaper(ctx: Context, light: Boolean): IntArray? = try {
        fun col(id: Int) = ctx.resources.getColor(id, ctx.theme)
        if (light) {
            intArrayOf(
                col(android.R.color.system_accent1_600),
                col(android.R.color.system_accent1_500),
                col(android.R.color.system_accent3_500),
                Color.WHITE,
            )
        } else {
            val start = col(android.R.color.system_accent1_300)
            intArrayOf(
                start,
                col(android.R.color.system_accent1_200),
                col(android.R.color.system_accent3_200),
                if (luminance(blend(start, col(android.R.color.system_accent3_200), 0.25f)) > 0.42) {
                    col(android.R.color.system_accent1_900)
                } else Color.WHITE,
            )
        }
    } catch (e: Exception) {
        null
    }

    fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).toInt().coerceIn(0, 255)
        return Color.argb(
            ch(Color.alpha(a), Color.alpha(b)),
            ch(Color.red(a), Color.red(b)),
            ch(Color.green(a), Color.green(b)),
            ch(Color.blue(a), Color.blue(b)),
        )
    }

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    fun luminance(color: Int): Double {
        fun lin(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin(Color.red(color)) + 0.7152 * lin(Color.green(color)) + 0.0722 * lin(Color.blue(color))
    }
}

/**
 * The app background: a flat base with two soft glows in the accent colours,
 * top right and lower left. AMOLED has no glow so the screen stays black.
 */
class AuroraBackground : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawColor(Palette.BG)
        if (Palette.BG_GLOW_1 == 0 && Palette.BG_GLOW_2 == 0) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        glow(canvas, w, 0f, w * 1.15f, h * 0.55f, Palette.BG_GLOW_1)
        glow(canvas, 0f, h * 0.72f, w * 0.95f, h * 0.45f, Palette.BG_GLOW_2)
    }

    private fun glow(canvas: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, color: Int) {
        if (color == 0) return
        canvas.save()
        canvas.scale(1f, ry / rx, cx, cy)
        paint.shader = RadialGradient(cx, cy, rx, intArrayOf(color, Accents.withAlpha(color, 0)), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, rx, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Required by Drawable")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/** A round accent swatch: the preset's gradient, with a ring when chosen. */
class SwatchView(ctx: Context, private val colors: IntArray, private val chosen: Boolean) : android.view.View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val d = ctx.resources.displayMetrics.density

    init {
        if (!Palette.isLight) setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 6 * d
        fill.shader = android.graphics.LinearGradient(cx - r, cy + r, cx + r, cy - r, colors, null, Shader.TileMode.CLAMP)
        if (!Palette.isLight) fill.setShadowLayer(8 * d, 0f, 0f, Accents.withAlpha(colors[0], 0x80))
        canvas.drawCircle(cx, cy, r, fill)
        if (chosen) {
            ring.strokeWidth = 2 * d
            ring.color = Palette.TEXT
            canvas.drawCircle(cx, cy, r + 4 * d, ring)
        }
    }
}
