package com.th4rv4.zukinav.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.th4rv4.zukinav.R
import com.th4rv4.zukinav.maint.MaintBook
import com.th4rv4.zukinav.maint.MaintStore
import com.th4rv4.zukinav.maint.Reminder
import com.th4rv4.zukinav.maint.Status
import com.th4rv4.zukinav.trips.TripFormat
import com.th4rv4.zukinav.trips.TripStore
import com.th4rv4.zukinav.util.Prefs
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Ride tab: this month's riding totals, with the maintenance reminder that is
 * due soonest on one line underneath.
 */
class MonthCard(private val host: MainActivity) {

    private val ui = host.ui

    private val monthName = cap("")
    private val km = stat()
    private val rides = stat()
    private val time = stat()

    private val reminderIcon = ui.icon(R.drawable.ic_sec_wrench, 14, Palette.WARN)
    private val reminderName = ui.text("", 13f, Palette.TEXT).apply {
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val reminderBar = Bar(ui.ctx)
    private val reminderLeft = ui.text("", 13f, Palette.WARN, Ui.Weight.MEDIUM)

    /** The reminder line, with a hairline above it. Hidden when no reminder is on. */
    val reminderRow: LinearLayout = LinearLayout(ui.ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, ui.dp(10), 0, 0)
        background = TopLine(Palette.BORDER, ui.dp(1).toFloat().coerceAtLeast(1f))
        addView(reminderIcon, LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)).apply { marginEnd = ui.dp(8) })
        addView(reminderName, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(reminderBar, LinearLayout.LayoutParams(ui.dp(52), ui.dp(5)).apply { marginStart = ui.dp(8); marginEnd = ui.dp(8) })
        addView(reminderLeft)
        isClickable = true
        isFocusable = true
        setOnClickListener { host.showBikeSection(MainActivity.BIKE_SERVICE) }
    }

    val view: LinearLayout

    /** True when a reminder is on, so the line has something to show. */
    var hasReminder = false
        private set

    init {
        val head = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(monthName, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.text("›", 16f, Palette.MUTED))
        }
        val totals = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            background = ui.pressable(0, 12)
            setOnClickListener { host.showTab(MainActivity.Tab.TRIPS) }
            addView(head)
            addView(ui.hstack(0, column(km, "km"), column(rides, "Rides"), column(time, "Riding"), weighted = true),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(4) })
        }
        view = ui.card(totals, reminderRow, gapDp = 10, padDp = 14)
    }

    /** Reads trips and reminders again. Kept off the per-second refresh, as it reads files. */
    fun reload() {
        val today = LocalDate.now()
        monthName.text = today.month.getDisplayName(TextStyle.FULL, Locale.US).uppercase(Locale.US)
        val all = TripStore.all(host)
        val (dist, n) = TripFormat.monthTotals(all)
        km.text = if (dist < 10) String.format(Locale.US, "%.1f", dist) else dist.toInt().toString()
        rides.text = n.toString()
        time.text = TripFormat.shortDuration(TripFormat.monthSeconds(all))
        view.contentDescription = "This month: ${km.text} km, $n rides, ${time.text} riding"

        val next = soonest(host)
        hasReminder = next != null
        if (next != null) {
            val (r, st) = next
            reminderName.text = r.name
            reminderBar.fraction = if (st.due) 1f else st.fraction
            val color = if (st.due) Palette.DANGER else Palette.WARN
            reminderBar.color = color
            reminderLeft.setTextColor(color)
            reminderIcon.imageTintList = android.content.res.ColorStateList.valueOf(color)
            reminderLeft.text = when {
                st.due -> "Due Now"
                st.leftKm != null && (st.leftDays == null || st.soonByKm || st.leftDays >= MaintBook.SOON_DAYS) -> "${st.leftKm} km"
                st.leftDays != null -> if (st.leftDays == 1) "1 day" else "${st.leftDays} days"
                else -> ""
            }
            reminderRow.contentDescription = "${r.name}, ${reminderLeft.text}"
        }
    }

    private fun stat() = TextView(ui.ctx).apply {
        typeface = DashFonts.numbers
        textSize = 20f
        setTextColor(Palette.TEXT)
        gravity = Gravity.CENTER
    }

    private fun cap(s: String) = ui.text(s.uppercase(Locale.US), 11f, Palette.MUTED, Ui.Weight.BOLD).apply { letterSpacing = 0.12f }

    private fun column(value: TextView, label: String): LinearLayout =
        ui.vstack(0, value, cap(label).apply { gravity = Gravity.CENTER }).apply { gravity = Gravity.CENTER_HORIZONTAL }

    companion object {
        /** The reminder that is on and closest to due, or null when none can come due yet. */
        fun soonest(ctx: Context): Pair<Reminder, Status>? {
            val s = MaintStore.state(ctx)
            val now = System.currentTimeMillis()
            val early = Prefs(ctx).maintEarlyKm
            return s.reminders
                .filter { it.enabled && it.active }
                .map { it to MaintBook.status(it, s.odoKm, now, early) }
                .filter { (_, st) -> st.due || st.leftKm != null || st.leftDays != null }
                .maxWithOrNull(compareBy<Pair<Reminder, Status>> { it.second.due }.thenBy { it.second.fraction })
        }
    }

    /** A thin progress bar. */
    private class Bar(ctx: Context) : View(ctx) {
        var fraction = 0f
            set(v) { field = v.coerceIn(0f, 1f); invalidate() }
        var color = Palette.WARN
            set(v) { field = v; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()

        override fun onDraw(canvas: Canvas) {
            val r = height / 2f
            paint.color = Palette.INSET
            box.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(box, r, r, paint)
            if (fraction > 0f) {
                paint.color = color
                box.set(0f, 0f, maxOf(height.toFloat(), width * fraction), height.toFloat())
                canvas.drawRoundRect(box, r, r, paint)
            }
        }
    }

    /** A hairline along the top edge. */
    private class TopLine(private val lineColor: Int, private val thick: Float) : android.graphics.drawable.Drawable() {
        private val paint = Paint().apply { color = lineColor }
        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.top + thick, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}
