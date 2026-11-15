package com.th4rv4.zukinav.ui

import android.view.View
import com.th4rv4.zukinav.trips.MonthRecap
import com.th4rv4.zukinav.trips.RoutePoint
import com.th4rv4.zukinav.trips.RouteStore
import java.time.format.TextStyle
import java.util.Locale

/** Share sheet for a month recap: preview, background, which stats, and the route doodles. */
class RecapSheet(private val host: MainActivity, private val recap: MonthRecap) {

    private val ui = host.ui
    private val prefs = host.prefs
    private var routes: List<List<RoutePoint>> = emptyList()
    private val preview = android.widget.ImageView(ui.ctx).apply {
        adjustViewBounds = true
        maxHeight = ui.dp(340)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        contentDescription = "Preview of the recap image"
    }

    private fun stats() = RecapImage.STATS.filter { prefs.shareShows("recap_" + it.key, it.default) }.map { it.key }.toSet()
    private fun showRoutes() = prefs.shareShows("recap_routes", true)

    private fun image(scale: Float = 1f) = RecapImage.render(recap, routes, prefs.shareBackground, stats(), showRoutes(), scale)

    private fun refresh() {
        preview.setImageBitmap(image(0.4f))
        preview.background = if (prefs.shareBackground == ShareImage.BG_TRANSPARENT) {
            ui.rounded(android.graphics.Color.rgb(58, 62, 74), 14)
        } else null
    }

    fun show() {
        // Routes are read from files: off the main thread, then the sheet opens.
        Thread {
            val withRoutes = recap.trips.filter { RouteStore.has(host, it.id) }.take(RecapImage.MAX_ROUTES)
            val loaded = withRoutes.map { RouteStore.points(host, it) }
            host.runOnUiThread {
                routes = loaded
                open()
            }
        }.start()
    }

    private fun open() {
        val month = recap.month.month.getDisplayName(TextStyle.FULL, Locale.US)
        val views = ArrayList<View>()
        views += ui.text("$month Recap", 18f, Palette.TEXT, Ui.Weight.BOLD)
        views += preview
        views += ui.sectionLabel("Background")
        views += ui.segmented(listOf("Transparent", "Dark", "Light"), prefs.shareBackground, fill = true) {
            prefs.shareBackground = it
            refresh()
        }
        views += ui.sectionLabel("On The Image")
        val rows = ArrayList<View>()
        RecapImage.STATS.forEach { s ->
            rows += ui.switchRow(s.label, null, prefs.shareShows("recap_" + s.key, s.default)) {
                prefs.setShareShows("recap_" + s.key, it)
                refresh()
            }
        }
        rows += ui.switchRow(
            "Route Doodles",
            if (routes.isEmpty()) "No routes recorded this month" else "Your ${routes.size} longest rides with a route",
            showRoutes(),
        ) {
            prefs.setShareShows("recap_routes", it)
            refresh()
        }
        views += ui.listCard(*rows.toTypedArray())

        lateinit var dialog: android.app.Dialog
        val save = ui.button("Save", Ui.Btn.SECONDARY, 48) {
            val ok = ShareImage.save(host, image(), "zuki-nav-${recap.month}")
            host.toast(if (ok) "Saved to Pictures/Zuki Nav" else "Couldn't save the image")
        }
        val share = ui.button("Share", Ui.Btn.PRIMARY, 48) {
            if (!ShareImage.share(host, image())) host.toast("Couldn't make the image") else dialog.dismiss()
        }
        views += ui.hstack(10, save, share, weighted = true)
        refresh()
        dialog = ui.sheet(*views.toTypedArray())
    }
}
