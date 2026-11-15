package com.th4rv4.zukinav.ui

import android.app.DatePickerDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.th4rv4.zukinav.R
import com.th4rv4.zukinav.papers.Paper
import com.th4rv4.zukinav.papers.PaperFiles
import com.th4rv4.zukinav.papers.PaperNotifier
import com.th4rv4.zukinav.papers.PaperStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)

/** "17 days left", "Expires today", "Expired 3 days ago", "2 years left". */
private fun leftText(days: Long): String = when {
    days < -1 -> "Expired ${-days} days ago"
    days == -1L -> "Expired yesterday"
    days == 0L -> "Expires today"
    days == 1L -> "1 day left"
    days < 60 -> "$days days left"
    days < 730 -> "${days / 30} months left"
    else -> "${days / 365} years left"
}

/** The Papers part of Bike → Service: expiry dates, with optional photos to show at a check. */
class PapersSection(private val host: MainActivity) {

    private val ui = host.ui
    private val rows = LinearLayout(ui.ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = ui.rounded(Palette.SURFACE, 20, Palette.BORDER)
        setPadding(ui.dp(16), ui.dp(2), ui.dp(16), ui.dp(2))
    }
    private val showAll = ui.button("Show All Papers", Ui.Btn.SECONDARY, 46) {
        val pages = PaperStore.all(host).flatMap { p -> p.pages.map { p.name to it } }
        if (pages.isNotEmpty()) PaperViewer(host, pages).show()
    }

    val views: List<View> = listOf(
        ui.sectionHeader(R.drawable.ic_sec_wrench, "Papers"),
        rows,
        showAll,
    )

    fun render() {
        rows.removeAllViews()
        val list = PaperStore.all(host)
        list.forEachIndexed { i, p ->
            if (i > 0) rows.addView(line())
            rows.addView(row(p))
        }
        if (list.isNotEmpty()) rows.addView(line())
        rows.addView(ui.row("+ Add Paper", "RC, permit, anything with a date").apply {
            (getChildAt(0) as? LinearLayout)?.let { (it.getChildAt(0) as? TextView)?.setTextColor(Palette.ACCENT_SOFT) }
            isClickable = true
            isFocusable = true
            background = ui.pressable(Color.TRANSPARENT, 8)
            setOnClickListener { PaperSheet(host, null).show() }
        })
        showAll.visibility = if (list.any { it.pages.isNotEmpty() }) View.VISIBLE else View.GONE
    }

    private fun line() = View(ui.ctx).apply {
        setBackgroundColor(Palette.BORDER)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    }

    private fun row(p: Paper): View {
        val left = p.daysLeft()
        val color = when {
            left == null -> Palette.MUTED
            left <= 0 -> Palette.DANGER
            left <= PaperStore.SOON_DAYS -> Palette.WARN
            else -> Palette.OK
        }
        val sub = if (left == null) "Expiry date not set"
        else leftText(left) + " · " + LocalDate.ofEpochDay(p.expires!!).format(DATE)
        val title = ui.text(p.name + if (p.pages.isNotEmpty()) "  ·  ${p.pages.size} ${if (p.pages.size == 1) "page" else "pages"}" else "",
            15f, Palette.TEXT, Ui.Weight.MEDIUM)
        val texts = ui.vstack(2, title, ui.text(sub, 12f, color))
        return LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(56)
            setPadding(0, ui.dp(10), 0, ui.dp(10))
            isClickable = true
            isFocusable = true
            background = ui.pressable(Color.TRANSPARENT, 8)
            contentDescription = "${p.name}, $sub"
            setOnClickListener { PaperSheet(host, p).show() }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (p.pages.isNotEmpty()) {
                addView(ui.button("Show", Ui.Btn.PRIMARY, 36) {
                    PaperViewer(host, p.pages.map { p.name to it }).show()
                }.apply { minWidth = ui.dp(72) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = ui.dp(10) })
            }
        }
    }
}

