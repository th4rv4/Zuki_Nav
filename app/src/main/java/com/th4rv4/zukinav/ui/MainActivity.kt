package com.th4rv4.zukinav.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.th4rv4.zukinav.R
import com.th4rv4.zukinav.nav.MapsIconTemplates
import com.th4rv4.zukinav.nav.MapsNavListener
import com.th4rv4.zukinav.service.DashService
import com.th4rv4.zukinav.update.Updater
import com.th4rv4.zukinav.util.AppLog
import com.th4rv4.zukinav.util.Places
import com.th4rv4.zukinav.util.Prefs

/**
 * Hosts the five tabs (Ride, Trips, Fuel, Test, Settings) and the first-run Setup
 * screen. Owns everything that needs the Activity: permissions, system
 * settings screens, sharing and dialogs.
 */
class MainActivity : Activity() {

    lateinit var prefs: Prefs
        private set
    lateinit var ui: Ui
        private set

    private lateinit var content: FrameLayout
    private lateinit var tabBar: LinearLayout
    private lateinit var tabDock: FrameLayout
    private var navInset = 0

    /** Without the tab bar (Setup), the page itself keeps clear of the gesture bar. */
    private fun padContent() {
        content.setPadding(0, 0, 0, if (tabDock.visibility == View.VISIBLE) 0 else navInset)
    }
    private val tabViews = ArrayList<Pair<Tab, LinearLayout>>()

    enum class Tab(val label: String, val icon: Int) {
        RIDE("Ride", R.drawable.ic_tab_ride),
        TRIPS("Trips", R.drawable.ic_tab_trips),
        BIKE("Bike", R.drawable.ic_tab_bike),
        SETTINGS("Settings", R.drawable.ic_tab_settings),
    }

