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
        /** Only in the Zuki Sim test build: shown in its own Test Build section. */
        val test: Boolean = false,
        val fix: () -> Unit,
    )

    /** Extra checks the Zuki Sim test build adds; null in the real app. */
    @Volatile var testChecks: ((MainActivity) -> List<Item>)? = null

    fun items(host: MainActivity, onWake: () -> Unit): List<Item> {
        val prefs = Prefs(host)
        val bike = prefs.clusterName
        return testChecks?.invoke(host).orEmpty() + listOfNotNull(
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
                "Ride Connect Removed",
                if (host.rideConnectInstalled()) "Still installed: it can take the dash away mid-ride" else "Not installed",
                !host.rideConnectInstalled(), true, "Uninstall",
            ) { host.uninstallRideConnect() },
            Item(
                "Notifications", "Reminders, refills and the ride notification",
                host.hasNotificationPermission(), false, "Allow",
            ) { host.requestNotificationPermission() },
            Item(
                "Contacts",
                if (host.hasContactsPermission()) "Your saved names show for callers"
                else "Not allowed: callers may show their SIM's registered name instead of your saved name",
                host.hasContactsPermission(), false, "Allow",
            ) { host.requestContactsPermission() },
            Item(
                "Location All The Time", "For trip places, routes and the parked spot",
                Places.hasPermission(host), false, "Allow",
            ) { host.requestTripLocation { host.refreshScreen() } },
            if (Wake.supported(host)) {
                Item(
                    "Wake On Ignition", "Nothing runs while you're parked",
                    Wake.isSetUp(host), false, "Set Up", fix = onWake,
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
        val test = ui.listCard()
        val needed = ui.listCard()
        val optional = ui.listCard()
        val testCount = ui.text("", 11f, Palette.OK, Ui.Weight.BOLD).apply { letterSpacing = 0.08f }
        val neededCount = ui.text("", 11f, Palette.OK, Ui.Weight.BOLD).apply { letterSpacing = 0.08f }
        val optionalCount = ui.text("", 11f, Palette.ACCENT_SOFT, Ui.Weight.BOLD).apply { letterSpacing = 0.08f }
        fun fill() {
            test.removeAllViews()
            needed.removeAllViews()
            optional.removeAllViews()
            val all = items(host, onWake)
            val testToFix = all.count { it.test && !it.ok }
            testCount.text = if (testToFix == 0) "ALL DONE" else "$testToFix TO FIX"
            testCount.setTextColor(if (testToFix == 0) Palette.OK else Palette.WARN)
            val toFix = all.count { it.needed && !it.test && !it.ok }
            neededCount.text = if (toFix == 0) "ALL DONE" else "$toFix TO FIX"
            neededCount.setTextColor(if (toFix == 0) Palette.OK else Palette.WARN)
            val left = all.count { !it.needed && !it.ok }
            optionalCount.text = if (left == 0) "ALL DONE" else "$left LEFT"
            optionalCount.setTextColor(if (left == 0) Palette.OK else Palette.ACCENT_SOFT)
            for (item in all) {
                val trailing: View = if (item.ok) {
                    ui.text("✓", 16f, Palette.OK, Ui.Weight.BOLD)
                } else {
                    ui.button(item.fixLabel, if (item.needed) Ui.Btn.PRIMARY else Ui.Btn.SECONDARY, 36) { item.fix() }.apply {
                        textSize = 13f
                        setPadding(ui.dp(14), 0, ui.dp(14), 0)
                    }
                }
                val row = ui.row(item.title, item.detail, trailing)
                (if (item.test) test else if (item.needed) needed else optional).addView(
                    row,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
                )
            }
        }
        fill()
        return SettingsPage(
            host, "Setup Checklist",
            (if (testChecks != null) listOf(header(host, "TEST BUILD", testCount), test) else emptyList<View>()) + listOf(
                header(host, "NEEDED", neededCount),
                needed,
                header(host, "OPTIONAL", optionalCount),
                optional,
            ),
            showHook = { fill() },
        )
    }

    /** Section label with its count on the right, e.g. OPTIONAL ... 2 LEFT. */
    private fun header(host: MainActivity, label: String, count: View): View {
        val ui = host.ui
        return LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                ui.text(label, 11f, Palette.MUTED, Ui.Weight.BOLD).apply { letterSpacing = 0.12f },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(count)
        }
    }
}
