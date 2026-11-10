package com.th4rv4.zukinav.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.th4rv4.zukinav.R
import com.th4rv4.zukinav.protocol.ClusterModels
import com.th4rv4.zukinav.service.DashService
import com.th4rv4.zukinav.util.AppLog

/** First-run checklist and bike pairing. Also opened from Settings > Change Bike. */
@SuppressLint("MissingPermission") // Scanning starts only after hasBlePermissions().
class SetupScreen(host: MainActivity) : Screen(host) {

    private val progress = ProgressRingView(host.ui.ctx)
    private val checklist = LinearLayout(host.ui.ctx).apply { orientation = LinearLayout.VERTICAL }
    private val scanState = FrameLayout(host.ui.ctx)
    private val results = LinearLayout(host.ui.ctx).apply { orientation = LinearLayout.VERTICAL }
    private val scanButton: Button = ui.button("Scan For Bike", Ui.Btn.SECONDARY, 48) { if (scanning) stopScan() else startScan() }
    private val finishButton: Button = ui.button("Finish Setup") { finish() }

    private var scanning = false
    private val found = LinkedHashMap<String, String>() // address -> name

    override val view: View

    init {
        val head = LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(progress, LinearLayout.LayoutParams(ui.dp(58), ui.dp(58)).apply { marginEnd = ui.dp(14) })
            addView(ui.vstack(
                2,
                ui.text("FIRST RUN", 11f, Palette.ACCENT_SOFT, Ui.Weight.BOLD).apply { letterSpacing = 0.14f },
                ui.title("Set Up Zuki Nav"),
            ))
        }
        val intro = ui.vstack(
            10,
            head,
            ui.text("A few things, once. After this the app connects to your bike on its own.", 14f, Palette.MUTED),
        ).apply { setPadding(ui.dp(4), 0, ui.dp(4), 0) }

