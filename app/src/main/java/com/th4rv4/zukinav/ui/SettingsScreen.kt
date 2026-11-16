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
    private var wakeRow: View? = null
    private val wakeSummary = ui.text("", 12f, Palette.MUTED)
    private val wakeAction = ui.text("", 12f, Palette.ON_ACCENT, Ui.Weight.BOLD)

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

        wakeRow = if (com.th4rv4.zukinav.service.Wake.supported(host)) wakeRow() else null
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
                *listOfNotNull(wakeRow).toTypedArray(),
                menuRow(PAGE_BACKUP, R.drawable.ic_set_backup, "Backup & Restore"),
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
        if (wakeRow != null) showWake()
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
        summaries[PAGE_BACKUP]?.text = if (prefs.backupLastAt > 0) "Last backup " + whenText(prefs.backupLastAt) else "No backup yet"
        summaries[PAGE_TROUBLESHOOTING]?.text = "Dash Test, arrows, logs"
    }

    /** Wake On Ignition: Set Up, or On with a tap to turn it off. */
    private fun wakeRow(): View = LinearLayout(ui.ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ui.dp(60)
        setPadding(0, ui.dp(8), 0, ui.dp(8))
        isClickable = true
        isFocusable = true
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Palette.RIPPLE), null, ui.rounded(android.graphics.Color.WHITE, 8),
        )
        addView(icon(R.drawable.ic_set_wake), iconParams())
        addView(
            ui.vstack(2, ui.text("Wake On Ignition", 15f, Palette.TEXT, Ui.Weight.MEDIUM), wakeSummary),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(wakeAction)
        setOnClickListener { onWakeTap() }
    }

    private fun showWake() {
        val on = com.th4rv4.zukinav.service.Wake.isSetUp(host)
        wakeSummary.text = if (on) "On · the app sleeps while the bike is parked" else "Saves battery while the bike is parked"
        wakeSummary.setTextColor(if (on) Palette.OK else Palette.MUTED)
        wakeAction.text = if (on) "›" else "Set Up"
        wakeAction.textSize = if (on) 20f else 12f
        wakeAction.setTextColor(if (on) Palette.MUTED else Palette.ON_ACCENT)
        wakeAction.background = if (on) null else ui.accentGradient(9)
        wakeAction.setPadding(if (on) 0 else ui.dp(10), ui.dp(5), if (on) 0 else ui.dp(10), ui.dp(5))
    }

    private fun onWakeTap() {
        val wake = com.th4rv4.zukinav.service.Wake
        if (wake.isSetUp(host)) {
            android.app.AlertDialog.Builder(host, host.dialogTheme)
                .setTitle("Wake On Ignition")
                .setMessage(
                    "While the bike is parked, Zuki Nav stops completely. Android watches for your bike with the phone's own " +
                        "Bluetooth and wakes Zuki Nav when the ignition comes on. Bluetooth has to stay on for this.",
                )
                .setPositiveButton("OK", null)
                .setNegativeButton("Turn Off") { _, _ ->
                    wake.turnOff(host)
                    showWake()
                }
                .show()
            return
        }
        if (prefs.clusterAddress == null) {
            host.toast("Set up your bike first")
            return
        }
        android.app.AlertDialog.Builder(host, host.dialogTheme)
            .setTitle("Turn The Ignition On")
            .setMessage(
                "Android needs to see your bike once. Turn the ignition on and tap Continue: Zuki Nav lets go of the dash " +
                    "for a moment so Android can find it, then shows your bike in a list. Pick it and allow access. " +
                    "After that, Zuki Nav sleeps while the bike is parked and wakes when you turn it on.",
            )
            .setPositiveButton("Continue") { _, _ ->
                host.setUpWake { ok ->
                    showWake()
                    host.toast(if (ok) "Wake On Ignition is on" else "Couldn't set it up. Is the ignition on?")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        PAGE_BACKUP -> backupPage()
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
        val onDash = ui.switchRow("Show On Dash", "Shows REJECTED on the dash's message screen. During a route it sits below the arrow", prefs.rejectShowOnDash) {
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

    private fun messagesPage(): SettingsPage {
        val extras = ArrayList<View>()
        fun showExtras(on: Boolean) = extras.forEach { it.visibility = if (on) View.VISIBLE else View.GONE }
        val replyText = ui.vstack(
            8,
            ui.text("Reply", 15f, Palette.TEXT, Ui.Weight.MEDIUM),
            ui.input(prefs.autoReplyText, Prefs.DEFAULT_AUTO_REPLY) { prefs.autoReplyText = it }.apply {
                gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                isSingleLine = false
                maxLines = 4
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            },
        ).apply { setPadding(0, ui.dp(10), 0, ui.dp(12)) }
        val contacts = ui.switchRow("Contacts Only", "Skip numbers you haven't saved, and group chats", prefs.autoReplyContactsOnly) {
            prefs.autoReplyContactsOnly = it
        }
        val choices = Prefs.AUTO_REPLY_EVERY
        val every = ui.vstack(
            8,
            ui.text("Reply Again After", 15f, Palette.TEXT, Ui.Weight.MEDIUM),
            ui.segmented(choices.map { if (it == 0) "Once" else "$it min" }, choices.indexOf(prefs.autoReplyEveryMin).coerceAtLeast(0), fill = true) {
                prefs.autoReplyEveryMin = choices[it]
            },
            ui.text("Once means one reply per chat per ride.", 12f, Palette.MUTED),
        ).apply { setPadding(0, ui.dp(10), 0, ui.dp(12)) }
        extras.addAll(listOf(replyText, contacts, every))
        showExtras(prefs.autoReply)
        return SettingsPage(
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
                ui.sectionHeader(R.drawable.ic_sec_message, "Auto-Reply"),
                ui.listCard(
                    ui.switchRow("Auto-Reply While Riding", "WhatsApp chats, while the bike is connected", prefs.autoReply) {
                        prefs.autoReply = it
                        showExtras(it)
                    },
                    replyText,
                    contacts,
                    every,
                ),
                ui.infoNote("How Auto-Reply Works") {
                    host.showInfo(
                        "Auto-Reply",
                        "When a WhatsApp message arrives while the bike is connected, Zuki Nav presses the Reply button on " +
                            "WhatsApp's own notification and sends your reply, the same as replying from the notification " +
                            "yourself. Each chat gets one reply per window, however many messages it sends.\n\n" +
                            "SMS isn't answered: that would need the Send SMS permission. WhatsApp calls aren't answered either. " +
                            "If WhatsApp's notifications are turned off or hidden, there is no Reply button to press.",
                    )
                },
            ),
        )
    }

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

    private fun backupPage(): SettingsPage {
        val fileValue = ui.text("", 12f, Palette.MUTED)
        val lastValue = ui.text("", 12f, Palette.MUTED)
        val busy = arrayOf(false)

        fun refresh() {
            val folder = prefs.backupFolder?.let { android.net.Uri.parse(it) }
            fileValue.text = if (folder == null) "Not chosen yet"
            else com.th4rv4.zukinav.backup.Backup.displayName(host, folder, tree = true) ?: "Chosen folder"
            val last = prefs.backupUri?.let { com.th4rv4.zukinav.backup.Backup.displayName(host, android.net.Uri.parse(it), tree = false) }
            lastValue.text = if (prefs.backupLastAt > 0) {
                listOfNotNull(whenText(prefs.backupLastAt), sizeText(prefs.backupLastSize), last).joinToString(" · ")
            } else "Never"
            lastValue.setTextColor(if (prefs.backupLastAt > 0) Palette.OK else Palette.MUTED)
        }

        fun backUp() {
            if (busy[0]) return
            busy[0] = true
            host.toast("Backing up…")
            Thread {
                val ok = com.th4rv4.zukinav.backup.Backup.toSavedFolder(host)
                host.runOnUiThread {
                    busy[0] = false
                    refresh()
                    if (ok) host.toast("Backed up") else {
                        host.toast("Couldn't save the backup there. Pick the folder again.")
                        prefs.backupFolder = null
                        refresh()
                    }
                }
            }.start()
        }

        fun choose(thenBackUp: Boolean) {
            host.chooseBackupFolder { uri ->
                if (uri == null) return@chooseBackupFolder
                com.th4rv4.zukinav.backup.Backup.keepAccess(host, uri)
                prefs.backupFolder = uri.toString()
                refresh()
                if (thenBackUp) backUp()
            }
        }

        fun restore() {
            host.pickBackupToRestore { uri ->
                if (uri == null) return@pickBackupToRestore
                Thread {
                    val info = try {
                        host.contentResolver.openInputStream(uri)?.use { com.th4rv4.zukinav.backup.Backup.peek(it) }
                    } catch (e: com.th4rv4.zukinav.backup.Backup.NewerBackup) {
                        host.runOnUiThread { host.toast(e.message ?: "Update Zuki Nav first") }
                        return@Thread
                    } catch (e: Exception) {
                        null
                    }
                    host.runOnUiThread {
                        if (info == null) {
                            host.toast("That isn't a Zuki Nav backup")
                            return@runOnUiThread
                        }
                        android.app.AlertDialog.Builder(host, host.dialogTheme)
                            .setTitle("Restore This Backup?")
                            .setMessage(
                                "From ${whenText(info.createdAt)} (Zuki Nav v${info.appVersion})\n" +
                                    "${info.trips} trips · ${info.routes} routes · fuel, reminders and settings\n\n" +
                                    "This replaces what's in the app now. The app restarts when it's done.",
                            )
                            .setPositiveButton("Restore") { _, _ ->
                                Thread {
                                    val ok = try {
                                        host.contentResolver.openInputStream(uri)?.use { com.th4rv4.zukinav.backup.Backup.restore(host, it) } != null
                                    } catch (e: Exception) {
                                        com.th4rv4.zukinav.util.AppLog.d("Backup", "Restore failed: $e")
                                        false
                                    }
                                    host.runOnUiThread {
                                        if (ok) {
                                            host.toast("Restored. Restarting…")
                                            view.postDelayed({ host.restartApp() }, 800)
                                        } else {
                                            host.toast("Couldn't restore that file. Nothing was changed.")
                                        }
                                    }
                                }.start()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }.start()
            }
        }

        val fileRow = ui.row("Backup Folder", null, ui.text("Change", 13f, Palette.ACCENT_SOFT, Ui.Weight.MEDIUM)).apply {
            ((getChildAt(0) as? LinearLayout) ?: this).addView(fileValue)
            isClickable = true
            isFocusable = true
            setOnClickListener { choose(thenBackUp = false) }
        }
        return SettingsPage(
            host, "Backup & Restore",
            listOf(
                ui.listCard(
                    fileRow,
                    ui.row("Last Backup").apply { ((getChildAt(0) as? LinearLayout) ?: this).addView(lastValue) },
                    ui.switchRow("Back Up After Every Trip", "Makes a fresh backup each time a ride is saved", prefs.backupAfterTrip) {
                        prefs.backupAfterTrip = it
                        if (it && prefs.backupFolder == null) choose(thenBackUp = true)
                    },
                ),
                ui.button("Back Up Now", Ui.Btn.PRIMARY, 50) { if (prefs.backupFolder == null) choose(thenBackUp = true) else backUp() },
                ui.button("Restore From Backup", Ui.Btn.SECONDARY, 50) { restore() },
                ui.text(
                    "Each backup is named with its date and time, like ZukiNav-2026-11-15_21-14.zip, and replaces the " +
                        "previous one, so the folder only ever holds the latest. In a backup: trips and their route maps, " +
                        "fuel, reminders, your avatar and settings, including your saved bike. Pick a Google Drive folder to keep it " +
                        "safe if you lose the phone.",
                    12f, Palette.MUTED,
                ).apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) },
            ),
            showHook = { refresh() },
        )
    }

    private fun whenText(at: Long): String {
        val zone = java.time.ZoneId.systemDefault()
        val d = java.time.Instant.ofEpochMilli(at).atZone(zone)
        val today = java.time.LocalDate.now(zone)
        val time = d.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern(
            if (android.text.format.DateFormat.is24HourFormat(host)) "HH:mm" else "h:mm a", java.util.Locale.US,
        ))
        return when (d.toLocalDate()) {
            today -> "today, $time"
            today.minusDays(1) -> "yesterday, $time"
            else -> d.toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.US)) + ", $time"
        }
    }

    private fun sizeText(bytes: Long): String = when {
        bytes >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
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
        const val PAGE_BACKUP = "backup"
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
