package com.th4rv4.zukinav.ui

import android.app.AlertDialog
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import com.th4rv4.zukinav.protocol.EngineState
import com.th4rv4.zukinav.trips.Trip
import com.th4rv4.zukinav.trips.TripFormat
import com.th4rv4.zukinav.trips.TripStore

/** One trip: its numbers, and Rename, Star, Share and Delete. */
class TripDetailScreen(host: MainActivity, private val id: Long) : Screen(host) {

    private val title = ui.text("", 20f, Palette.TEXT, Ui.Weight.BOLD).apply {
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val star = ui.text("", 24f, Palette.WARN).apply {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setPadding(ui.dp(10), ui.dp(4), ui.dp(4), ui.dp(4))
        setOnClickListener { trip()?.let { TripStore.update(host, it.copy(starred = !it.starred)); render() } }
    }
    private val span = ui.text("", 13f, Palette.MUTED)
    private val distance = android.widget.TextView(ui.ctx).apply {
        typeface = DashFonts.numbers
        textSize = 52f
        gravity = Gravity.CENTER
        setTextColor(Palette.TEXT)
    }
    private val from = ui.text("", 13f, Palette.MUTED).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private val to = ui.text("", 13f, Palette.MUTED).apply {
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.END
    }
    private val placesRow = LinearLayout(ui.ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(from, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ui.text("→", 13f, Palette.MUTED).apply { setPadding(ui.dp(8), 0, ui.dp(8), 0) })
        addView(to, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    private val grid = ui.vstack(8)
    private val odo = number(16f)

    override val view: View

    init {
        val back = ui.text("‹", 30f, Palette.TEXT).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Back to Trips"
            setPadding(ui.dp(4), 0, ui.dp(14), ui.dp(4))
            setOnClickListener { host.showTab(MainActivity.Tab.TRIPS) }
        }
        val header = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back)
            addView(ui.vstack(2, title, span), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(star)
        }
        val hero = ui.card(
            cap("Distance").apply { gravity = Gravity.CENTER },
            distance,
            placesRow,
            gapDp = 4,
            padDp = 18,
        )
        val odoCard = ui.card(
            LinearLayout(ui.ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(cap("ODO"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(odo)
            },
            padDp = 14,
            radiusDp = 16,
        )
        view = host.page(
            header,
            hero,
            grid,
            odoCard,
            ui.hstack(
                8,
                ui.button("Rename", Ui.Btn.SECONDARY, 48) { rename() },
                ui.button("Delete", Ui.Btn.SECONDARY, 48) { delete() }.apply { setTextColor(Palette.DANGER) },
                ui.button("Share", Ui.Btn.PRIMARY, 48) { share() },
                weighted = true,
            ),
        )
    }

    override fun onShow() = render()

    private fun trip(): Trip? = TripStore.all(host).firstOrNull { it.id == id }

    private fun render() {
        val t = trip() ?: run { host.showTab(MainActivity.Tab.TRIPS); return }
        val is24 = DateFormat.is24HourFormat(host)
        title.text = TripFormat.title(t)
        star.text = if (t.starred) "★" else "☆"
        star.setTextColor(if (t.starred) Palette.WARN else Palette.MUTED)
        star.contentDescription = if (t.starred) "Unstar" else "Star"
        span.text = TripFormat.span(t, is24)

        val km = TripFormat.distance(t.distanceKm)
        distance.text = km
        distance.post { paintDistance() }
        placesRow.visibility = if (t.startPlace != null || t.endPlace != null) View.VISIBLE else View.GONE
        from.text = t.startPlace ?: "Unknown"
        to.text = t.endPlace ?: "Unknown"

        grid.removeAllViews()
        val cells = listOf(
            "Moving Time" to TripFormat.duration(t.movingSeconds),
            "Stopped" to TripFormat.duration(t.durationSeconds - t.movingSeconds),
            "Average Speed" to (t.averageKmh?.let { "${it.toInt()} km/h" } ?: "-"),
            "Top Speed" to "${t.topSpeedKmh} km/h",
        )
        cells.chunked(2).forEach { pair ->
            grid.addView(ui.hstack(8, *pair.map { (k, v) -> cell(k, v) }.toTypedArray(), weighted = true))
        }
        val maxC = t.maxEngineC
        val avgC = t.avgEngineC
        if (maxC != null && avgC != null) {
            grid.addView(ui.hstack(8, cell("Max Engine Temp", "$maxC °C", engineColor(maxC)),
                cell("Average Engine Temp", "$avgC °C", engineColor(avgC)), weighted = true))
        }
        odo.text = "${TripFormat.odo(t.startOdo)} → ${TripFormat.odo(t.endOdo)}"
    }

    /** The distance in the accent gradient, like the speed gauge. */
    private fun paintDistance() {
        val w = distance.paint.measureText(distance.text.toString())
        if (w <= 0f) return
        val start = (distance.width - w) / 2
        distance.paint.shader = android.graphics.LinearGradient(
            start, 0f, start + w, 0f,
            intArrayOf(Palette.ACCENT_MID, Palette.ACCENT_END), null, android.graphics.Shader.TileMode.CLAMP,
        )
        distance.invalidate()
    }

    private fun number(sizeSp: Float) = android.widget.TextView(ui.ctx).apply {
        typeface = DashFonts.numbers
        textSize = sizeSp
        setTextColor(Palette.TEXT)
        gravity = Gravity.CENTER
    }

    private fun cap(s: String) = ui.text(s.uppercase(java.util.Locale.US), 11f, Palette.MUTED, Ui.Weight.BOLD).apply { letterSpacing = 0.12f }

    /** Coloured by the rider's engine limits, if set. */
    private fun engineColor(c: Int): Int = when (EngineState.of(c, prefs.engineWarmC, prefs.engineHotC)) {
        EngineState.HOT -> Palette.DANGER
        EngineState.NORMAL -> Palette.OK
        EngineState.WARMING_UP -> EngineHeatView.COLD
        null -> Palette.TEXT
    }

    private fun cell(label: String, value: String, color: Int = Palette.TEXT): View =
        ui.vstack(4, cap(label).apply { gravity = Gravity.CENTER }, number(19f).apply { text = value; setTextColor(color) }).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            background = ui.rounded(Palette.SURFACE, 16, Palette.BORDER)
            setPadding(ui.dp(8), ui.dp(12), ui.dp(8), ui.dp(12))
        }

    private fun rename() {
        val t = trip() ?: return
        val input = EditText(host).apply {
            setText(t.name ?: TripFormat.title(t))
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            filters = arrayOf(android.text.InputFilter.LengthFilter(40))
        }
        val box = LinearLayout(host).apply {
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(host, host.dialogTheme)
            .setTitle("Rename Trip")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                TripStore.update(host, t.copy(name = name.ifEmpty { null }))
                render()
            }
            .setNeutralButton("Reset") { _, _ -> TripStore.update(host, t.copy(name = null)); render() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun share() {
        val t = trip() ?: return
        host.shareText(TripFormat.title(t), TripFormat.shareText(t, DateFormat.is24HourFormat(host)))
    }

    private fun delete() {
        val t = trip() ?: return
        AlertDialog.Builder(host, host.dialogTheme)
            .setTitle("Delete Trip?")
            .setMessage(TripFormat.title(t) + " will be removed.")
            .setPositiveButton("Delete") { _, _ ->
                TripStore.delete(host, t.id)
                host.showTab(MainActivity.Tab.TRIPS)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
