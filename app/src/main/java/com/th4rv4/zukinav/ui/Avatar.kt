package com.th4rv4.zukinav.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import com.th4rv4.zukinav.R
import com.th4rv4.zukinav.util.AppLog
import java.io.File
import java.util.Locale

/** The rider's avatar: an initial, one of a few icons, or a photo. */
object Avatars {
    const val INITIAL = "initial"
    const val PHOTO = "photo"
    private const val FILE = "avatar.png"
    private const val SIZE = 320

    val icons: List<Pair<String, Int>> = listOf(
        "helmet" to R.drawable.ic_avatar_helmet,
        "bolt" to R.drawable.ic_avatar_bolt,
        "peak" to R.drawable.ic_avatar_peak,
        "flame" to R.drawable.ic_avatar_flame,
        "wheel" to R.drawable.ic_avatar_wheel,
        "road" to R.drawable.ic_avatar_road,
        "star" to R.drawable.ic_avatar_star,
    )

    fun iconRes(key: String): Int? = icons.firstOrNull { it.first == key }?.second

    fun initial(name: String): String =
        name.trim().firstOrNull { it.isLetterOrDigit() }?.toString()?.uppercase(Locale.getDefault()) ?: ""

    fun photoFile(ctx: Context) = File(ctx.filesDir, FILE)

    fun loadPhoto(ctx: Context): Bitmap? = try {
        val f = photoFile(ctx)
        if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    } catch (e: Exception) {
        null
    }

    /**
     * Copies the chosen picture into the app as a square, so the avatar keeps
     * working if the original is deleted. Runs off the main thread.
     */
    fun savePhoto(ctx: Context, uri: Uri): Boolean = try {
        val src = android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)
        val full = android.graphics.ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
            val side = minOf(info.size.width, info.size.height)
            if (side > SIZE * 2) decoder.setTargetSampleSize(side / (SIZE * 2))
            decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val side = minOf(full.width, full.height)
        val square = Bitmap.createBitmap(full, (full.width - side) / 2, (full.height - side) / 2, side, side)
        val out = Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        photoFile(ctx).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    } catch (e: Exception) {
        AppLog.d("Avatar", "Could not use that picture: ${e.message}")
        false
    }
}

/**
 * Round avatar with a ring in the accent gradient. Shows the rider's
 * initial, an icon or their photo.
 */
class AvatarView(ctx: Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG)
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var kind = Avatars.INITIAL
    private var text = ""
    private var photo: Bitmap? = null

    /** Ring thickness; 0 for none. */
    var ringDp = 2.5f

    /** Colour inside the ring; null for the sheet colour. */
    var fill: Int? = null

    /** [key] is the saved choice; [name] gives the initial. */
    fun show(key: String, name: String) {
        kind = key
        text = Avatars.initial(name)
        photo = if (key == Avatars.PHOTO) Avatars.loadPhoto(context) else null
        if (key == Avatars.PHOTO && photo == null) kind = Avatars.INITIAL
        contentDescription = "Rider avatar. Tap to change."
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f
        ring.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r,
            intArrayOf(Palette.ACCENT, Palette.ACCENT_MID, Palette.ACCENT_END), null, Shader.TileMode.CLAMP)
        if (ringDp > 0f) canvas.drawCircle(cx, cy, r, ring)
        val ir = r - ringDp * d
        inner.color = fill ?: Palette.SHEET
        canvas.drawCircle(cx, cy, ir, inner)
        val cr = ir - 1.5f * d

        val bmp = photo
        if (kind == Avatars.PHOTO && bmp != null) {
            val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val scale = (cr * 2) / bmp.width
            shader.setLocalMatrix(Matrix().apply { setScale(scale, scale); postTranslate(cx - cr, cy - cr) })
            photoPaint.shader = shader
            canvas.drawCircle(cx, cy, cr, photoPaint)
            return
        }
        val res = Avatars.iconRes(kind) ?: if (text.isEmpty()) R.drawable.ic_avatar_helmet else null
        if (res != null) {
            val dr = context.getDrawable(res)?.mutate() ?: return
            dr.setTint(Palette.TEXT)
            val half = (cr * 0.55f).toInt()
            dr.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            dr.draw(canvas)
            return
        }
        letter.color = Palette.TEXT
        letter.textSize = cr * 0.95f
        canvas.drawText(text, cx, cy - (letter.descent() + letter.ascent()) / 2, letter)
    }
}

