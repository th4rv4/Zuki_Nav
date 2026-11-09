package com.th4rv4.zukinav.ui

import android.view.View
import android.widget.TextView
import com.th4rv4.zukinav.nav.MapsIconTemplates
import com.th4rv4.zukinav.protocol.ClusterModels

/** Dash, connection and troubleshooting settings. */
class SettingsScreen(host: MainActivity) : Screen(host) {

    private val subtitle = ui.text("", 13f, Palette.MUTED)
    private var arrowsRow: TextView? = null
    private val riderInput = ui.input(prefs.riderName, "Your Name") { prefs.riderName = it }.apply {
        filters = arrayOf(android.text.InputFilter.LengthFilter(20))
    }

    override val view: View

    init {
        val dash = ui.listCard(
            ui.row("Rider Name", "Shown on the dash when it connects",
                riderInput),
            ui.row("Speed Alert", "km/h, empty to turn off",
                ui.input(prefs.speedAlertKmh.takeIf { it > 0 }?.toString() ?: "", "Off", numeric = true) {
                    prefs.speedAlertKmh = it.toIntOrNull()?.coerceIn(0, 999) ?: 0
                }),
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
            ui.sectionLabel("Connection"),
            connection,
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
        subtitle.text = if (name == null) "No bike chosen" else "${ClusterModels.fromBleName(name).displayName} · $name"
    }

    private fun reloadArrows() {
        arrowsRow?.text = "Loading…"
        Thread {
            val n = MapsIconTemplates.matcher(host).size
            view.post { arrowsRow?.text = "$n Loaded" }
        }.start()
    }
}
