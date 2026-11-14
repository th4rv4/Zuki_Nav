package com.th4rv4.zukinav.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.th4rv4.zukinav.R
import com.th4rv4.zukinav.nav.MapsIconTemplates
import com.th4rv4.zukinav.nav.NavRepository
import com.th4rv4.zukinav.ble.ClusterLink
import com.th4rv4.zukinav.protocol.ClusterModels
import com.th4rv4.zukinav.service.DashService
import com.th4rv4.zukinav.util.Prefs

/**
 * Settings as a short menu: the rider card, then one row per topic. Most
 * rows open their own page (see [makePage]); Connect Automatically is a
 * switch right here.
 */
class SettingsScreen(host: MainActivity) : Screen(host) {

    private val avatar = AvatarView(ui.ctx)
    private val riderName = ui.text("", 16f, Palette.TEXT, Ui.Weight.MEDIUM)
    private val riderDetail = ui.text("", 12f, Palette.MUTED)
    private val summaries = HashMap<String, TextView>()

    override val view: View

    init {
        val riderCard = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.pressable(Palette.SURFACE, 20)
            setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
            isClickable = true
            isFocusable = true
            addView(avatar, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { marginEnd = ui.dp(12) })
            addView(ui.vstack(2, riderName, riderDetail), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.text("›", 20f, Palette.MUTED))
            setOnClickListener { host.showSettingsPage(PAGE_RIDER) }
        }

        val connect = ui.switchRow(
            "Connect Automatically", "On ignition and after the phone restarts",
            prefs.autoConnect,
        ) {
            prefs.autoConnect = it
            prefs.startOnBoot = it
        }
        connect.addView(icon(R.drawable.ic_set_bluetooth), 0, iconParams())