        val nameCard = ui.card(
            ui.text("Your Name", 16f, Palette.TEXT, Ui.Weight.MEDIUM),
            ui.text("The dash greets you with it when it connects. Optional.", 13f, Palette.MUTED),
            ui.input(prefs.riderName, "Rider Name") { prefs.riderName = it }.apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                filters = arrayOf(android.text.InputFilter.LengthFilter(MAX_NAME))
            },
        )

        val bikeCard = ui.card(
            LinearLayout(ui.ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.text("Find Your Bike", 16f, Palette.TEXT, Ui.Weight.MEDIUM),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(scanState)
            },
            ui.text("Turn the ignition on. Your bike shows up below with its model.", 13f, Palette.MUTED),
            results,
            scanButton,
            ui.text("Works with Suzuki Ride Connect models sold in India.", 12f, Palette.MUTED),
        )

        view = host.page(intro, checklist, nameCard, bikeCard, finishButton)
    }

    override fun onShow() {
        renderChecklist()
        renderResults()
        if (host.hasBlePermissions() && prefs.clusterAddress == null && !scanning) startScan()
    }

    override fun onHide() {
        stopScan()
    }

    private fun renderChecklist() {
        checklist.removeAllViews()
        val ble = host.hasBlePermissions()
        val listener = host.hasListenerAccess()
        val maps = host.mapsInstalled()
        val rcInstalled = host.isInstalled(RIDE_CONNECT_PKG)

        val paired = prefs.clusterAddress != null
        progress.set(listOf(ble, listener, maps, paired).count { it }, 4)

        val card = ui.listCard(
            ui.row(
                "Nearby Devices", "To find and talk to the dash",
                if (ble) ui.text("Allowed", 13f, Palette.OK) else smallButton("Grant") { host.requestBlePermissions() },
                ui.badge(if (ble) Ui.Badge.Done else Ui.Badge.Step(1)),
            ),
            ui.row(
                "Google Maps", if (maps) "Turn arrows are read from Maps" else "Install Google Maps to navigate",
                ui.text(if (maps) "Ready" else "Missing", 13f, if (maps) Palette.OK else Palette.WARN),
                ui.badge(if (maps) Ui.Badge.Done else Ui.Badge.Step(2)),
            ),
            ui.row(
                "Notification Access", "To read Maps directions",
                if (listener) ui.text("Allowed", 13f, Palette.OK) else smallButton("Grant") { host.openListenerSettings() },
                ui.badge(if (listener) Ui.Badge.Done else Ui.Badge.Step(3)),
            ),
            ui.row(
                "Close Ride Connect", "The dash takes one phone at a time",
                if (rcInstalled) smallButton("Force Stop", primary = false) { host.openAppInfo(RIDE_CONNECT_PKG) }
                else ui.text("Not Installed", 13f, Palette.OK),
                ui.badge(if (rcInstalled) Ui.Badge.Step(4) else Ui.Badge.Done),
            ),
        )
        checklist.addView(card)

        val done = host.setupComplete()
        finishButton.isEnabled = done
        finishButton.alpha = if (done) 1f else 0.5f
        finishButton.text = if (done) "Finish Setup" else "Finish The Steps Above"
    }

    private fun smallButton(label: String, primary: Boolean = true, onClick: () -> Unit): Button =
        ui.button(label, if (primary) Ui.Btn.PRIMARY else Ui.Btn.SECONDARY, 36, onClick).apply {
            textSize = 13f
            setPadding(ui.dp(12), 0, ui.dp(12), 0)
        }

    private fun renderResults() {
        results.removeAllViews()
        scanState.removeAllViews()
        if (scanning) scanState.addView(ui.dotLabel("Scanning", Palette.ACCENT_SOFT))
        val chosen = prefs.clusterAddress
        if (chosen != null && !found.containsKey(chosen)) {
            results.addView(bikeButton(chosen, prefs.clusterName ?: chosen, chosen = true))
        }
        for ((addr, name) in found) results.addView(bikeButton(addr, name, chosen = addr == chosen))
        if (!scanning && found.isEmpty() && chosen == null) {
            results.addView(ui.text("No bike found yet.", 13f, Palette.MUTED))
        }
        scanButton.text = if (scanning) "Stop Scan" else "Scan For Bike"
    }

    private fun bikeButton(addr: String, name: String, chosen: Boolean): View {
        val model = ClusterModels.fromBleName(name)
        return LinearLayout(ui.ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = "Pair ${model.displayName}"
            background = ui.pressable(if (chosen) Palette.ACCENT_BG else Palette.INSET, 16,
                if (chosen) Palette.ACCENT else Accents.withAlpha(Palette.ACCENT_SOFT, 0x8C))
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
            addView(ui.icon(R.drawable.ic_bike, 24, Palette.ACCENT_SOFT),
                LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)).apply { marginEnd = ui.dp(12) })
            addView(ui.vstack(2, ui.text(model.displayName, 15f, Palette.TEXT, Ui.Weight.MEDIUM), ui.text(name, 12f, Palette.MUTED)),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.text(if (chosen) "Paired" else "Pair", 13f, if (chosen) Palette.OK else Palette.ACCENT_SOFT, Ui.Weight.BOLD))
            setOnClickListener { choose(addr, name) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = ui.dp(8) }
        }
    }

    private fun choose(addr: String, name: String) {
        stopScan()
        prefs.clusterAddress = addr
        prefs.clusterName = name
        AppLog.d("Scan", "Chose $name ($addr)")
        // A new bike: restart the link so it connects to this one.
        if (DashService.instance != null) DashService.stop(host)
        view.postDelayed({ host.startLink() }, 1_200)
        renderChecklist()
        renderResults()
    }

    private fun finish() {
        if (!host.setupComplete()) return
        if (DashService.instance == null) host.startLink()
        host.showTab(MainActivity.Tab.RIDE)
    }

    // ---- scanning -------------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            val addr = result.device.address
            if (!ClusterModels.looksLikeCluster(name) || found.containsKey(addr)) return
            view.post {
                found[addr] = name
                renderResults()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            AppLog.d("Scan", "Scan failed: $errorCode")
            view.post { stopScan() }
        }
    }

    private val scanTimeout = Runnable { stopScan() }

    private fun startScan() {
        if (!host.hasBlePermissions()) { host.requestBlePermissions(); return }
        val adapter = host.getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null || !adapter.isEnabled) { host.toast("Turn Bluetooth on"); return }
        // The dash accepts one connection; free it from our own link while scanning.
        if (DashService.instance != null) DashService.stop(host)
        found.clear()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        adapter.bluetoothLeScanner?.startScan(null, settings, scanCallback) ?: return
        scanning = true
        AppLog.d("Scan", "Scanning for Suzuki clusters")
        view.postDelayed(scanTimeout, 15_000)
        renderResults()
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        view.removeCallbacks(scanTimeout)
        try {
            host.getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        renderResults()
    }

    private companion object {
        const val RIDE_CONNECT_PKG = "suzuki.com.suzuki"
        /** The dash shows up to 20 characters. */
        const val MAX_NAME = 20
    }
}

/** Ring that fills with the setup steps done, with "done/total" inside. */
class ProgressRingView(ctx: android.content.Context) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 5 * d
    }
    private val arc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 5 * d
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    private val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = DashFonts.numbers
    }
    private val oval = android.graphics.RectF()
    private var done = 0
    private var total = 4

    init {
        if (!Palette.isLight) {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            arc.setShadowLayer(5 * d, 0f, 0f, Palette.ACCENT_GLOW)
        }
    }

    fun set(done: Int, total: Int) {
        this.done = done
        this.total = total
        contentDescription = "$done of $total setup steps done"
        invalidate()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val pad = 6 * d
        oval.set(pad, pad, width - pad, height - pad)
        track.color = Palette.TRACK
        canvas.drawOval(oval, track)
        if (done > 0) {
            arc.shader = android.graphics.LinearGradient(oval.left, oval.bottom, oval.right, oval.top,
                Palette.ACCENT, Palette.ACCENT_MID, android.graphics.Shader.TileMode.CLAMP)
            canvas.drawArc(oval, -90f, 360f * done / total, false, arc)
        }
        label.color = Palette.TEXT
        label.textSize = height * 0.28f
        canvas.drawText("$done/$total", width / 2f, height / 2f - (label.descent() + label.ascent()) / 2, label)
    }
}
