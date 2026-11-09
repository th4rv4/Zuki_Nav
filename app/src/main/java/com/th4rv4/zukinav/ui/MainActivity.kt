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
import com.th4rv4.zukinav.util.Prefs

/**
 * Hosts the three tabs (Ride, Dash Test, Settings) and the first-run Setup
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
    private val tabViews = ArrayList<Pair<Tab, LinearLayout>>()

    enum class Tab(val label: String, val icon: Int) {
        RIDE("Ride", R.drawable.ic_tab_ride),
        DASH_TEST("Dash Test", R.drawable.ic_tab_dash),
        SETTINGS("Settings", R.drawable.ic_tab_settings),
    }

    private val screens = HashMap<String, Screen>()
    private var current: Screen? = null
    private var currentTab: Tab? = null
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        ui = Ui(this)
        setContentView(buildRoot())
        if (setupComplete()) showTab(Tab.RIDE) else showSetup()
        checkForUpdateIfDue()
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
        if (current is SetupScreen && setupComplete()) showTab(Tab.RIDE) else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---- layout -------------------------------------------------------------

    private fun buildRoot(): View {
        content = FrameLayout(this)
        tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Palette.NAV_BG)
            setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
        }
        for (tab in Tab.entries) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(6), 0, ui.dp(6))
                isClickable = true
                isFocusable = true
                contentDescription = tab.label
                background = ui.pressable(Palette.NAV_BG, 12)
                addView(ui.icon(tab.icon, 22, Palette.MUTED), LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)))
                addView(ui.text(tab.label, 12f, Palette.MUTED, Ui.Weight.MEDIUM).apply { gravity = Gravity.CENTER })
                setOnClickListener { showTab(tab) }
            }
            tabBar.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            tabViews += tab to item
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(View(this@MainActivity).apply { setBackgroundColor(0xFF1C222C.toInt()) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(1)))
            addView(tabBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // Android 15 draws apps edge to edge: keep content clear of the system bars.
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, 0)
                tabBar.setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6) + bars.bottom)
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
        tabBar.visibility = View.VISIBLE
        for ((t, item) in tabViews) {
            val color = if (t == tab) Palette.ACCENT else Palette.MUTED
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
                Tab.DASH_TEST -> screen("dash") { DashTestScreen(this) }
                Tab.SETTINGS -> screen("settings") { SettingsScreen(this) }
            },
        )
    }

    fun showSetup() {
        currentTab = null
        tabBar.visibility = View.GONE
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
        current?.onShow()
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
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Log")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNeutralButton("Share") { _, _ -> shareLog() }
            .show()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
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