/** Bottom sheet to add or edit a paper: name, expiry, how long a renewal lasts, and pages. */
class PaperSheet(private val host: MainActivity, private val paper: Paper?) {

    private val ui = host.ui
    private val id = paper?.id ?: PaperStore.newId(host)
    private var expires: Long? = paper?.expires
    private var renew = paper?.renewMonths ?: 0
    private val pages = ArrayList(paper?.pages.orEmpty())
    /** Files copied in while the sheet is open; removed again if it isn't saved. */
    private val added = ArrayList<String>()
    private val removed = ArrayList<String>()
    private var saved = false

    private val dateValue = ui.text("", 14f, Palette.TEXT)
    private val thumbs = LinearLayout(ui.ctx).apply { orientation = LinearLayout.HORIZONTAL }

    fun show() {
        val name = ui.input(paper?.name.orEmpty(), "Pollution Certificate") {}.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        val dateBox = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.pressable(Palette.INSET, 10, Palette.INPUT_BORDER)
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
            isClickable = true
            isFocusable = true
            addView(dateValue, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.text("Change", 13f, Palette.ACCENT_SOFT, Ui.Weight.MEDIUM))
            setOnClickListener { pickDate(expires?.let { LocalDate.ofEpochDay(it) } ?: LocalDate.now()) }
        }
        val choices = listOf(0, 6, 12)
        val renewPick = ui.segmented(listOf("None", "6 Months", "1 Year"), choices.indexOf(renew).coerceAtLeast(0), fill = true) {
            renew = choices[it]
        }
        val renewed = ui.button("Renewed", Ui.Btn.SECONDARY, 42) {
            // Suggests the new date; the rider can still change it.
            pickDate(LocalDate.now().plusMonths(renew.toLong().coerceAtLeast(0)))
        }
        renderDate()
        renderThumbs()

        val views = ArrayList<View>()
        views += ui.text(if (paper == null) "Add Paper" else paper.name, 18f, Palette.TEXT, Ui.Weight.BOLD)
        if (paper == null || paper.id >= 100) views += labelled("Name", name)
        views += labelled("Expires On", dateBox)
        views += labelled("Renewal Lasts", renewPick)
        if (paper != null) views += renewed
        views += labelled("Document · Optional", ui.vstack(
            8,
            android.widget.HorizontalScrollView(ui.ctx).apply {
                isHorizontalScrollBarEnabled = false
                addView(thumbs)
            },
            ui.text("Photo or PDF, up to ${PaperStore.MAX_PAGES} pages, like front and back. Long-press a page to remove it.", 12f, Palette.MUTED),
        ))

        lateinit var dialog: Dialog
        val save = ui.button("Save", Ui.Btn.PRIMARY, 48) {
            val n = if (paper != null && paper.id < 100) paper.name else name.text.toString().trim()
            if (n.isEmpty()) {
                host.toast("Give the paper a name")
                return@button
            }
            saved = true
            removed.forEach { PaperFiles.delete(host, it) }
            val p = Paper(id, n, expires, renew, pages.toList(), paper?.notified ?: 0)
            PaperStore.save(host, p)
            if (p.expires != paper?.expires) PaperNotifier.cancel(host, id)
            dialog.dismiss()
        }
        val second = if (paper == null) {
            ui.button("Cancel", Ui.Btn.SECONDARY, 48) { dialog.dismiss() }
        } else {
            ui.button("Delete", Ui.Btn.SECONDARY, 48) {
                dialog.dismiss()
                android.app.AlertDialog.Builder(host, host.dialogTheme)
                    .setTitle("Delete ${paper.name}?")
                    .setMessage(if (paper.pages.isNotEmpty()) "Its saved pages are deleted too." else null)
                    .setPositiveButton("Delete") { _, _ ->
                        PaperStore.delete(host, paper.id)
                        PaperNotifier.cancel(host, paper.id)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }.apply { setTextColor(Palette.DANGER) }
        }
        views += ui.hstack(10, second, save, weighted = true)
        dialog = ui.sheet(*views.toTypedArray())
        dialog.setOnDismissListener { if (!saved) added.forEach { PaperFiles.delete(host, it) } }
    }