        view = host.page(
            ui.title("Settings").apply { setPadding(ui.dp(4), 0, 0, 0) },
            riderCard,
            ui.listCard(
                menuRow(PAGE_DASH, R.drawable.ic_sec_gauge, "Dash"),
                menuRow(PAGE_CALLS, R.drawable.ic_set_dialer, "Calls"),
                menuRow(PAGE_MESSAGES, R.drawable.ic_sec_message, "Messages"),
            ),
            ui.listCard(
                menuRow(PAGE_APPEARANCE, R.drawable.ic_sec_palette, "Appearance"),
                connect,
                menuRow(PAGE_UPDATES, R.drawable.ic_sec_download, "Updates"),
            ),
            ui.listCard(
                menuRow(PAGE_TROUBLESHOOTING, R.drawable.ic_sec_wrench, "Troubleshooting"),
                menuRow(null, R.drawable.ic_heart, "About & Support") { host.showAbout() },
            ),
            ui.text("Zuki Nav ${host.versionName()} · Unofficial, not affiliated with Suzuki", 12f, Palette.MUTED)
                .apply { gravity = Gravity.CENTER },
        )
    }

    override fun onShow() {
        avatar.show(prefs.avatar, prefs.riderName)
        riderName.text = prefs.riderName.ifBlank { "Rider" }
        val bike = prefs.clusterName
        riderDetail.text = if (bike == null) "No bike chosen · tap to set up" else ClusterModels.fromBleName(bike).displayName
        summaries[PAGE_DASH]?.text = listOfNotNull(
            prefs.speedAlertKmh.takeIf { it > 0 }?.let { "Speed alert $it" } ?: "Speed alert off",
            "animation".takeIf { prefs.startupAnimation },
            "greeting".takeIf { prefs.startupGreeting.isNotBlank() },
        ).joinToString(" · ")
        summaries[PAGE_CALLS]?.text = listOfNotNull(
            if (prefs.callAlerts) "Alerts on" else "Alerts off",
            "rejecting unknown".takeIf { prefs.rejectUnknown },
        ).joinToString(" · ")
        summaries[PAGE_MESSAGES]?.text = if (prefs.messageAlerts) "On · WhatsApp and SMS" else "Off"
        summaries[PAGE_APPEARANCE]?.text = themeName(prefs.theme) + " · " + Accents.name(prefs.accent)
        summaries[PAGE_UPDATES]?.text = "Installed v${host.versionName()}"
        summaries[PAGE_TROUBLESHOOTING]?.text = "Dash Test, arrows, logs"
    }

    /** One menu row: icon, title, a short summary of what's set, and ›. */
    private fun menuRow(page: String?, iconRes: Int, title: String, onClick: (() -> Unit)? = null): View {
        val summary = ui.text("", 12f, Palette.MUTED)
        if (page != null) summaries[page] = summary
        return LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(60)
            setPadding(0, ui.dp(8), 0, ui.dp(8))
            isClickable = true
            isFocusable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Palette.RIPPLE), null, ui.rounded(android.graphics.Color.WHITE, 8),
            )
            addView(icon(iconRes), iconParams())
            addView(
                ui.vstack(2, ui.text(title, 15f, Palette.TEXT, Ui.Weight.MEDIUM)).apply { if (page != null) addView(summary) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(ui.text("›", 20f, Palette.MUTED))
            setOnClickListener { if (onClick != null) onClick() else if (page != null) host.showSettingsPage(page) }
        }
    }

    private fun icon(res: Int) = ui.iconTile(res, 32, 17, Palette.ACCENT_SOFT, Palette.ACCENT_BG, 10)

    private fun iconParams() = LinearLayout.LayoutParams(ui.dp(32), ui.dp(32)).apply { marginEnd = ui.dp(12) }

    private fun themeName(t: Int) = when (t) {
        Prefs.THEME_LIGHT -> "Light"
        Prefs.THEME_DARK -> "Dark"
        Prefs.THEME_AMOLED -> "AMOLED"
        else -> "System"
    }

    // ---- pages ------------------------------------------------------------

    /** Builds one Settings page. */
    fun makePage(key: String): SettingsPage = when (key) {
        PAGE_RIDER -> riderPage()
        PAGE_DASH -> dashPage()
        PAGE_CALLS -> callsPage()
        PAGE_MESSAGES -> messagesPage()
        PAGE_APPEARANCE -> appearancePage()
        PAGE_UPDATES -> updatesPage()
        else -> troubleshootingPage()
    }

    private fun riderPage(): SettingsPage {
        val bikeName = ui.text("", 15f, Palette.TEXT, Ui.Weight.MEDIUM)
        val bikeDetail = ui.text("", 12f, Palette.MUTED)
        val riderInput = ui.input(prefs.riderName, "Your Name") { prefs.riderName = it }.apply {
            filters = arrayOf(android.text.InputFilter.LengthFilter(20))
        }
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
        return SettingsPage(
            host, "Rider & Bike",
            listOf(ui.listCard(ui.row("Rider Name", "Shown on the dash when it connects", riderInput), bikeRow)),
            showHook = {
                // Setup may have changed these since the page was built.
                if (riderInput.text.toString().trim() != prefs.riderName) riderInput.setText(prefs.riderName)
                val name = prefs.clusterName
                bikeName.text = if (name == null) "No Bike Chosen" else ClusterModels.fromBleName(name).displayName
                bikeDetail.text = if (name == null) "Pair your bike to get started" else "$name · Paired"
            },
        )
    }

    private fun dashPage(): SettingsPage {
        val greetingTime = ui.durationRow(prefs.greetingSeconds) { prefs.greetingSeconds = it }.apply {
            visibility = if (prefs.startupGreeting.isNotBlank()) View.VISIBLE else View.GONE
        }
        return SettingsPage(
            host, "Dash",
            listOf(
                ui.listCard(
                    ui.row("Speed Alert", "The dash warns above this, km/h. Empty to turn off.",
                        ui.input(prefs.speedAlertKmh.takeIf { it > 0 }?.toString() ?: "", "Off", numeric = true) {
                            prefs.speedAlertKmh = it.toIntOrNull()?.coerceIn(0, 999) ?: 0
                        }),
                ),
                ui.sectionHeader(R.drawable.ic_sec_wave, "Welcome"),
                ui.listCard(
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
                ),
            ),
        )
    }

    private fun callsPage(): SettingsPage {
        val extras = ArrayList<View>()
        fun showExtras(on: Boolean) = extras.forEach { it.visibility = if (on) View.VISIBLE else View.GONE }
        val whenRow = ui.vstack(
            8,
            ui.text("When", 15f, Palette.TEXT, Ui.Weight.MEDIUM),
            ui.segmented(listOf("While Riding", "Always"), if (prefs.rejectAlways) 1 else 0, fill = true) {
                prefs.rejectAlways = it == 1
            },
            ui.text("While Riding means while the bike is connected.", 12f, Palette.MUTED),
        ).apply { setPadding(0, ui.dp(10), 0, ui.dp(12)) }
        val repeat = ui.switchRow(
            "Allow Repeat Callers", "A second call from the same number within 5 min rings through, in case it's urgent",
            prefs.rejectAllowRepeat,
        ) { prefs.rejectAllowRepeat = it }
        val onDash = ui.switchRow("Show On Dash", "Shows REJECTED CALL for 3 s, except during a route", prefs.rejectShowOnDash) {
            prefs.rejectShowOnDash = it
        }
        extras.addAll(listOf(whenRow, repeat, onDash))
        showExtras(prefs.rejectUnknown)
        return SettingsPage(
            host, "Calls",
            listOf(
                ui.listCard(
                    ui.switchRow("Call Alerts", "Incoming and missed calls on the dash, phone and WhatsApp", prefs.callAlerts) {
                        prefs.callAlerts = it
                    },
                ),
                ui.sectionHeader(R.drawable.ic_set_dialer, "Unknown Callers"),
                ui.listCard(
                    ui.switchRow(
                        "Reject Unknown Callers",
                        "Phone calls from numbers not in your contacts are declined straight away. They still show as missed calls.",
                        prefs.rejectUnknown,
                    ) {
                        prefs.rejectUnknown = it
                        showExtras(it)
                    },
                    whenRow,
                    repeat,
                    onDash,
                ),
                ui.infoNote("How Unknown Callers Are Spotted") {
                    host.showInfo(
                        "Unknown Callers",
                        "Zuki Nav goes by your phone app's incoming-call notification: a saved contact shows a name, " +
                            "anyone else a number, Unknown or Private number. It then presses the notification's own " +
                            "Decline button, so it doesn't replace your caller ID app.\n\nIt needs a phone app with a Decline " +
                            "button on that notification, as on most phones with Android 12 or newer. WhatsApp calls aren't " +
                            "affected. If a caller ID app shows a name for a number you haven't saved, that call rings through.",
                    )
                },
            ),
        )
    }

    private fun messagesPage(): SettingsPage = SettingsPage(
        host, "Messages",
        listOf(
            ui.listCard(
                ui.switchRow("Message Alerts", "New WhatsApp and SMS messages on the dash", prefs.messageAlerts) {
                    prefs.messageAlerts = it
                },
                ui.switchRow("Skip Business SMS", "Bank, OTP, government and promo senders, like AD-HPGOVT", prefs.skipBusinessSms) {
                    prefs.skipBusinessSms = it
                },
            ),
        ),
    )

    private fun appearancePage(): SettingsPage = SettingsPage(
        host, "Appearance",
        listOf(
            ui.card(
                ui.text("Theme", 15f, Palette.TEXT, Ui.Weight.MEDIUM),
                ui.segmented(listOf("System", "Light", "Dark", "AMOLED"), themeIndex(prefs.theme), padDp = 6, fill = true) { i ->
                    host.setThemeChoice(
                        when (i) {
                            1 -> Prefs.THEME_LIGHT
                            2 -> Prefs.THEME_DARK
                            3 -> Prefs.THEME_AMOLED
                            else -> Prefs.THEME_SYSTEM
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
            ),
        ),
    )

    private fun updatesPage(): SettingsPage {
        val updates = UpdatesSection(host)
        return SettingsPage(host, "Updates", updates.views, showHook = { updates.onShow() }, hideHook = { updates.onHide() })
    }

    private fun troubleshootingPage(): SettingsPage {
        val arrowStatus = ui.text("", 13f, Palette.MUTED)
        val reload = ui.navRow("Reload Maps Arrows", "Tap To Load") {}
        val reloadValue = reload.getChildAt(reload.childCount - 1) as? TextView
        reload.setOnClickListener {
            reloadValue?.text = "Loading…"
            Thread {
                val n = MapsIconTemplates.matcher(host).size
                reload.post { reloadValue?.text = "$n Loaded" }
            }.start()
        }
        val dashTest = ui.row("Dash Test", "Check turn icons and text on your dash", ui.text("›", 20f, Palette.MUTED)).apply {
            isClickable = true
            isFocusable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Palette.RIPPLE), null, ui.rounded(android.graphics.Color.WHITE, 8),
            )
            setOnClickListener { host.showDashTest() }
        }
        return SettingsPage(
            host, "Troubleshooting",
            listOf(
                ui.listCard(
                    dashTest,
                    ui.row("Arrow Status", "What Zuki Nav is sending to the dash right now", arrowStatus),
                    ui.row("Traffic Side", "Decides roundabout and U-turn icons",
                        ui.segmented("Left", "Right", if (prefs.leftHandTraffic) 0 else 1) { prefs.leftHandTraffic = it == 0 }),
                    reload,
                    ui.navRow("View Log") { host.showLogDialog() },
                    ui.navRow("Share Log") { host.shareLog() },
                ),
                ui.text("Right-hand traffic uses roundabout icons 47 and up, which some dashes (e.g. Gixxer SF 250) leave blank.", 12f, Palette.WARN)
                    .apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) },
            ),
            showHook = {
                val nav = NavRepository.current
                val ready = DashService.linkState == ClusterLink.State.READY
                arrowStatus.text = when {
                    !ready -> "Not Connected"
                    nav == null -> "Clock Only"
                    else -> "Sending · " + when (nav.iconSource) {
                        "maps-icon" -> "Maps Arrow"
                        "text" -> "Instruction Text"
                        "previous" -> "Previous Turn"
                        else -> "None"
                    }
                }
                arrowStatus.setTextColor(if (ready) Palette.OK else Palette.MUTED)
            },
        )
    }

    private fun themeIndex(t: Int) = when (t) {
        Prefs.THEME_LIGHT -> 1
        Prefs.THEME_DARK -> 2
        Prefs.THEME_AMOLED -> 3
        else -> 0
    }

    companion object {
        const val PAGE_RIDER = "rider"
        const val PAGE_DASH = "dash"
        const val PAGE_CALLS = "calls"
        const val PAGE_MESSAGES = "messages"
        const val PAGE_APPEARANCE = "appearance"
        const val PAGE_UPDATES = "updates"
        const val PAGE_TROUBLESHOOTING = "troubleshooting"
    }
}

/** One page under Settings, with a ‹ back to the menu. */
class SettingsPage(
    host: MainActivity,
    title: String,
    content: List<View>,
    private val showHook: () -> Unit = {},
    private val hideHook: () -> Unit = {},
) : Screen(host) {

    override val view: View

    init {
        val back = ui.text("‹", 30f, Palette.TEXT).apply {
            isClickable = true
            isFocusable = true
            contentDescription = "Back to Settings"
            setPadding(ui.dp(4), 0, ui.dp(14), ui.dp(4))
            setOnClickListener { host.showTab(MainActivity.Tab.SETTINGS) }
        }
        val header = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(back)
            addView(ui.text(title, 22f, Palette.TEXT, Ui.Weight.BOLD))
        }
        view = host.page(header, *content.toTypedArray())
    }

    override fun onShow() = showHook()

    override fun onHide() = hideHook()
}
