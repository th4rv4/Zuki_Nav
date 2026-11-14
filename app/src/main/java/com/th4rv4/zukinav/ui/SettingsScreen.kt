package com.th4rv4.zukinav.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.th4rv4.zukinav.R
import com.th4rv4.zukinav.nav.MapsIconTemplates
import com.th4rv4.zukinav.protocol.ClusterModels

/** Dash, connection and troubleshooting settings. */
class SettingsScreen(host: MainActivity) : Screen(host) {

    private val bikeName = ui.text("", 15f, Palette.TEXT, Ui.Weight.MEDIUM)
    private val bikeDetail = ui.text("", 12f, Palette.MUTED)
    private var arrowsRow: TextView? = null
    private val updates = UpdatesSection(host)
    private val riderInput = ui.input(prefs.riderName, "Your Name") { prefs.riderName = it }.apply {
        filters = arrayOf(android.text.InputFilter.LengthFilter(20))
    }


    override val view: View

    init {
        val changeBike = ui.button("Change Bike", Ui.Btn.SECONDARY, 38) { host.showSetup() }.apply {
            textSize = 13f
            setPadding(ui.dp(14), 0, ui.dp(14), 0)
        }
        val bikeRow = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(64)
            setPadding(0, ui.dp(10), 0, ui.dp(10))
            addView(ui.iconTile(R.drawable.ic_sec_bike, 40, 22, Palette.ACCENT_SOFT, Palette.ACCENT_BG, 20),
                LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { marginEnd = ui.dp(12) })
            addView(ui.vstack(2, bikeName, bikeDetail), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(changeBike)
        }
        val bike = ui.listCard(
            bikeRow,
            ui.row("Rider Name", "Shown on the dash when it connects", riderInput),
        )

        val greetingTime = ui.durationRow(prefs.greetingSeconds) { prefs.greetingSeconds = it }.apply {
            visibility = if (prefs.startupGreeting.isNotBlank()) View.VISIBLE else View.GONE
        }
        val welcome = ui.listCard(
            ui.switchRow("Startup Animation", "Spins the arrow on the dash after Welcome. Skipped during a route.", prefs.startupAnimation) {
                prefs.startupAnimation = it
            },
            ui.row("Startup Greeting", "Shown after Welcome, on the dash's call screen under \"CALLER:\"",
                ui.input(prefs.startupGreeting, "None") {
                    prefs.startupGreeting = it
                    greetingTime.visibility = if (prefs.startupGreeting.isNotBlank()) View.VISIBLE else View.GONE
                }.apply {
                    filters = arrayOf(android.text.InputFilter.LengthFilter(20))
                }),
            greetingTime,
        )

        val riding = ui.listCard(
            ui.row("Speed Alert", "km/h, empty to turn off",
                ui.input(prefs.speedAlertKmh.takeIf { it > 0 }?.toString() ?: "", "Off", numeric = true) {
                    prefs.speedAlertKmh = it.toIntOrNull()?.coerceIn(0, 999) ?: 0
                }),
            ui.row("Traffic Side", "Decides roundabout and U-turn icons",
                ui.segmented("Left", "Right", if (prefs.leftHandTraffic) 0 else 1) { prefs.leftHandTraffic = it == 0 }),
        )

        val calls = ui.listCard(
            ui.switchRow("Call Alerts", "Incoming and missed calls, phone and WhatsApp", prefs.callAlerts) {
                prefs.callAlerts = it
            },
        )

        val messages = ui.listCard(
            ui.switchRow("Message Alerts", "New WhatsApp and SMS messages", prefs.messageAlerts) {
                prefs.messageAlerts = it
            },
            ui.switchRow("Skip Business SMS", "Bank, OTP, government and promo senders, like AD-HPGOVT", prefs.skipBusinessSms) {
                prefs.skipBusinessSms = it
            },
        )

        val look = ui.card(
            ui.text("Theme", 15f, Palette.TEXT, Ui.Weight.MEDIUM),
            ui.segmented(listOf("System", "Light", "Dark", "AMOLED"), themeIndex(prefs.theme), padDp = 6, fill = true) { i ->
                host.setThemeChoice(
                    when (i) {
                        1 -> com.th4rv4.zukinav.util.Prefs.THEME_LIGHT
                        2 -> com.th4rv4.zukinav.util.Prefs.THEME_DARK
                        3 -> com.th4rv4.zukinav.util.Prefs.THEME_AMOLED
                        else -> com.th4rv4.zukinav.util.Prefs.THEME_SYSTEM
                    },
                )
            },
            ui.vstack(
                2,
                ui.text("Accent", 15f, Palette.TEXT, Ui.Weight.MEDIUM),
                ui.text(
                    if (Accents.wallpaperSupported) "Colours for the gauge, rings and buttons. Wallpaper takes them from your phone's wallpaper."
                    else "Colours for the gauge, rings and buttons",
                    12f, Palette.MUTED,
                ),
            ),
            ui.accentPicker(prefs.accent) { host.setAccentChoice(it) },
            ui.text("Fuel and warning colours stay the same in every accent.", 12f, Palette.MUTED),
            gapDp = 14,
        )

        val connection = ui.listCard(
            ui.switchRow("Auto-Connect", "Reconnect when the ignition turns on", prefs.autoConnect) {
                prefs.autoConnect = it
            },
            ui.switchRow("Start On Boot", "Keep the dash link ready after a restart", prefs.startOnBoot) {
                prefs.startOnBoot = it
            },
        )



        val reload = ui.navRow("Reload Maps Arrows", "Tap To Load") { reloadArrows() }
        arrowsRow = reload.getChildAt(reload.childCount - 1) as? TextView
        val troubleshooting = ui.listCard(
            ui.row("Dash Test", "Check turn icons and text on your dash", ui.text("›", 20f, Palette.MUTED)).apply {
                isClickable = true
                isFocusable = true
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Palette.RIPPLE), null, ui.rounded(android.graphics.Color.WHITE, 8),
                )
                setOnClickListener { host.showDashTest() }
            },
            ui.navRow("View Log") { host.showLogDialog() },
            ui.navRow("Share Log") { host.shareLog() },
            reload,
        )

        val aboutRow = ui.row(
            "About & Support", "Version, source code and Support The Developer",
            trailing = ui.text("›", 20f, Palette.MUTED),
            leading = ui.iconTile(R.drawable.ic_heart, 32, 16, Palette.ACCENT_END, Accents.withAlpha(Palette.ACCENT_END, 0x24), 10),
        ).apply {
            isClickable = true
            isFocusable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Palette.RIPPLE), null, ui.rounded(android.graphics.Color.WHITE, 8),
            )
            setOnClickListener { host.showAbout() }
        }
        // Sits in the Updates card, under Check For Updates and Check On Launch.
        (updates.views.first() as LinearLayout).addView(aboutRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        view = host.page(
            ui.title("Settings").apply { setPadding(ui.dp(4), 0, 0, 0) },
            ui.sectionHeader(R.drawable.ic_sec_bike, "Your Bike"),
            bike,
            ui.sectionHeader(R.drawable.ic_sec_wave, "Welcome"),
            welcome,
            ui.sectionHeader(R.drawable.ic_sec_gauge, "Riding"),
            riding,
            ui.text("Right-hand traffic uses roundabout icons 47 and up, which some dashes (e.g. Gixxer SF 250) leave blank.", 12f, Palette.WARN)
                .apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) },
            ui.sectionHeader(R.drawable.ic_sec_phone, "Calls"),
            calls,
            ui.sectionHeader(R.drawable.ic_sec_message, "Messages"),
            messages,
            ui.sectionHeader(R.drawable.ic_sec_palette, "Appearance"),
            look,
            ui.sectionHeader(R.drawable.ic_sec_link, "Connection"),
            connection,
            ui.sectionHeader(R.drawable.ic_sec_download, "Updates"),
            *updates.views.toTypedArray(),
            ui.sectionHeader(R.drawable.ic_sec_wrench, "Troubleshooting"),
            troubleshooting,
            ui.text("Zuki Nav ${host.versionName()} · Unofficial, not affiliated with Suzuki", 12f, Palette.MUTED)
                .apply { gravity = Gravity.CENTER },
        )
    }

    override fun onShow() {
        // Setup may have changed it since this screen was built.
        if (riderInput.text.toString().trim() != prefs.riderName) riderInput.setText(prefs.riderName)
        val name = prefs.clusterName
        updates.onShow()
        bikeName.text = if (name == null) "No Bike Chosen" else ClusterModels.fromBleName(name).displayName
        bikeDetail.text = if (name == null) "Pair your bike to get started" else "$name · Paired"
    }

    override fun onHide() {
        updates.onHide()
    }

    private fun themeIndex(t: Int) = when (t) {
        com.th4rv4.zukinav.util.Prefs.THEME_LIGHT -> 1
        com.th4rv4.zukinav.util.Prefs.THEME_DARK -> 2
        com.th4rv4.zukinav.util.Prefs.THEME_AMOLED -> 3
        else -> 0
    }

    private fun reloadArrows() {
        arrowsRow?.text = "Loading…"
        Thread {
            val n = MapsIconTemplates.matcher(host).size
            view.post { arrowsRow?.text = "$n Loaded" }
        }.start()
    }
}
