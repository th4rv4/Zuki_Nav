package com.th4rv4.zukinav.ui

import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.th4rv4.zukinav.R
import com.th4rv4.zukinav.ble.ClusterLink
import com.th4rv4.zukinav.nav.NavRepository
import com.th4rv4.zukinav.nav.NavSnapshot
import com.th4rv4.zukinav.protocol.ClusterModels
import com.th4rv4.zukinav.service.DashService
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Home tab: the bike, live guidance and what is being sent to the dash. */
class RideScreen(host: MainActivity) : Screen(host) {

    private val bikeName = ui.text("", 16f, Palette.TEXT, Ui.Weight.MEDIUM)
    private val bikeDetail = ui.text("", 12f, Palette.MUTED)
    private val statusSlot = FrameLayout(host.ui.ctx)

    private val arrowImage: ImageView = ui.icon(R.drawable.ic_turn_straight, 56, android.graphics.Color.WHITE, "Next Turn")
    private val distance = ui.text("", 40f, Palette.TEXT, Ui.Weight.BOLD)
    private val maneuver = ui.text("", 16f, Palette.TEXT, Ui.Weight.MEDIUM)
    private val road = ui.text("", 14f, Palette.MUTED)
    private val etaValue = statValue()
    private val remainingValue = statValue()
    private val iconValue = statValue()
    private val guidanceBody: LinearLayout
    private val idleBody: LinearLayout

    private val sendingValue = ui.text("", 13f, Palette.MUTED)
    private val sourceValue = ui.text("", 13f, Palette.TEXT_SOFT)
    private val modelValue = ui.text("", 13f, Palette.TEXT_SOFT)
    private val linkButton: Button = ui.button("Connect") { toggleLink() }

    private val navListener: (NavSnapshot?) -> Unit = { refresh() }
    private val stateListener: () -> Unit = { view.post { refresh() } }

    override val view: View