    private val screens = HashMap<String, Screen>()
    private var current: Screen? = null
    private var currentTab: Tab? = null
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        val mode = themeMode()
        val light = mode == Palette.Mode.LIGHT
        Palette.apply(this, mode, prefs.accent)
        setTheme(if (light) R.style.Theme_ZukiNav_Light else R.style.Theme_ZukiNav)
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        setContentView(buildRoot())
        styleSystemBars(light)
        // After a theme change the app reopens on the tab it was on.
        val reopen = savedInstanceState?.getString(STATE_TAB)?.let { n -> Tab.entries.firstOrNull { it.name == n } }
        if (setupComplete()) showTab(reopen ?: Tab.RIDE) else showSetup()
        checkForUpdateIfDue()
        openRefillFrom(intent)
        openMaintenanceFrom(intent)
        com.th4rv4.zukinav.maint.MaintNotifier.schedule(this)
        Thread { com.th4rv4.zukinav.papers.PaperNotifier.check(applicationContext) }.start()
        // Kept for the whole time the app is open, so the Bluetooth popup also
        // closes when Bluetooth is turned on from Android's own prompt.
        val filter = android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(bluetoothReceiver, filter)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRefillFrom(intent)
        openMaintenanceFrom(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_RIDE, false) && setupComplete()) showTab(Tab.RIDE)
    }

    /** From a maintenance reminder notification: opens the Service tab. */
    private fun openMaintenanceFrom(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_MAINTENANCE, false) != true) return
        intent.removeExtra(EXTRA_MAINTENANCE)
        if (setupComplete()) showBikeSection(BIKE_SERVICE)
    }

    /** From the "Refuelled?" notification: opens that refill's form. */
    private fun openRefillFrom(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_REFILL, -1L) ?: -1L
        if (id < 0) return
        intent?.removeExtra(EXTRA_REFILL)
        getSystemService(android.app.NotificationManager::class.java).cancel(DashService.REFILL_NOTIF_ID)
        val refill = com.th4rv4.zukinav.fuel.FuelStore.refills(this).firstOrNull { it.id == id } ?: return
        window.decorView.post { RefillSheet(this, refill).show() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentTab?.let { outState.putString(STATE_TAB, it.name) }
    }

    private fun themeMode(): Palette.Mode = when (prefs.theme) {
        Prefs.THEME_LIGHT -> Palette.Mode.LIGHT
        Prefs.THEME_DARK -> Palette.Mode.DARK
        Prefs.THEME_AMOLED -> Palette.Mode.AMOLED
        else -> if ((resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        ) Palette.Mode.DARK else Palette.Mode.LIGHT
    }

    private fun currentMode(): Palette.Mode = when {
        Palette.isLight -> Palette.Mode.LIGHT
        Palette.isAmoled -> Palette.Mode.AMOLED
        else -> Palette.Mode.DARK
    }

    /** Switches between System, Light, Dark and AMOLED; the screen is rebuilt in the new colours. */
    fun setThemeChoice(choice: Int) {
        if (prefs.theme == choice) return
        prefs.theme = choice
        if (themeMode() != currentMode()) recreate()
    }

    /** Switches the accent colours; the screen is rebuilt in them. */
    fun setAccentChoice(key: String) {
        if (prefs.accent == key) return
        prefs.accent = key
        recreate()
    }

    @Suppress("DEPRECATION")
    private fun styleSystemBars(light: Boolean) {
        window.decorView.setBackgroundColor(Palette.BG)
        window.statusBarColor = Palette.BG
        window.navigationBarColor = Palette.BG
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
        } else {
            window.decorView.systemUiVisibility = if (light) {
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            } else 0
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(bluetoothReceiver) } catch (_: Exception) {}
        bluetoothSheet?.dismiss()
        Updater.removeListener(launchCheckListener)
        super.onDestroy()
    }

    private var launchCheckPending = false

    // ---- updates ------------------------------------------------------------

    /** Tells the rider when the once-a-day check on launch finds a new release. */
    private val launchCheckListener: (Updater.State) -> Unit = { s ->
        if (launchCheckPending) {
            when (s) {
                is Updater.State.Available -> {
                    launchCheckPending = false
                    toast("Zuki Nav v${s.release.version} is available. See Settings > Updates.")
                }
                is Updater.State.UpToDate, is Updater.State.Failed -> launchCheckPending = false
                else -> Unit
            }
        }
    }

    private fun checkForUpdateIfDue() {
        if (!prefs.checkUpdatesOnLaunch) return
        val now = System.currentTimeMillis()
        if (now - prefs.lastUpdateCheck < 24 * 60 * 60 * 1000L) return
        prefs.lastUpdateCheck = now
        launchCheckPending = true
        Updater.addListener(launchCheckListener)
        Updater.check(versionName())
    }

    fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) {
            toast("No browser found")
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        current?.onShow()
        checkBluetooth()
    }

    override fun onPause() {
        resumed = false
        current?.onHide()
        // Picks up a theme change, and anything the app changed while open.
        com.th4rv4.zukinav.widget.RangeWidget.refresh(this)
        super.onPause()
    }

    // ---- Bluetooth off popup ----------------------------------------------

    private var bluetoothSheet: android.app.Dialog? = null

    /** "Not Now" hides the popup until Bluetooth is next switched off. */
    private var bluetoothSnoozed = false

    private val bluetoothReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            when (intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)) {
                android.bluetooth.BluetoothAdapter.STATE_ON -> {
                    bluetoothSheet?.dismiss()
                    current?.onShow()
                }
                android.bluetooth.BluetoothAdapter.STATE_OFF -> {
                    bluetoothSnoozed = false
                    if (resumed) checkBluetooth()
                }
            }
        }
    }

    fun bluetoothOn(): Boolean {
        val adapter = getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter ?: return true
        return adapter.isEnabled
    }

    /** Asks to turn Bluetooth on while it is off; closes on its own once it is on. */
    private fun checkBluetooth() {
        if (bluetoothOn()) { bluetoothSheet?.dismiss(); return }
        if (bluetoothSnoozed || bluetoothSheet != null || isFinishing) return
        val icon = ui.iconTile(R.drawable.ic_bluetooth, 60, 30, Palette.ACCENT_SOFT, Palette.ACCENT_BG, 30)
        val iconRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(icon, LinearLayout.LayoutParams(ui.dp(60), ui.dp(60)))
        }
        lateinit var sheet: android.app.Dialog
        sheet = ui.sheet(
            iconRow,
            ui.vstack(
                4,
                ui.text("Bluetooth Is Off", 18f, Palette.TEXT, Ui.Weight.BOLD).apply { gravity = Gravity.CENTER },
                ui.text("Zuki Nav needs Bluetooth to talk to your dash. This closes by itself once it's on.", 13f, Palette.MUTED)
                    .apply { gravity = Gravity.CENTER },
            ),
            ui.button("Turn On Bluetooth", Ui.Btn.PRIMARY, 50) { turnOnBluetooth() },
            ui.button("Not Now", Ui.Btn.SECONDARY, 48) {
                bluetoothSnoozed = true
                sheet.dismiss()
            },
        )
        sheet.setOnDismissListener { bluetoothSheet = null }
        bluetoothSheet = sheet
    }

    /** Android's own "turn on Bluetooth?" prompt, or Bluetooth settings where that is not allowed. */
    @android.annotation.SuppressLint("MissingPermission")
    fun turnOnBluetooth() {
        val canAsk = Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT)
        try {
            if (canAsk) {
                startActivity(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } else {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } catch (e2: Exception) {
                toast("Turn Bluetooth on from Quick Settings")
            }
        }
    }

    @Deprecated("Back from Setup returns to the tabs")
    override fun onBackPressed() {
        if (current is TripDetailScreen) showTab(Tab.TRIPS)
        else if (current is StopsScreen) showTrip((current as StopsScreen).tripId)
        else if (current is DashTestScreen) showSettingsPage(SettingsScreen.PAGE_TROUBLESHOOTING)
        else if (current is AboutScreen || current is SettingsPage) showTab(Tab.SETTINGS)
        else if (current is SetupScreen && setupComplete()) showTab(Tab.RIDE) else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---- layout -------------------------------------------------------------

    private fun buildRoot(): View {
        content = FrameLayout(this)
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ui.rounded(Palette.NAV_BG, 24, Palette.BORDER)
            elevation = if (Palette.isLight) ui.dp(6).toFloat() else 0f
            setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4))
        }
        tabDock = FrameLayout(this).apply {
            setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(12))
            clipToPadding = false
            addView(tabBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        for (tab in Tab.entries) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(6), 0, ui.dp(6))
                isClickable = true
                isFocusable = true
                contentDescription = tab.label
                background = ui.pressable(Palette.NAV_BG, 20)
                addView(ui.icon(tab.icon, 22, Palette.MUTED), LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)))
                addView(ui.text(tab.label, 11f, Palette.MUTED, Ui.Weight.MEDIUM).apply { gravity = Gravity.CENTER })
                setOnClickListener { showTab(tab) }
            }
            tabBar.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            tabViews += tab to item
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AuroraBackground()
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(tabDock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // Android 15 draws apps edge to edge: keep content clear of the system bars.
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, 0)
                tabDock.setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(12) + bars.bottom)
                navInset = bars.bottom
                padContent()
                insets
            }
        }
        return root
    }

    /** Wraps a screen's content in a padded, scrolling page. */
    fun page(vararg views: View): ScrollView {
        val column = ui.vstack(14, *views).apply {
            setPadding(ui.dp(16), ui.dp(20), ui.dp(16), ui.dp(24))
        }
        return ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(column)
        }
    }

    private fun screen(key: String, make: () -> Screen): Screen = screens.getOrPut(key, make)

    private fun swapTo(next: Screen) {
        if (current === next) { next.onShow(); return }
        if (resumed) current?.onHide()
        current = next
        content.removeAllViews()
        content.addView(next.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (resumed) next.onShow()
    }

    fun showTab(tab: Tab) {
        currentTab = tab
        tabDock.visibility = View.VISIBLE
        padContent()
        for ((t, item) in tabViews) {
            val color = if (t == tab) Palette.ACCENT_SOFT else Palette.MUTED
            (item.getChildAt(0) as android.widget.ImageView).imageTintList = android.content.res.ColorStateList.valueOf(color)
            (item.getChildAt(1) as TextView).apply {
                setTextColor(color)
                typeface = if (t == tab) Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            item.isSelected = t == tab
        }
        swapTo(
            when (tab) {
                Tab.RIDE -> screen("ride") { RideScreen(this) }
                Tab.TRIPS -> screen("trips") { TripsScreen(this) }
                Tab.BIKE -> when (bikeSection) {
                    BIKE_THERMALS -> screen("thermals") { ThermalsScreen(this) }
                    BIKE_SERVICE -> screen("maintenance") { MaintenanceScreen(this) }
                    else -> screen("fuel") { FuelScreen(this) }
                }
                Tab.SETTINGS -> screen("settings") { SettingsScreen(this) }
            },
        )
    }

    /** Opens Dash Test from Settings > Troubleshooting, with the Settings tab still selected. */
    /** Re-reads what the current screen shows, e.g. after a permission prompt. */
    fun refreshScreen() {
        current?.onShow()
    }

    fun showDashTest() = swapTo(screen("dash") { DashTestScreen(this) })

    /** Which part of the Bike tab is open: Fuel, Thermals or Service. */
    private var bikeSection = BIKE_FUEL

    /** Opens the Bike tab on Fuel, Thermals or Service. */
    fun showBikeSection(section: Int) {
        bikeSection = section
        showTab(Tab.BIKE)
    }

    /** "Bike" and the Fuel / Thermals / Service switcher, at the top of each of the three. */
    fun bikeHeader(section: Int): View {
        val switcher = ui.segmented(listOf("Fuel", "Thermals", "Service"), section, fill = true, stay = false) { i ->
            if (i != bikeSection) showBikeSection(i)
        }
        return ui.vstack(
            10,
            ui.title("Bike").apply { setPadding(ui.dp(4), 0, 0, 0) },
            switcher,
        )
    }

    /** Opens one page of Settings (Dash, Calls, Messages...), with the Settings tab still selected. */
    fun showSettingsPage(key: String) {
        val settings = screen("settings") { SettingsScreen(this) } as SettingsScreen
        swapTo(screen("settings:$key") { settings.makePage(key) })
    }

    /** A titled explanation, for the ⓘ notes. */
    fun showInfo(title: String, body: String) {
        AlertDialog.Builder(this, dialogTheme).setTitle(title).setMessage(body).setPositiveButton("OK", null).show()
    }

    /** Opens About & Support, with the Settings tab still selected. */
    fun showAbout() = swapTo(screen("about") { AboutScreen(this) })

    /** Opens the Fuel tab, e.g. from the fuel cells on the Ride tab. */
    fun showFuel() = showBikeSection(BIKE_FUEL)

    /** Opens one trip's details, with the Trips tab still selected. */
    fun showTrip(id: Long) {
        swapTo(TripDetailScreen(this, id))
    }

    /** A merged trip's stops, with the Trips tab still selected. */
    fun showStops(tripId: Long) {
        swapTo(StopsScreen(this, tripId))
    }

    /** Dialog style that matches the app. */
    val dialogTheme: Int
        get() = if (Palette.isLight) android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Dialog_Alert

    fun showSetup() {
        currentTab = null
        tabDock.visibility = View.GONE
        padContent()
        swapTo(screen("setup") { SetupScreen(this) })
    }

    // ---- checks -------------------------------------------------------------

    fun hasBlePermissions(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            granted(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun hasListenerAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(this, MapsNavListener::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun isInstalled(pkg: String): Boolean = try {
        packageManager.getPackageInfo(pkg, 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun mapsInstalled(): Boolean = isInstalled(MapsIconTemplates.MAPS_PKG)

    /** Notifications allowed (asked for from Android 13). */
    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
    }

    /** Contacts allowed, so callers show with their saved names. */
    fun hasContactsPermission(): Boolean = granted(Manifest.permission.READ_CONTACTS)

    /** Android's Contacts prompt; once it has been turned down twice Android won't show it again, so App Info opens instead. */
    fun requestContactsPermission() {
        if (prefs.contactsDenials >= 2) {
            toast("Allow Contacts under Permissions")
            openAppInfo(packageName)
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
        }
    }

    /** Android leaves the app running in the background without battery limits. */
    fun batteryUnrestricted(): Boolean =
        getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) ?: true

    /** Android's prompt to let the app run without battery limits, or its settings list where that fails. */
    @android.annotation.SuppressLint("BatteryLife")
    fun askBatteryUnrestricted() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                openAppInfo(packageName)
            }
        }
    }

    fun setupComplete(): Boolean = hasBlePermissions() && hasListenerAccess() && prefs.clusterAddress != null

    fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    // ---- actions ------------------------------------------------------------

    fun requestBlePermissions() {
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
        when (requestCode) {
            REQ_LOCATION -> {
                if (Places.hasForeground(this) && !Places.hasBackground(this)) askBackgroundLocation()
                else finishLocationRequest()
            }
            REQ_LOCATION_BG -> finishLocationRequest()
            REQ_CONTACTS -> {
                if (hasContactsPermission()) prefs.contactsDenials = 0
                else if (grantResults.isNotEmpty()) prefs.contactsDenials = prefs.contactsDenials + 1
            }
        }
        current?.onShow()
    }

    // ---- location for trip places -----------------------------------------

    private var locationDone: ((Boolean) -> Unit)? = null

    /** True while the location prompts for Start And End Places are open. */
    val askingLocation: Boolean get() = locationDone != null

    /**
     * Asks for location, then for "Allow all the time": a ride ends with the
     * ignition off and the app in the background, which is when the end
     * place is noted. [done] gets whether both were granted.
     */
    fun requestTripLocation(done: (Boolean) -> Unit) {
        if (Places.hasPermission(this)) { done(true); return }
        locationDone = done
        if (!Places.hasForeground(this)) {
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION,
            )
        } else {
            askBackgroundLocation()
        }
    }

    private fun askBackgroundLocation() {
        val label = if (Build.VERSION.SDK_INT >= 30) packageManager.backgroundPermissionOptionLabel else "Allow all the time"
        AlertDialog.Builder(this, dialogTheme)
            .setTitle("Location While Riding")
            .setMessage("To note where a ride ends, Zuki Nav needs location after the ignition is off, when the app is in the background. On the next screen choose \"$label\".")
            .setPositiveButton("Continue") { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_LOCATION_BG)
            }
            .setNegativeButton("Not Now") { _, _ -> finishLocationRequest() }
            .setOnCancelListener { finishLocationRequest() }
            .show()
    }

    private fun finishLocationRequest() {
        val done = locationDone ?: return
        locationDone = null
        done(Places.hasPermission(this))
    }

    fun openListenerSettings() {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(this, MapsNavListener::class.java).flattenToString(),
        )
        try {
            startActivity(detail)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
    }

    /** Suzuki Ride Connect is on the phone; it holds the dash and can cut Zuki Nav off mid-ride. */
    fun rideConnectInstalled(): Boolean = isInstalled(RIDE_CONNECT_PKG)

    /** Android's uninstall prompt for Suzuki Ride Connect, or its App Info page where that fails. */
    fun uninstallRideConnect() {
        try {
            startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$RIDE_CONNECT_PKG")))
        } catch (e: Exception) {
            openAppInfo(RIDE_CONNECT_PKG)
        }
    }

    /** Opens a package's App Info page. */
    fun openAppInfo(pkg: String) {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg")))
        } catch (e: Exception) {
            toast("Could not open App Info")
        }
    }

    /** Starts the dash link if everything it needs is in place. */
    fun startLink(): Boolean {
        if (!hasBlePermissions()) { requestBlePermissions(); return false }
        if (prefs.clusterAddress == null) { showSetup(); return false }
        DashService.start(this)
        return true
    }

    fun shareText(subject: String, body: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
        startActivity(Intent.createChooser(send, subject))
    }

    /**
     * Sends the log as a .txt file: pasted as one long message, apps cut it
     * off after a few hundred lines.
     */
    fun shareLog() {
        val name = "zuki-nav-log-" + java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm", java.util.Locale.US).format(java.util.Date()) + ".txt"
        try {
            val dir = java.io.File(cacheDir, "share").apply { mkdirs() }
            dir.listFiles { f -> f.name.startsWith("zuki-nav-log-") }?.forEach { it.delete() }
            java.io.File(dir, name).writeText(AppLog.snapshot().joinToString("\n"))
            val uri = android.net.Uri.parse("content://${ShareFileProvider.authority(this)}/$name")
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Zuki Nav log")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            AppLog.d("Share", "Could not write the log: $e")
            shareText("Zuki Nav log", AppLog.snapshot().joinToString("\n"))
        }
    }

    fun showLogDialog() {
        val body = TextView(this).apply {
            text = AppLog.snapshot().takeLast(300).joinToString("\n")
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Palette.TEXT_SOFT)
            setTextIsSelectable(true)
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }
        AlertDialog.Builder(this, dialogTheme)
            .setTitle("Log")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNeutralButton("Share") { _, _ -> shareLog() }
            .show()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** Opens the system picture picker for the rider avatar. */
    fun pickAvatarPhoto() {
        val pick = if (Build.VERSION.SDK_INT >= 33) {
            Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
        } else {
            Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(pick, REQ_AVATAR)
        } catch (e: Exception) {
            toast("No picture picker found")
        }
    }

    @Deprecated("Picture picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_WAKE) {
            val cb = wakeCallback
            wakeCallback = null
            val ok = resultCode == RESULT_OK && com.th4rv4.zukinav.service.Wake.startWatching(this)
            cb?.invoke(ok)
            return
        }
        if (requestCode == REQ_BACKUP_CREATE || requestCode == REQ_BACKUP_OPEN || requestCode == REQ_PAPER) {
            val cb = fileCallback
            fileCallback = null
            cb?.invoke(if (resultCode == RESULT_OK) data?.data else null)
            return
        }
        if (requestCode != REQ_AVATAR || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val ok = Avatars.savePhoto(this, uri)
            runOnUiThread {
                if (ok) {
                    prefs.avatar = Avatars.PHOTO
                    current?.onShow()
                } else {
                    toast("Could not use that picture")
                }
            }
        }.start()
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private var fileCallback: ((android.net.Uri?) -> Unit)? = null
    private var wakeCallback: ((Boolean) -> Unit)? = null

    /** A Wake On Ignition setup is under way: Android's list is open or about to be. */
    private var wakeBusy = false

    /** Wake On Ignition: Android's one-time prompt to register the bike. */
    fun setUpWake(onDone: (Boolean) -> Unit) {
        // A second tap while Android is still looking used to fail the first
        // setup too, and the app took the dash back while Android's list was
        // open, so the bike never showed up in it.
        if (wakeBusy) {
            toast("Still looking for your bike")
            return
        }
        wakeBusy = true
        // Never stuck, whatever happens to Android's prompt.
        content.postDelayed({ wakeBusy = false }, 2 * 60_000L)
        val service = com.th4rv4.zukinav.service.DashService
        // While connected the dash stops advertising and Android can't list it.
        service.holdForSetup(true)
        var done = false
        val finish: (Boolean) -> Unit = { ok ->
            if (!done) {
                done = true
                wakeBusy = false
                service.holdForSetup(false)
                onDone(ok)
            }
        }
        wakeCallback = finish
        content.postDelayed({
            com.th4rv4.zukinav.service.Wake.setUp(this, REQ_WAKE) { ok ->
                if (!ok) {
                    wakeCallback = null
                    finish(false)
                }
            }
        }, 2_500)
    }

    /** Android's folder picker, for where backups go. */
    fun chooseBackupFolder(onPicked: (android.net.Uri?) -> Unit) {
        fileCallback = onPicked
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_BACKUP_CREATE)
    }

    /** Android's file picker, to choose a backup to restore. */
    fun pickBackupToRestore(onPicked: (android.net.Uri?) -> Unit) {
        fileCallback = onPicked
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(i, REQ_BACKUP_OPEN)
    }

    /** Android's file picker, for a photo or PDF of a paper. */
    fun pickPaperFile(onPicked: (android.net.Uri?) -> Unit) {
        fileCallback = onPicked
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_PAPER)
        } catch (_: Exception) {
            fileCallback = null
            toast("No file picker found")
        }
    }

    /** Starts the app afresh, e.g. after a restore, so everything reads the restored data. */
    /**
     * Restore From Backup, from Settings or the setup screen: picks the zip,
     * shows what's in it, restores and restarts.
     */
    fun restoreFromBackup() {
        pickBackupToRestore { uri ->
            if (uri == null) return@pickBackupToRestore
            Thread {
                val info = try {
                    contentResolver.openInputStream(uri)?.use { com.th4rv4.zukinav.backup.Backup.peek(it) }
                } catch (e: com.th4rv4.zukinav.backup.Backup.NewerBackup) {
                    runOnUiThread { toast(e.message ?: "Update Zuki Nav first") }
                    return@Thread
                } catch (e: Exception) {
                    null
                }
                runOnUiThread {
                    if (info == null) {
                        toast("That isn't a Zuki Nav backup")
                        return@runOnUiThread
                    }
                    val made = backupTime(info.createdAt)
                    AlertDialog.Builder(this, dialogTheme)
                        .setTitle("Restore This Backup?")
                        .setMessage(
                            "From $made (Zuki Nav v${info.appVersion})\n" +
                                "${info.trips} trips · ${info.routes} routes · bike, fuel, papers, reminders and settings\n\n" +
                                "This replaces what's in the app now. The app restarts when it's done.",
                        )
                        .setPositiveButton("Restore") { _, _ ->
                            Thread {
                                val ok = try {
                                    contentResolver.openInputStream(uri)?.use { com.th4rv4.zukinav.backup.Backup.restore(this, it) } != null
                                } catch (e: Exception) {
                                    com.th4rv4.zukinav.util.AppLog.d("Backup", "Restore failed: $e")
                                    false
                                }
                                runOnUiThread {
                                    if (ok) {
                                        prefs.restoredNote = "Restored from $made · ${info.trips} " + (if (info.trips == 1) "ride" else "rides")
                                        toast("Restored. Restarting…")
                                        window.decorView.postDelayed({ restartApp() }, 800)
                                    } else {
                                        toast("Couldn't restore that file. Nothing was changed.")
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

    /** "17 Nov, 1:28 AM" */
    private fun backupTime(at: Long): String {
        val d = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
        val pattern = if (android.text.format.DateFormat.is24HourFormat(this)) "d MMM, HH:mm" else "d MMM, h:mm a"
        return d.format(java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.US))
    }

    fun restartApp() {
        stopService(Intent(this, com.th4rv4.zukinav.service.DashService::class.java))
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (launch != null) startActivity(launch)
        Runtime.getRuntime().exit(0)
    }

    companion object {
        /** Opens the refill form for this refill id (from the "Refuelled?" notification). */
        const val EXTRA_REFILL = "refill"

        /** From the ongoing notification: opens the Ride tab. */
        const val EXTRA_OPEN_RIDE = "open_ride"
        const val EXTRA_MAINTENANCE = "maintenance"
        private const val REQ_NOTIFICATIONS = 9
        private const val REQ_CONTACTS = 10
        const val RIDE_CONNECT_PKG = "suzuki.com.suzuki"
        const val BIKE_FUEL = 0
        const val BIKE_THERMALS = 1
        const val BIKE_SERVICE = 2
        private const val REQ_LOCATION = 2
        private const val REQ_LOCATION_BG = 3
        private const val STATE_TAB = "tab"
        private const val REQ_AVATAR = 4
        private const val REQ_BACKUP_CREATE = 5
        private const val REQ_BACKUP_OPEN = 6
        private const val REQ_WAKE = 7
        private const val REQ_PAPER = 8
    }
}

/** One page of the app. */
abstract class Screen(protected val host: MainActivity) {
    protected val ui: Ui get() = host.ui
    protected val prefs: Prefs get() = host.prefs
    abstract val view: View

    /** Called when the page becomes visible (and on every resume). */
    open fun onShow() {}

    /** Called when the page is hidden or the app goes to the background. */
    open fun onHide() {}
}