    private fun pickDate(start: LocalDate) {
        DatePickerDialog(host, host.dialogTheme, { _, y, m, d ->
            expires = LocalDate.of(y, m + 1, d).toEpochDay()
            renderDate()
        }, start.year, start.monthValue - 1, start.dayOfMonth).show()
    }

    private fun renderDate() {
        dateValue.text = expires?.let { LocalDate.ofEpochDay(it).format(DATE) } ?: "Not set"
        dateValue.setTextColor(if (expires == null) Palette.MUTED else Palette.TEXT)
    }

    private fun renderThumbs() {
        thumbs.removeAllViews()
        pages.forEachIndexed { i, file ->
            thumbs.addView(ImageView(ui.ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ui.rounded(Palette.INSET, 8)
                clipToOutline = true
                contentDescription = "Page ${i + 1}"
                setImageBitmap(PaperFiles.load(host, file, 240))
                setOnClickListener { PaperViewer(host, pages.map { (paper?.name ?: "Paper") to it }, start = i).show() }
                setOnLongClickListener {
                    android.app.AlertDialog.Builder(host, host.dialogTheme)
                        .setTitle("Remove page ${i + 1}?")
                        .setPositiveButton("Remove") { _, _ ->
                            pages.remove(file)
                            if (added.remove(file)) PaperFiles.delete(host, file) else removed += file
                            renderThumbs()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
            }, tile())
        }
        if (pages.size < PaperStore.MAX_PAGES) {
            thumbs.addView(ui.vstack(2, ui.text("+", 20f, Palette.MUTED).apply { gravity = Gravity.CENTER },
                ui.text("Add", 12f, Palette.MUTED).apply { gravity = Gravity.CENTER }).apply {
                gravity = Gravity.CENTER
                background = ui.pressable(Palette.INSET, 8, Palette.INPUT_BORDER)
                isClickable = true
                isFocusable = true
                contentDescription = "Add a photo or PDF"
                setOnClickListener { addPage() }
            }, tile())
        }
    }

    private fun tile() = LinearLayout.LayoutParams(ui.dp(60), ui.dp(80)).apply { marginEnd = ui.dp(8) }

    private fun addPage() {
        host.pickPaperFile { uri ->
            if (uri == null) return@pickPaperFile
            val room = PaperStore.MAX_PAGES - pages.size
            Thread {
                val files = PaperFiles.add(host, uri, id, room)
                host.runOnUiThread {
                    if (files.isEmpty()) {
                        host.toast("Could not read that file")
                    } else {
                        pages += files
                        added += files
                        renderThumbs()
                    }
                }
            }.start()
        }
    }

    private fun labelled(label: String, v: View): View =
        ui.vstack(6, ui.text(label.uppercase(Locale.US), 11f, Palette.MUTED, Ui.Weight.BOLD).apply { letterSpacing = 0.1f }, v)
}

/**
 * Full screen pages to hand over at a check: black background, full
 * brightness, screen kept on, pinch or double-tap to zoom.
 */
class PaperViewer(private val host: MainActivity, private val pages: List<Pair<String, String>>, private val start: Int = 0) {

    private var index = start.coerceIn(0, (pages.size - 1).coerceAtLeast(0))

    fun show() {
        if (pages.isEmpty()) return
        val ui = host.ui
        val dialog = Dialog(host, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val title = ui.text("", 15f, Color.WHITE, Ui.Weight.MEDIUM)
        val count = ui.text("", 13f, 0xFFAAAAAA.toInt())
        val image = ZoomImageView(host)
        val prev = ui.text("‹", 34f, Color.WHITE).apply { setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(4)); contentDescription = "Previous page" }
        val next = ui.text("›", 34f, Color.WHITE).apply { setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(4)); contentDescription = "Next page" }

        fun render() {
            val (name, file) = pages[index]
            title.text = name
            count.text = if (pages.size > 1) "${index + 1} / ${pages.size}" else ""
            image.setPage(PaperFiles.load(host, file))
            prev.alpha = if (index > 0) 1f else 0.25f
            next.alpha = if (index < pages.size - 1) 1f else 0.25f
        }
        fun go(step: Int) {
            val to = index + step
            if (to in pages.indices) { index = to; render() }
        }
        prev.setOnClickListener { go(-1) }
        next.setOnClickListener { go(1) }
        image.onSwipe = { go(it) }

        val close = ui.text("✕", 20f, Color.WHITE).apply {
            setPadding(ui.dp(14), ui.dp(8), ui.dp(4), ui.dp(8))
            contentDescription = "Close"
            setOnClickListener { dialog.dismiss() }
        }
        val top = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), ui.dp(12), ui.dp(12), ui.dp(4))
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(count)
            addView(close)
        }
        val bottom = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(4), 0, ui.dp(16))
            if (pages.size > 1) { addView(prev); addView(next) }
        }
        val root = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(top)
            addView(image, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(bottom)
        }
        dialog.setContentView(root)
        dialog.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            attributes = attributes.apply { screenBrightness = 1f }
            // Keep the bars clear of the camera cut-out and gesture bar.
            decorView.setOnApplyWindowInsetsListener { v, insets ->
                @Suppress("DEPRECATION")
                root.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
                v.onApplyWindowInsets(insets)
            }
        }
        dialog.setOnDismissListener { image.setPage(null) }
        render()
        dialog.show()
    }
}

