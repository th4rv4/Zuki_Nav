package com.th4rv4.suzukinav.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.th4rv4.suzukinav.ble.ClusterLink
import com.th4rv4.suzukinav.nav.MapsIconTemplates
import com.th4rv4.suzukinav.nav.MapsNavListener
import com.th4rv4.suzukinav.nav.NavRepository
import com.th4rv4.suzukinav.nav.NavSnapshot
import com.th4rv4.suzukinav.protocol.ClusterIcon
import com.th4rv4.suzukinav.protocol.ClusterModels
import com.th4rv4.suzukinav.protocol.DashDistance
import com.th4rv4.suzukinav.service.DashService
import com.th4rv4.suzukinav.util.AppLog
import com.th4rv4.suzukinav.util.Prefs

/**
 * One functional screen: setup, live guidance preview, icon test panel,
 * settings and the log. Built in code to keep the app dependency-free.
 */
@SuppressLint("MissingPermission", "SetTextI18n", "UseSwitchCompatOrMaterialCode")
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())

    private lateinit var statusText: TextView
    private lateinit var setupText: TextView
    private lateinit var navText: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var scanButton: Button
    private lateinit var startButton: Button
    private lateinit var testLabel: TextView
    private lateinit var logText: TextView

    private var testId = 1
    private var cycling = false
    private var scanning = false
    private val found = LinkedHashMap<String, String>() // address -> name

    private val logListener: () -> Unit = { refreshLog() }
    private val stateListener: () -> Unit = { main.post { refreshStatus() } }
    private val navListener: (NavSnapshot?) -> Unit = { refreshNav(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        AppLog.addListener(logListener)
        DashService.addStateListener(stateListener)
        NavRepository.addListener(navListener)
        refreshStatus()
        refreshNav(NavRepository.current)
        refreshLog()
    }

    override fun onPause() {
        AppLog.removeListener(logListener)
        DashService.removeStateListener(stateListener)
        NavRepository.removeListener(navListener)
        stopScan()
        cycling = false
        super.onPause()
    }

    // ---- UI ---------------------------------------------------------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun card(title: String, vararg children: View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0xFF161A22.toInt()) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(12) }
            addView(text(title, 13f, 0xFF8A93A6.toInt(), bold = true).apply { letterSpacing = 0.08f })
            children.forEach { addView(it) }
        }

    private fun text(s: String, size: Float = 15f, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4), 0, dp(4))
        }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun row(vararg views: View) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
        }

    private fun buildUi(): View {
        statusText = text("", 17f, bold = true)
        setupText = text("", 14f, 0xFFB8C0D0.toInt())
        navText = text("", 16f)
        deviceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scanButton = button("Scan For Bike") { if (scanning) stopScan() else startScan() }
        startButton = button("Start") { toggleService() }
        testLabel = text("", 15f)
        logText = text("", 11f, 0xFF9AA3B5.toInt()).apply { typeface = Typeface.MONOSPACE }

        val riderName = EditText(this).apply {
            hint = "Rider Name (Shown On Dash)"
            setText(prefs.riderName)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF6B7385.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            addTextChangedListener(simpleWatcher { prefs.riderName = it })
        }
        val speed = EditText(this).apply {
            hint = "Speed Alert km/h (0 = Off)"
            setText(prefs.speedAlertKmh.takeIf { it > 0 }?.toString() ?: "")
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF6B7385.toInt())
            inputType = InputType.TYPE_CLASS_NUMBER
            addTextChangedListener(simpleWatcher { prefs.speedAlertKmh = it.toIntOrNull()?.coerceIn(0, 999) ?: 0 })
        }
        val lht = Switch(this).apply {
            text = "Left-Hand Traffic (India)"
            setTextColor(Color.WHITE)
            isChecked = prefs.leftHandTraffic
            setOnCheckedChangeListener { _, c -> prefs.leftHandTraffic = c }
        }
        val auto = Switch(this).apply {
            text = "Auto-Connect"
            setTextColor(Color.WHITE)
            isChecked = prefs.autoConnect
            setOnCheckedChangeListener { _, c -> prefs.autoConnect = c }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
            addView(text("Suzuki Nav", 26f, bold = true))
            addView(text("Google Maps Directions On Your Suzuki Dash", 14f, 0xFF8A93A6.toInt()))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(1, dp(12)))
            addView(card("STATUS", statusText, setupText,
                row(button("Permissions") { requestPermissions() }, button("Notification Access") { openListenerSettings() }),
                row(scanButton, startButton),
                deviceList))
            addView(card("LIVE GUIDANCE", navText))
            addView(card("ICON TEST", testLabel,
                row(button("Prev") { stepTest(-1) }, button("Send") { DashService.testIcon(testId) }, button("Next") { stepTest(1) }),
                row(button("Cycle All") { cycleAll() }, button("Stop Cycle") { cycling = false }),
                text("Shows an icon on the dash for 8 s with 350 m / 12.3 km. Use it to check what each icon ID looks like on your bike.", 12f, 0xFF8A93A6.toInt())))
            addView(card("SETTINGS", riderName, speed, lht, auto))
            addView(card("LOG", row(button("Copy Log") { copyLog() }, button("Reload Maps Arrows") { reloadArrows() }), logText))
        }
        updateTestLabel()
        return ScrollView(this).apply {
            setBackgroundColor(0xFF0E1116.toInt())
            addView(content)
        }
    }

    private fun simpleWatcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { onChange(s?.toString()?.trim().orEmpty()) }
    }

    // ---- state --------------------------------------------------------------

    private fun refreshStatus() {
        val state = DashService.linkState
        val running = DashService.instance != null
        val name = prefs.clusterName
        statusText.text = when (state) {
            ClusterLink.State.READY -> "Connected · ${ClusterModels.fromBleName(name).displayName}"
            ClusterLink.State.CONNECTING -> "Waiting For ${name ?: "Bike"}"
            ClusterLink.State.DISCONNECTED -> if (running) "Not Connected" else "Stopped"
        }
        val checks = listOf(
            "Bluetooth Permission" to hasBlePermissions(),
            "Notification Access" to hasListenerAccess(),
            "Bike Chosen" to (prefs.clusterAddress != null),
            "Google Maps Installed" to mapsInstalled(),
        )
        setupText.text = checks.joinToString("\n") { (label, ok) -> (if (ok) "✓ " else "✗ ") + label } +
            (name?.let { "\nBike: ${ClusterModels.fromBleName(it).displayName} · $it (${prefs.clusterAddress})" } ?: "")
        startButton.text = if (running) "Stop" else "Start"
    }

    private fun refreshNav(s: NavSnapshot?) {
        navText.text = if (s == null) {
            "No active navigation. Start a route in Google Maps."
        } else {
            val toTurn = s.metersToTurn?.let { DashDistance.fromMeters(it, true) }
            val rem = s.remainingMeters?.let { DashDistance.fromMeters(it) }
            buildString {
                append(s.icon?.let { "${it.label} (#${it.id})" } ?: "No Arrow").append("  [${s.iconSource}]\n")
                append("In ${toTurn?.let { "${it.digits} ${it.unit}" } ?: "-"}   ·   ${s.instruction ?: ""}\n")
                append("ETA ${s.eta ?: "-"}   ·   Remaining ${rem?.let { "${it.digits} ${it.unit}" } ?: "-"}")
            }
        }
    }

    private fun refreshLog() {
        logText.text = AppLog.snapshot().takeLast(80).joinToString("\n")
    }

    private fun updateTestLabel() {
        testLabel.text = "Icon #$testId · ${ClusterIcon.fromId(testId)?.label ?: "Unlisted"}"
    }

    private fun stepTest(d: Int) {
        testId = ((testId - 1 + d + 64) % 64) + 1
        updateTestLabel()
    }

    private fun cycleAll() {
        if (DashService.linkState != ClusterLink.State.READY) {
            toast("Connect to the bike first")
            return
        }
        cycling = true
        testId = 1
        val r = object : Runnable {
            override fun run() {
                if (!cycling || testId > 63) { cycling = false; return }
                updateTestLabel()
                DashService.testIcon(testId)
                testId++
                main.postDelayed(this, 2_500)
            }
        }
        main.post(r)
    }

    // ---- actions ------------------------------------------------------------

    private fun toggleService() {
        if (DashService.instance != null) {
            DashService.stop(this)
        } else {
            if (!hasBlePermissions()) { requestPermissions(); return }
            if (prefs.clusterAddress == null) { toast("Scan and pick your bike first"); return }
            DashService.start(this)
        }
        main.postDelayed({ refreshStatus() }, 400)
    }

    private fun requestPermissions() {
        val perms = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }

    private fun hasBlePermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun hasListenerAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, MapsNavListener::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun openListenerSettings() {
        val i = if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(this, MapsNavListener::class.java).flattenToString())
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
        try {
            startActivity(i)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    private fun mapsInstalled(): Boolean = try {
        packageManager.getPackageInfo(MapsIconTemplates.MAPS_PKG, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    private fun reloadArrows() {
        Thread {
            val n = MapsIconTemplates.matcher(this).size
            main.post { toast("$n Maps arrow templates loaded") }
        }.start()
    }

    private fun copyLog() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Suzuki Nav log", AppLog.snapshot().joinToString("\n")))
        toast("Log copied")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---- scanning -----------------------------------------------------------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            val addr = result.device.address
            if (!ClusterModels.looksLikeCluster(name) || found.containsKey(addr)) return
            found[addr] = name
            main.post { addDeviceButton(addr, name) }
        }

        override fun onScanFailed(errorCode: Int) {
            AppLog.d("Scan", "Scan failed: $errorCode")
            main.post { stopScan() }
        }
    }

    private fun startScan() {
        if (!hasBlePermissions()) { requestPermissions(); return }
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null || !adapter.isEnabled) { toast("Turn Bluetooth on"); return }
        // The cluster only allows one phone; free it from this app's own link first.
        if (DashService.instance != null) DashService.stop(this)
        found.clear()
        deviceList.removeAllViews()
        deviceList.addView(text("Searching… turn the ignition on and close Suzuki Ride Connect.", 13f, 0xFF8A93A6.toInt()))
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        adapter.bluetoothLeScanner?.startScan(null, settings, scanCallback)
        scanning = true
        scanButton.text = "Stop Scan"
        AppLog.d("Scan", "Scanning for Suzuki clusters")
        main.postDelayed({ stopScan() }, 15_000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        scanButton.text = "Scan For Bike"
        try {
            getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        if (found.isEmpty()) {
            deviceList.removeAllViews()
            deviceList.addView(text("No Suzuki cluster found.", 13f, 0xFF8A93A6.toInt()))
        }
    }

    private fun addDeviceButton(addr: String, name: String) {
        if (deviceList.childCount == 1 && deviceList.getChildAt(0) is TextView && deviceList.getChildAt(0) !is Button) {
            deviceList.removeAllViews()
        }
        deviceList.addView(button("$name · ${ClusterModels.fromBleName(name).displayName}") {
            prefs.clusterAddress = addr
            prefs.clusterName = name
            AppLog.d("Scan", "Chose $name ($addr)")
            stopScan()
            deviceList.removeAllViews()
            refreshStatus()
            DashService.start(this)
        })
    }
}
