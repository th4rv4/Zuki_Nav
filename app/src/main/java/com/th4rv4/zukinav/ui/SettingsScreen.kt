package com.th4rv4.zukinav.ui

import android.view.View
import android.widget.Switch
import android.widget.TextView
import com.th4rv4.zukinav.nav.MapsIconTemplates
import com.th4rv4.zukinav.protocol.ClusterModels
import com.th4rv4.zukinav.trips.TripLog
import com.th4rv4.zukinav.util.Places

/** Dash, connection and troubleshooting settings. */
class SettingsScreen(host: MainActivity) : Screen(host) {

    private val subtitle = ui.text("", 13f, Palette.MUTED)
    private var arrowsRow: TextView? = null
    private val updates = UpdatesSection(host)
    private val riderInput = ui.input(prefs.riderName, "Your Name") { prefs.riderName = it }.apply {
        filters = arrayOf(android.text.InputFilter.LengthFilter(20))
    }

    private val placesRow = ui.switchRow(
        "Start And End Places",
        "Names where each ride starts and ends. Needs location, allowed all the time.",
        prefs.tripPlaces && Places.hasPermission(host),
    ) { on -> setTripPlaces(on) }
    private val placesSwitch = placesRow.getChildAt(placesRow.childCount - 1) as Switch
    private var syncingPlaces = false

    override val view: View

    init {
        val dash = ui.listCard(
            ui.row("Rider Name", "Shown on the dash when it connects",
                riderInput),
            ui.row("Speed Alert", "km/h, empty to turn off",
                ui.input(prefs.speedAlertKmh.takeIf { it > 0 }?.toString() ?: "", "Off", numeric = true) {
                    prefs.speedAlertKmh = it.toIntOrNull()?.coerceIn(0, 999) ?: 0
                }),
            ui.switchRow("Call Alerts", "Incoming and missed calls, phone and WhatsApp", prefs.callAlerts) {
                prefs.callAlerts = it
            },
            ui.switchRow("Message Alerts", "New WhatsApp and SMS messages", prefs.messageAlerts) {
                prefs.messageAlerts = it
            },
            ui.row("Traffic Side", "Decides roundabout and U-turn icons",
                ui.segmented("Left", "Right", if (prefs.leftHandTraffic) 0 else 1) { prefs.leftHandTraffic = it == 0 }),
        )

        val connection = ui.listCard(
            ui.switchRow("Auto-Connect", "Reconnect when the ignition turns on", prefs.autoConnect) {
                prefs.autoConnect = it
            },
            ui.switchRow("Start On Boot", "Keep the dash link ready after a restart", prefs.startOnBoot) {
                prefs.startOnBoot = it
            },
            ui.navRow("Change Bike") { host.showSetup() },
        )

        val trips = ui.listCard(
            ui.switchRow("Record Trips", "Save each ride, ignition on to ignition off", prefs.recordTrips) {
                prefs.recordTrips = it
                if (!it) TripLog.stopRecording(host)
            },
            ui.switchRow("Skip Short Rides", "Don't save rides under ${(TripLog.SHORT_RIDE_KM * 1000).toInt()} m", prefs.skipShortTrips) {
                prefs.skipShortTrips = it
            },
            placesRow,
        )

        val reload = ui.navRow("Reload Maps Arrows", "Tap To Load") { reloadArrows() }
        arrowsRow = reload.getChildAt(reload.childCount - 1) as? TextView
        val troubleshooting = ui.listCard(
            ui.navRow("View Log") { host.showLogDialog() },
            ui.navRow("Share Log") { host.shareLog() },
            reload,
        )

        view = host.page(
            ui.vstack(2, ui.title("Settings"), subtitle),
            ui.sectionLabel("Dash"),
            dash,
            ui.text("Right-hand traffic uses roundabout icons 47 and up, which some dashes (e.g. Gixxer SF 250) leave blank.", 12f, Palette.WARN)
                .apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) },
            ui.sectionLabel("Trips"),
            trips,
            ui.sectionLabel("Connection"),
            connection,
            *updates.views.toTypedArray(),
            ui.sectionLabel("Troubleshooting"),
            troubleshooting,
            ui.text("Zuki Nav ${host.versionName()} · Unofficial, not affiliated with Suzuki", 12f, Palette.MUTED)
                .apply { gravity = android.view.Gravity.CENTER },
        )
    }

    override fun onShow() {
        // Setup may have changed it since this screen was built.
        if (riderInput.text.toString().trim() != prefs.riderName) riderInput.setText(prefs.riderName)
        val name = prefs.clusterName
        updates.onShow()
        // Location may have been taken away in Android's settings.
        if (!host.askingLocation) showPlaces(prefs.tripPlaces && Places.hasPermission(host))
        subtitle.text = if (name == null) "No bike chosen" else "${ClusterModels.fromBleName(name).displayName} · $name"
    }

    override fun onHide() {
        updates.onHide()
    }

    private fun setTripPlaces(on: Boolean) {
        if (syncingPlaces) return
        if (!on) { prefs.tripPlaces = false; return }
        host.requestTripLocation { granted ->
            prefs.tripPlaces = granted
            showPlaces(granted)
            if (!granted) host.toast("Start And End Places needs location allowed all the time")
        }
    }

    private fun showPlaces(on: Boolean) {
        if (placesSwitch.isChecked == on) return
        syncingPlaces = true
        placesSwitch.isChecked = on
        syncingPlaces = false
    }

    private fun reloadArrows() {
        arrowsRow?.text = "Loading…"
        Thread {
            val n = MapsIconTemplates.matcher(host).size
            view.post { arrowsRow?.text = "$n Loaded" }
        }.start()
    }
}