/** An image that fits the screen and can be pinched, dragged and double-tapped to zoom. */
class ZoomImageView(ctx: Context) : ImageView(ctx) {

    /** -1 or 1 when swiped sideways while not zoomed in. */
    var onSwipe: ((Int) -> Unit)? = null

    private val m = Matrix()
    private var fit = 1f
    private var bitmap: Bitmap? = null

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val now = scale()
            val f = (now * d.scaleFactor).coerceIn(fit, fit * 6f) / now
            m.postScale(f, f, d.focusX, d.focusY)
            settle()
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (zoomed()) { m.postTranslate(-dx, -dy); settle() }
            return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoomed()) fitToView() else { val f = 2.5f; m.postScale(f, f, e.x, e.y); settle() }
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (!zoomed() && kotlin.math.abs(vx) > kotlin.math.abs(vy) * 1.5f && kotlin.math.abs(vx) > 600) {
                onSwipe?.invoke(if (vx < 0) 1 else -1)
            }
            return true
        }
    })

    init { scaleType = ScaleType.MATRIX }

    fun setPage(b: Bitmap?) {
        val old = bitmap
        bitmap = b
        setImageBitmap(b)
        old?.takeIf { it != b }?.recycle()
        fitToView()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        fitToView()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) gestures.onTouchEvent(e)
        return true
    }

    private fun scale(): Float { val v = FloatArray(9); m.getValues(v); return v[Matrix.MSCALE_X] }
    private fun zoomed() = scale() > fit * 1.02f

    private fun fitToView() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        fit = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        m.reset()
        m.postScale(fit, fit)
        m.postTranslate((width - b.width * fit) / 2f, (height - b.height * fit) / 2f)
        imageMatrix = m
    }

    /** Keeps the page on screen: centred when smaller than the view, edges pinned when bigger. */
    private fun settle() {
        val b = bitmap ?: return
        val r = RectF(0f, 0f, b.width.toFloat(), b.height.toFloat())
        m.mapRect(r)
        val dx = when {
            r.width() <= width -> width / 2f - r.centerX()
            r.left > 0 -> -r.left
            r.right < width -> width - r.right
            else -> 0f
        }
        val dy = when {
            r.height() <= height -> height / 2f - r.centerY()
            r.top > 0 -> -r.top
            r.bottom < height -> height - r.bottom
            else -> 0f
        }
        m.postTranslate(dx, dy)
        imageMatrix = m
    }
}
