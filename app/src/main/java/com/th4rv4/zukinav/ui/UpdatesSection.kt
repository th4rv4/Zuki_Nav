package com.th4rv4.zukinav.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.th4rv4.zukinav.update.Release
import com.th4rv4.zukinav.update.Updater
import com.th4rv4.zukinav.update.Updater.State

/** The Updates part of Settings: check GitHub for a new release and install it. */
class UpdatesSection(private val host: MainActivity) {

    private val ui = host.ui
    private val prefs = host.prefs
    private val installed = host.versionName()

    private val status = ui.text("", 13f, Palette.MUTED, Ui.Weight.MEDIUM)
    private val checkRow = ui.row("Check For Updates", "Installed v$installed", status).apply {
        isClickable = true
        isFocusable = true
        background = ui.pressable(Palette.SURFACE, 8)
        setOnClickListener { Updater.check(installed) }
    }

    /** Shown while a newer release is found, downloading or installing. */
    private val card = ui.card().apply { visibility = View.GONE }

    val views: List<View> = listOf(
        ui.listCard(
            checkRow,
            ui.switchRow("Check On Launch", "Once a day, when the app opens", prefs.checkUpdatesOnLaunch) {
                prefs.checkUpdatesOnLaunch = it
            },
        ),
        card,
    )

    private val listener: (State) -> Unit = { render(it) }

    fun onShow() {
        Updater.removeListener(listener)
        Updater.addListener(listener)
        render(Updater.state)
    }

    fun onHide() {
        Updater.removeListener(listener)
    }

    private fun render(s: State) {
        val (label, color) = when (s) {
            State.Idle -> "Tap To Check" to Palette.MUTED
            State.Checking -> "Checking…" to Palette.ACCENT_SOFT
            is State.UpToDate -> "Up To Date" to Palette.OK
            is State.Available -> "Update Found" to Palette.ACCENT_SOFT
            is State.Downloading -> "Downloading" to Palette.ACCENT_SOFT
            is State.Installing -> "Installing" to Palette.ACCENT_SOFT
            is State.Failed -> "Failed" to Palette.DANGER
        }
        status.text = label
        status.setTextColor(color)

        card.removeAllViews()
        when (s) {
            is State.Available -> available(s.release)
            is State.Downloading -> downloading(s)
            is State.Installing -> {
                card.addView(ui.text("Installing v${s.release.version}", 16f, Palette.TEXT, Ui.Weight.MEDIUM))
                card.addView(ui.text("Confirm the update in Android's prompt. The app restarts on the new version.", 13f, Palette.MUTED))
            }
            is State.Failed -> {
                card.addView(ui.text(s.message, 14f, Palette.DANGER))
                card.addView(ui.button("Try Again", Ui.Btn.SECONDARY, 44) {
                    if (s.release != null) install(s.release) else Updater.check(installed)
                })
            }
            else -> Unit
        }
        card.visibility = if (card.childCount > 0) View.VISIBLE else View.GONE
        card.background = ui.rounded(Palette.SURFACE, 20, if (s is State.Available) Palette.ACCENT else Palette.BORDER)
    }

    private fun available(r: Release) {
        card.addView(ui.text("Zuki Nav v${r.version}", 17f, Palette.TEXT, Ui.Weight.BOLD))
        val size = if (r.apkSize > 0) " · ${mb(r.apkSize)} MB" else ""
        card.addView(ui.text("You have v$installed$size", 13f, Palette.MUTED))
        if (r.notes.isNotBlank()) {
            card.addView(ui.text("What's New", 14f, Palette.TEXT, Ui.Weight.MEDIUM))
            card.addView(ui.text(r.notes, 13f, Palette.TEXT_SOFT))
        }
        card.addView(ui.button("Download And Install", Ui.Btn.PRIMARY, 48) { install(r) })
        card.addView(ui.button("View On GitHub", Ui.Btn.SECONDARY, 44) { host.openUrl(r.pageUrl) })
    }

    private fun downloading(s: State.Downloading) {
        card.addView(ui.text("Downloading v${s.release.version}", 16f, Palette.TEXT, Ui.Weight.MEDIUM))
        val bar = ProgressBar(ui.ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            isIndeterminate = s.total <= 0
            if (s.total > 0) progress = (s.done * 1000 / s.total).toInt()
            progressTintList = ColorStateList.valueOf(Palette.ACCENT)
        }
        card.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ui.dp(8)))
        val of = if (s.total > 0) " of ${mb(s.total)}" else ""
        card.addView(ui.text("${mb(s.done)}$of MB", 13f, Palette.MUTED))
    }

    private fun install(r: Release) {
        // Android asks once to let Zuki Nav install apps.
        if (!host.packageManager.canRequestPackageInstalls()) {
            host.toast("Allow Zuki Nav to install apps, then come back and tap again")
            host.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${host.packageName}")),
            )
            return
        }
        Updater.install(host, r)
    }

    private fun mb(bytes: Long) = "%.1f".format(bytes / 1_048_576.0)
}
