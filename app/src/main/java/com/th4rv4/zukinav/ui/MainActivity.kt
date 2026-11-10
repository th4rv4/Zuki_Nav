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
        FUEL("Fuel", R.drawable.ic_fuel),
        DASH_TEST("Test", R.drawable.ic_tab_dash),
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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRefillFrom(intent)
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
    }

    override fun onPause() {
        resumed = false
        current?.onHide()
        super.onPause()
    }

    @Deprecated("Back from Setup returns to the tabs")
    override fun onBackPressed() {
        if (current is TripDetailScreen) showTab(Tab.TRIPS)
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
                Tab.FUEL -> screen("fuel") { FuelScreen(this) }
                Tab.DASH_TEST -> screen("dash") { DashTestScreen(this) }
                Tab.SETTINGS -> screen("settings") { SettingsScreen(this) }
            },
        )
    }

    /** Opens the Fuel tab, e.g. from the fuel cells on the Ride tab. */
    fun showFuel() = showTab(Tab.FUEL)

    /** Opens one trip's details, with the Trips tab still selected. */
    fun showTrip(id: Long) {
        swapTo(TripDetailScreen(this, id))
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

    /** Opens a package's App Info page, e.g. to force-stop Suzuki Ride Connect. */
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

    fun shareLog() = shareText("Zuki Nav log", AppLog.snapshot().joinToString("\n"))

    fun showLogDialog() {
        val body = TextView(this).apply {
            text = AppLog.snapshot().takeLast(300).joinToString("\n")
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(Palette.TEXT_SOFT)
            setTextIsSelectable(true)
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
        }
        val scroll = ScrollView(this).apply { addView(body) }
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

    companion object {
        /** Opens the refill form for this refill id (from the "Refuelled?" notification). */
        const val EXTRA_REFILL = "refill"
        private const val REQ_LOCATION = 2
        private const val REQ_LOCATION_BG = 3
        private const val STATE_TAB = "tab"
        private const val REQ_AVATAR = 4
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
