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
import com.th4rv4.zukinav.trips.Route
import com.th4rv4.zukinav.trips.RoutePoint
import com.th4rv4.zukinav.trips.RouteStore
import com.th4rv4.zukinav.trips.TripStore
import com.th4rv4.zukinav.util.Prefs

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

    // Route map: the line, its colour choice and legend, and Open In Google Maps.
    private val routeMap = RouteView(ui.ctx)
    private var route: List<RoutePoint> = emptyList()
    private val legendLow = ui.text("Slow", 11f, Palette.MUTED)
    private val legendHigh = ui.text("Fast", 11f, Palette.MUTED)
    private val legend = LinearLayout(ui.ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(legendLow)
        addView(View(ui.ctx).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, RouteView.SPEED_COLORS,
            ).apply { cornerRadius = ui.dp(3).toFloat() }
        }, LinearLayout.LayoutParams(0, ui.dp(5), 1f).apply { setMargins(ui.dp(8), 0, ui.dp(8), 0) })
        addView(legendHigh)
    }
    private lateinit var modePicker: LinearLayout
    private val routeCard: LinearLayout
    private val mapsCard: View

    // Stops: shown for merged trips.
    private val stopsDetail = ui.text("", 12f, Palette.MUTED)
    private val stopsCard: View = ui.listCard(
        ui.row("Stops", null, ui.text("›", 20f, Palette.MUTED)).apply {
            ((getChildAt(0) as? LinearLayout) ?: this).addView(stopsDetail)
            isClickable = true
            isFocusable = true
            setOnClickListener { host.showStops(id) }
        },
    )

    // Merge With Next Ride: shown when a later ride exists.
    private val mergeDetail = ui.text("", 12f, Palette.MUTED)
    private val mergeCard: View = ui.listCard(
        ui.row("Merge With Next Ride", null, ui.text("›", 20f, Palette.MUTED)).apply {
            ((getChildAt(0) as? LinearLayout) ?: this).addView(mergeDetail)
            isClickable = true
            isFocusable = true
            setOnClickListener { merge() }
        },
    )

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
        val expand = ui.text("⤢ Expand", 12f, Palette.TEXT).apply {
            background = ui.rounded(android.graphics.Color.argb(200, 11, 11, 13), 12)
            setTextColor(android.graphics.Color.rgb(221, 221, 221))
            setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5))
            isClickable = true
            isFocusable = true
            setOnClickListener { showFullMap() }
        }
        val mapFrame = android.widget.FrameLayout(ui.ctx).apply {
            addView(routeMap, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(240)))
            addView(expand, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END,
            ).apply { setMargins(0, 0, ui.dp(10), ui.dp(10)) })
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, ui.dp(14).toFloat())
                }
            }
        }
        modePicker = ui.segmented(listOf("Speed", "Engine Temp", "Plain"), prefs.routeColours, fill = true) {
            prefs.routeColours = it
            showRoute()
        }
        val shareImage = ui.button("Share Image", Ui.Btn.SECONDARY, 44) { trip()?.let { ShareSheet(host, it, route).show() } }
        routeCard = ui.card(mapFrame, modePicker, legend, shareImage, gapDp = 10, padDp = 10)
        mapsCard = ui.listCard(ui.row("Open In Google Maps", "The route as a path, through up to 8 stops", ui.text("›", 20f, Palette.MUTED)).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { openInMaps() }
        })
        view = host.page(
            header,
            hero,
            routeCard,
            stopsCard,
            grid,
            odoCard,
            mapsCard,
            mergeCard,
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

    private fun merge() {
        val t = trip() ?: return
        val next = com.th4rv4.zukinav.trips.TripMerge.next(t, TripStore.all(host)) ?: return
        val is24 = DateFormat.is24HourFormat(host)
        val gapMin = ((next.start - t.end) / 60_000).coerceAtLeast(0)
        android.app.AlertDialog.Builder(host, host.dialogTheme)
            .setTitle("Merge These Rides?")
            .setMessage(
                "${TripFormat.title(t)}, ${TripFormat.time(t.start, is24)}\n" +
                    "+ ${TripFormat.title(next)}, ${TripFormat.time(next.start, is24)}\n\n" +
                    "They become one trip: distances and riding time are added up, the routes join into one map, " +
                    "and the ${TripFormat.shortDuration(gapMin * 60)} in between counts as a stop. This can't be undone.",
            )
            .setPositiveButton("Merge") { _, _ ->
                if (TripStore.mergeWithNext(host, t.id) != null) {
                    host.toast("Rides merged")
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun trip(): Trip? = TripStore.all(host).firstOrNull { it.id == id }

    private fun render() {
        val t = trip() ?: run { host.showTab(MainActivity.Tab.TRIPS); return }
        val is24 = DateFormat.is24HourFormat(host)
        title.text = TripFormat.title(t)
        star.text = if (t.starred) "★" else "☆"
        star.setTextColor(if (t.starred) Palette.WARN else Palette.MUTED)
        star.contentDescription = if (t.starred) "Unstar" else "Star"
        span.text = TripFormat.span(t, is24)
        stopsCard.visibility = if (t.stops > 0) View.VISIBLE else View.GONE
        stopsDetail.text = listOfNotNull(
            TripFormat.stops(t),
            t.stopList.mapIndexed { i, s -> TripFormat.stopName(s, i) }.takeIf { it.isNotEmpty() }?.joinToString(", "),
        ).joinToString(" · ")
        val next = com.th4rv4.zukinav.trips.TripMerge.next(t, TripStore.all(host))
        mergeCard.visibility = if (next == null) View.GONE else View.VISIBLE
        if (next != null) {
            val gapMin = ((next.start - t.end) / 60_000).coerceAtLeast(0)
            mergeDetail.text = "${TripFormat.title(next)} · ${TripFormat.time(next.start, is24)}, " +
                TripFormat.shortDuration(gapMin * 60) + " later"
        }

        val km = TripFormat.distance(t.distanceKm)
        distance.text = km
        distance.post { paintDistance() }
        placesRow.visibility = if (t.startPlace != null || t.endPlace != null) View.VISIBLE else View.GONE
        from.text = t.startPlace ?: "Unknown"
        to.text = t.endPlace ?: "Unknown"

        grid.removeAllViews()
        val cells = listOf(
            "Moving Time" to TripFormat.duration(t.movingSeconds),
            "Stopped" to TripFormat.duration(t.ridingSeconds - t.movingSeconds),
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

        route = RouteStore.points(host, t)
        val has = route.size >= 2
        routeCard.visibility = if (has) View.VISIBLE else View.GONE
        mapsCard.visibility = if (has) View.VISIBLE else View.GONE
        showRoute()
    }

    private fun showRoute() {
        val mode = prefs.routeColours
        routeMap.set(route, mode, prefs.engineWarmC, prefs.engineHotC)
        routeMap.stops = trip()?.stopList?.mapNotNull { s -> if (s.lat != null && s.lng != null) s.lat to s.lng else null } ?: emptyList()
        val engine = mode == Prefs.ROUTE_ENGINE && routeMap.hasEngine()
        legend.visibility = if (mode == Prefs.ROUTE_SPEED || engine) View.VISIBLE else View.GONE
        legendLow.text = if (engine) "Cold" else "Slow"
        legendHigh.text = if (engine) "Hot" else "Fast"
    }

    /** The route full screen, with pinch to zoom, drag to move and double-tap to reset. */
    private fun showFullMap() {
        val full = RouteView(ui.ctx, zoomable = true).apply { set(route, prefs.routeColours, prefs.engineWarmC, prefs.engineHotC) }
        val dialog = android.app.Dialog(host, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val close = ui.text("✕", 22f, android.graphics.Color.WHITE).apply {
            background = ui.rounded(android.graphics.Color.argb(200, 11, 11, 13), 22)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = "Close map"
            setOnClickListener { dialog.dismiss() }
        }
        val hint = ui.text("Pinch to zoom · double-tap to reset", 12f, android.graphics.Color.rgb(160, 160, 170))
        dialog.setContentView(android.widget.FrameLayout(ui.ctx).apply {
            addView(full, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(close, android.widget.FrameLayout.LayoutParams(ui.dp(44), ui.dp(44), Gravity.TOP or Gravity.END)
                .apply { setMargins(0, ui.dp(40), ui.dp(16), 0) })
            addView(hint, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ).apply { setMargins(0, 0, 0, ui.dp(32)) })
        })
        dialog.show()
    }

    private fun openInMaps() {
        val url = Route.googleMapsUrl(route) ?: return
        try {
            host.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) {
            host.toast("No app can open Google Maps links")
        }
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

/** Share Image: a live preview of the PNG, what goes on it, then Save or Share. */
class ShareSheet(private val host: MainActivity, private val trip: Trip, private val route: List<RoutePoint>) {
    private val ui = host.ui
    private val prefs = host.prefs
    private val preview = android.widget.ImageView(ui.ctx).apply {
        adjustViewBounds = true
        maxHeight = ui.dp(340)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        contentDescription = "Preview of the image"
    }
    private val statRows = ArrayList<View>()

    private fun options() = ShareImage.Options(
        background = prefs.shareBackground,
        line = prefs.shareLine,
        showStats = prefs.shareShows("stats", true),
        stats = ShareImage.STATS.filter { prefs.shareShows(it.key, it.default) }.map { it.key }.toSet(),
        showName = prefs.shareShows("name", true),
    )

    private fun refresh() {
        val o = options()
        preview.setImageBitmap(ShareImage.render(trip, route, o, scale = 0.4f))
        preview.background = when (o.background) {
            ShareImage.BG_TRANSPARENT -> ui.rounded(android.graphics.Color.rgb(58, 62, 74), 14)
            else -> null
        }
        statRows.forEach { it.alpha = if (o.showStats) 1f else 0.4f }
    }

    fun show() {
        val views = ArrayList<View>()
        views += ui.text("Share Image", 18f, Palette.TEXT, Ui.Weight.BOLD)
        views += preview
        views += ui.sectionLabel("Background")
        views += ui.segmented(listOf("Transparent", "Dark", "Light"), prefs.shareBackground, fill = true) {
            prefs.shareBackground = it
            refresh()
        }
        views += ui.sectionLabel("Line")
        views += ui.segmented(listOf("Accent", "Speed", "White"), prefs.shareLine, fill = true) {
            prefs.shareLine = it
            refresh()
        }
        views += ui.sectionLabel("Stats On The Image")
        val rows = ArrayList<View>()
        rows += ui.switchRow("Show Stats", null, prefs.shareShows("stats", true)) {
            prefs.setShareShows("stats", it)
            refresh()
        }
        ShareImage.STATS.forEach { s ->
            val available = ShareImage.value(trip, s.key) != null
            val row = ui.switchRow(s.label, if (available) null else "Not recorded for this ride", prefs.shareShows(s.key, s.default)) {
                prefs.setShareShows(s.key, it)
                refresh()
            }
            row.setPadding(row.paddingLeft + ui.dp(12), row.paddingTop, row.paddingRight, row.paddingBottom)
            statRows += row
            rows += row
        }
        rows += ui.switchRow("Trip Name", "With ZUKI NAV at the bottom", prefs.shareShows("name", true)) {
            prefs.setShareShows("name", it)
            refresh()
        }
        views += ui.listCard(*rows.toTypedArray())

        lateinit var dialog: android.app.Dialog
        val save = ui.button("Save", Ui.Btn.SECONDARY, 48) {
            val ok = ShareImage.save(host, ShareImage.render(trip, route, options()), "zuki-nav-${trip.id}")
            host.toast(if (ok) "Saved to Pictures/Zuki Nav" else "Couldn't save the image")
        }
        val share = ui.button("Share", Ui.Btn.PRIMARY, 48) {
            if (!ShareImage.share(host, ShareImage.render(trip, route, options()))) host.toast("Couldn't make the image")
            else dialog.dismiss()
        }
        views += ui.hstack(10, save, share, weighted = true)
        refresh()
        dialog = ui.sheet(*views.toTypedArray())
    }
}
