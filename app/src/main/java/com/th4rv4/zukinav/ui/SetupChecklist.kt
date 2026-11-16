package com.th4rv4.zukinav.ui

import android.view.View
import android.widget.LinearLayout
import com.th4rv4.zukinav.protocol.ClusterModels
import com.th4rv4.zukinav.service.Wake
import com.th4rv4.zukinav.util.Places
import com.th4rv4.zukinav.util.Prefs

/**
 * Everything the app needs to work, and the extras that make it better, each
 * with whether it is in place and a one-tap way to fix it.
 */
object SetupChecklist {

    class Item(
        val title: String,
        val detail: String,
        val ok: Boolean,
        val needed: Boolean,
        val fixLabel: String,
        val fix: () -> Unit,
    )

    fun items(host: MainActivity, onWake: () -> Unit): List<Item> {
        val prefs = Prefs(host)
        val bike = prefs.clusterName
        return listOfNotNull(
            Item(
                "Bluetooth", if (host.bluetoothOn()) "On" else "Off, so the dash can't connect",
                host.bluetoothOn(), true, "Turn On",
            ) { host.turnOnBluetooth() },
            Item(
                "Nearby Devices", if (host.hasBlePermissions()) "Allowed, to talk to the dash" else "Needed to talk to the dash",
                host.hasBlePermissions(), true, "Allow",
            ) { host.requestBlePermissions() },
            Item(
                "Notification Access", "To read Google Maps, calls and messages. Other apps are ignored.",
                host.hasListenerAccess(), true, "Allow",
            ) { host.openListenerSettings() },
            Item(
                "Battery Unrestricted",
                if (host.batteryUnrestricted()) "Android won't stop the app mid-ride" else "Android may stop the app mid-ride",
                host.batteryUnrestricted(), true, "Fix",
            ) { host.askBatteryUnrestricted() },
            Item(
                "Bike Paired", bike?.let { ClusterModels.fromBleName(it).displayName } ?: "No bike chosen yet",
                prefs.clusterAddress != null, true, "Set Up",
            ) { host.showSetup() },
            Item(
                "Notifications", "Reminders, refills and the ride notification",
                host.hasNotificationPermission(), false, "Allow",
            ) { host.requestNotificationPermission() },
            Item(
                "Location All The Time", "For trip places, routes and the parked spot",
                Places.hasPermission(host), false, "Allow",
            ) { host.requestTripLocation { host.refreshScreen() } },
            if (Wake.supported(host)) {
                Item(
                    "Wake On Ignition", "Nothing runs while you're parked",
                    Wake.isSetUp(host), false, "Set Up", onWake,
                )
            } else {
                null
            },
        )
    }

    /** The needed items that are not in place. */
    fun problems(host: MainActivity): List<Item> = items(host) {}.filter { it.needed && !it.ok }

    /** The Setup Checklist page; it refreshes each time it is shown, e.g. back from Android's settings. */
    fun page(host: MainActivity, onWake: () -> Unit): SettingsPage {
        val ui = host.ui
        val needed = ui.listCard()
        val optional = ui.listCard()
        fun fill() {
            needed.removeAllViews()
            optional.removeAllViews()
            for (item in items(host, onWake)) {
                val trailing: View = if (item.ok) {
                    ui.text("✓", 16f, Palette.OK, Ui.Weight.BOLD)
                } else {
                    ui.button(item.fixLabel, if (item.needed) Ui.Btn.PRIMARY else Ui.Btn.SECONDARY, 36) { item.fix() }.apply {
                        textSize = 13f
                        setPadding(ui.dp(14), 0, ui.dp(14), 0)
                    }
                }
                val row = ui.row(item.title, item.detail, trailing)
                (if (item.needed) needed else optional).addView(
                    row,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
                )
            }
        }
        fill()
        return SettingsPage(
            host, "Setup Checklist",
            listOf(
                ui.text("NEEDED", 11f, Palette.MUTED, Ui.Weight.BOLD).apply { letterSpacing = 0.12f },
                needed,
                ui.text("OPTIONAL", 11f, Palette.MUTED, Ui.Weight.BOLD).apply { letterSpacing = 0.12f },
                optional,
            ),
            showHook = { fill() },
        )
    }
}
