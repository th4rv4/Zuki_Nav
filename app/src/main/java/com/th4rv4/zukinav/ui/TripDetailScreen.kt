package com.th4rv4.zukinav.ui

import android.app.AlertDialog
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import com.th4rv4.zukinav.trips.Trip
import com.th4rv4.zukinav.trips.TripFormat
import com.th4rv4.zukinav.trips.TripStore

/** One trip: its numbers, and Rename, Star, Share and Delete. */
class TripDetailScreen(host: MainActivity, private val id: Long) : Screen(host) {

    private val title = ui.text("", 24f, Palette.TEXT, Ui.Weight.BOLD)
    private val star = ui.text("", 24f, Palette.ACCENT_SOFT).apply {
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setPadding(ui.dp(10), ui.dp(4), ui.dp(4), ui.dp(4))
        setOnClickListener { trip()?.let { TripStore.update(host, it.copy(starred = !it.starred)); render() } }
    }
    private val span = ui.text("", 13f, Palette.MUTED)
    private val places = ui.vstack(2)
    private val placesCard = ui.card(places, gapDp = 0, padDp = 14, radiusDp = 16)
    private val grid = ui.vstack(8)

    override val view: View

    init {
        val back = ui.text("‹  Trips", 15f, Palette.ACCENT_SOFT, Ui.Weight.MEDIUM).apply {
            isClickable = true
            isFocusable = true
            setPadding(0, ui.dp(4), ui.dp(12), ui.dp(4))
            setOnClickListener { host.showTab(MainActivity.Tab.TRIPS) }
        }
        val header = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(star)
        }
        view = host.page(
            back,
            ui.vstack(2, header, span),
            placesCard,
            grid,
            ui.hstack(
                8,
                ui.button("Rename", Ui.Btn.SECONDARY, 46) { rename() },
                ui.button("Share", Ui.Btn.SECONDARY, 46) { share() },
                ui.button("Delete", Ui.Btn.DANGER, 46) { delete() },
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
        star.contentDescription = if (t.starred) "Unstar" else "Star"
        span.text = TripFormat.span(t, is24)

        places.removeAllViews()
        if (t.startPlace != null || t.endPlace != null) {
            places.addView(ui.text(t.startPlace ?: "Unknown", 14f, Palette.TEXT_SOFT))
            places.addView(ui.text("↓", 14f, Palette.MUTED).apply { setPadding(ui.dp(6), 0, 0, 0) })
            places.addView(ui.text(t.endPlace ?: "Unknown", 14f, Palette.TEXT_SOFT))
        }
        placesCard.visibility = if (places.childCount > 0) View.VISIBLE else View.GONE

        grid.removeAllViews()
        val cells = listOf(
            "Distance" to TripFormat.distance(t.distanceKm),
            "Moving Time" to TripFormat.duration(t.movingSeconds),
            "Average Speed" to (t.averageKmh?.let { "${it.toInt()} km/h" } ?: "-"),
            "Top Speed" to "${t.topSpeedKmh} km/h",
            "ODO" to "${TripFormat.odo(t.startOdo)} → ${TripFormat.odo(t.endOdo)}",
            "Stopped" to TripFormat.duration(t.durationSeconds - t.movingSeconds),
        )
        cells.chunked(2).forEach { pair ->
            grid.addView(ui.hstack(8, *pair.map { (k, v) -> cell(k, v) }.toTypedArray(), weighted = true))
        }
    }

    private fun cell(label: String, value: String): View =
        ui.vstack(4, ui.text(label, 11f, Palette.MUTED), ui.text(value, 17f, Palette.TEXT, Ui.Weight.MEDIUM)).apply {
            background = ui.rounded(Palette.SURFACE, 14, Palette.BORDER)
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
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