    init {
        val settingsButton = LinearLayout(ui.ctx).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = "Open Settings"
            background = ui.pressable(Palette.SURFACE, 22, Palette.BORDER)
            addView(ui.icon(R.drawable.ic_tab_settings, 20, Palette.TEXT_SOFT), LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)))
            setOnClickListener { host.showTab(MainActivity.Tab.SETTINGS) }
            layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44))
        }
        val header = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ui.vstack(2, ui.title("Zuki Nav"), ui.text("Google Maps on your dash", 13f, Palette.MUTED)),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(settingsButton, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        }

        val bikeCard = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ui.rounded(Palette.SURFACE, 20, Palette.BORDER)
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
            addView(ui.iconTile(R.drawable.ic_bike, 48, 26, Palette.ACCENT_SOFT, 0xFF1B2433.toInt(), 14),
                LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { marginEnd = ui.dp(14) })
            addView(ui.vstack(3, bikeName, bikeDetail), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(statusSlot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(8) })
        }

        val arrowTile = LinearLayout(ui.ctx).apply {
            gravity = Gravity.CENTER
            background = ui.rounded(Palette.ACCENT, 24)
            addView(arrowImage, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)))
        }
        guidanceBody = ui.vstack(
            16,
            LinearLayout(ui.ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(arrowTile, LinearLayout.LayoutParams(ui.dp(96), ui.dp(96)).apply { marginEnd = ui.dp(18) })
                addView(ui.vstack(2, distance, maneuver, road), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            },
            ui.hstack(8, stat("ETA", etaValue), stat("Remaining", remainingValue), stat("Dash Icon", iconValue), weighted = true),
        )
        idleBody = ui.vstack(
            4,
            ui.text("No Active Navigation", 16f, Palette.TEXT, Ui.Weight.MEDIUM),
            ui.text("Start a route in Google Maps and the turns appear here and on your dash.", 14f, Palette.MUTED),
        )
        val guidanceCard = ui.card(
            LinearLayout(ui.ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.sectionLabel("Live Guidance").apply { setPadding(0, 0, 0, 0) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ui.text("From Google Maps", 12f, Palette.MUTED))
            },
            guidanceBody,
            idleBody,
            gapDp = 16,
            padDp = 20,
            radiusDp = 24,
        )

        val statusCard = ui.listCard(
            ui.row("Sending To Dash", trailing = sendingValue),
            ui.row("Arrow Source", trailing = sourceValue),
            ui.row("Bike Model", trailing = modelValue),
        )

        view = host.page(header, bikeCard, guidanceCard, statusCard, linkButton)
    }

    override fun onShow() {
        NavRepository.addListener(navListener)
        DashService.addStateListener(stateListener)
        refresh()
    }

    override fun onHide() {
        NavRepository.removeListener(navListener)
        DashService.removeStateListener(stateListener)
    }

    private fun statValue() = ui.text("-", 17f, Palette.TEXT, Ui.Weight.MEDIUM)

    private fun stat(label: String, value: TextView): LinearLayout =
        ui.vstack(4, ui.text(label, 11f, Palette.MUTED), value).apply {
            background = ui.rounded(Palette.INSET, 14)
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
        }

    private fun refresh() {
        val name = prefs.clusterName
        val model = ClusterModels.fromBleName(name)
        val state = DashService.linkState
        val running = DashService.instance != null

        bikeName.text = if (name == null) "No Bike Chosen" else model.displayName
        bikeDetail.text = if (name == null) "Pair your bike in Settings" else
            "$name · Auto-Connect ${if (prefs.autoConnect) "On" else "Off"}"
        statusSlot.removeAllViews()
        statusSlot.addView(
            when (state) {
                ClusterLink.State.READY -> ui.pill("Connected", Palette.OK, Palette.OK_BG)
                ClusterLink.State.CONNECTING -> ui.pill("Waiting", Palette.WARN, Palette.WARN_BG)
                ClusterLink.State.DISCONNECTED ->
                    ui.pill(if (running) "Not Connected" else "Stopped", Palette.MUTED, Palette.INSET)
            },
        )

        val nav = NavRepository.current
        guidanceBody.visibility = if (nav == null) View.GONE else View.VISIBLE
        idleBody.visibility = if (nav == null) View.VISIBLE else View.GONE
        if (nav != null) {
            val art = TurnArt.of(nav.icon)
            arrowImage.setImageResource(art.res)
            arrowImage.scaleX = if (art.mirrored) -1f else 1f
            arrowImage.rotation = art.rotation
            arrowImage.contentDescription = nav.icon?.label ?: "Next Turn"
            distance.text = nav.metersToTurn?.let { formatDistance(it) } ?: "—"
            maneuver.text = nav.icon?.label ?: "Follow The Route"
            road.text = nav.instruction.orEmpty()
            road.visibility = if (nav.instruction.isNullOrBlank()) View.GONE else View.VISIBLE
            etaValue.text = nav.eta?.let { formatTime(it) } ?: "-"
            remainingValue.text = nav.remainingMeters?.let { formatDistance(it) } ?: "-"
            iconValue.text = nav.icon?.let { "#${model.adjustIcon(it.id)}" } ?: "-"
        }

        sendingValue.text = when {
            state != ClusterLink.State.READY -> "Not Connected"
            nav != null -> "Every 200 ms"
            else -> "Clock Only"
        }
        sendingValue.setTextColor(if (state == ClusterLink.State.READY) Palette.OK else Palette.MUTED)
        sourceValue.text = when (nav?.iconSource) {
            "maps-icon" -> "Maps Arrow"
            "text" -> "Instruction Text"
            "previous" -> "Previous Turn"
            null -> "-"
            else -> "None"
        }
        modelValue.text = if (name == null) "-" else model.displayName

        if (running) {
            linkButton.text = "Disconnect"
            linkButton.background = ui.pressable(Palette.DANGER_BG, 16, Palette.DANGER_BORDER)
            linkButton.setTextColor(Palette.DANGER)
        } else {
            linkButton.text = "Connect"
            linkButton.background = ui.pressable(Palette.ACCENT, 16)
            linkButton.setTextColor(android.graphics.Color.WHITE)
        }
    }

    private fun toggleLink() {
        if (DashService.instance != null) DashService.stop(host) else host.startLink()
        view.postDelayed({ refresh() }, 400)
    }

    private fun formatDistance(m: Double): String =
        if (m < 1000) "${(m / 10).toInt() * 10} m" else String.format(Locale.US, "%.1f km", m / 1000)

    private fun formatTime(t: LocalTime): String =
        t.format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(host)) "HH:mm" else "h:mm a", Locale.US))
}