/**
 * Bottom sheet to pick the avatar: the initial, an icon, or a photo from the
 * gallery. [onPhoto] opens the picture picker; [onSaved] runs after Save.
 */
class AvatarSheet(
    private val host: MainActivity,
    private val onPhoto: () -> Unit,
    private val onSaved: () -> Unit,
) {
    private val ui = host.ui
    private val prefs = host.prefs
    private var choice = prefs.avatar

    fun show() {
        val dialog = Dialog(host)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val preview = AvatarView(host).apply { ringDp = 3f }
        preview.show(choice, prefs.riderName)

        val options = ArrayList<Pair<String, AvatarView>>()
        fun option(key: String): View {
            val v = AvatarView(host).apply { ringDp = if (key == choice) 2.5f else 0f; fill = Palette.TILE }
            v.show(key, prefs.riderName)
            v.contentDescription = when (key) {
                Avatars.INITIAL -> "Use my initial"
                Avatars.PHOTO -> "Choose a photo"
                else -> "Icon $key"
            }
            v.isClickable = true
            v.isFocusable = true
            options += key to v
            return v
        }

        val keys = ArrayList<String>()
        keys += Avatars.INITIAL
        Avatars.icons.forEach { keys += it.first }
        val grid = ui.vstack(12)
        val all = keys + "pick"
        all.chunked(4).forEach { rowKeys ->
            val row = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            for (i in 0 until 4) {
                val key = rowKeys.getOrNull(i)
                val cell: View = when (key) {
                    null -> View(host)
                    "pick" -> ui.iconTile(R.drawable.ic_avatar_photo, 52, 24, Palette.ACCENT_SOFT, Palette.ACCENT_BG, 26).apply {
                        isClickable = true
                        isFocusable = true
                        contentDescription = "Choose a photo from your gallery"
                        setOnClickListener { dialog.dismiss(); onPhoto() }
                    }
                    else -> option(key)
                }
                val frame = LinearLayout(host).apply { gravity = Gravity.CENTER }
                frame.addView(cell, LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)))
                row.addView(frame, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            grid.addView(row)
        }
        for ((key, v) in options) {
            v.setOnClickListener {
                choice = key
                preview.show(key, prefs.riderName)
                for ((k, o) in options) { o.ringDp = if (k == key) 2.5f else 0f; o.invalidate() }
            }
        }

        val head = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(preview, LinearLayout.LayoutParams(ui.dp(60), ui.dp(60)).apply { marginEnd = ui.dp(14) })
            addView(ui.vstack(2,
                ui.text("Rider Avatar", 18f, Palette.TEXT, Ui.Weight.BOLD),
                ui.text("Shown on the Ride tab", 13f, Palette.MUTED)))
        }
        val hint = ui.text(
            if (prefs.riderName.isBlank()) "Set your Rider Name in Settings to use your initial. The camera picks a photo from your gallery."
            else "The first circle uses your initial. The camera picks a photo from your gallery.",
            12f, Palette.MUTED,
        )
        val buttons = ui.hstack(
            10,
            ui.button("Cancel", Ui.Btn.SECONDARY, 48) { dialog.dismiss() },
            ui.button("Save", Ui.Btn.PRIMARY, 48) {
                prefs.avatar = choice
                dialog.dismiss()
                onSaved()
            },
            weighted = true,
        )
        val grab = View(host).apply { background = ui.rounded(Palette.BORDER, 3) }
        val sheet = ui.vstack(16, head, grid, hint, buttons).apply {
            setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(20))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Palette.SHEET)
                val r = ui.dp(26).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            addView(grab, 0, LinearLayout.LayoutParams(ui.dp(36), ui.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
        dialog.setContentView(sheet)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
    }
}
