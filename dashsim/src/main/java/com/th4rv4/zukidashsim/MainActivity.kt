package com.th4rv4.zukidashsim

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zuki Dash Sim: this phone plays the Gixxer SF 250's dash for Zuki Sim on
 * the other phone. Switch the ignition on, start the engine, pick a speed,
 * and fast-forward to cover a long ride in a few minutes.
 */
@SuppressLint("SetTextI18n")
class MainActivity : Activity(), DashPeripheral.Events {

    private val bike = Bike()
    private lateinit var dash: DashPeripheral
    private val main = Handler(Looper.getMainLooper())

    /** Ride time: real time, sped up and skipped ahead as asked. */
    private var rideTime = System.currentTimeMillis()
    private var speedUp = 1.0
    private var silent = false

    private var lastTick = 0L
    private var lastReading = 0L
    private var lastSave = 0L
    private var lastUi = 0L

    private val logLines = ArrayDeque<String>()
    private val shown = LinkedHashMap<String, String>()

    private lateinit var status: TextView
    private lateinit var ignitionBtn: Button
    private lateinit var engineBtn: Button
    private lateinit var speedText: TextView
    private lateinit var speedBar: SeekBar
    private lateinit var timeText: TextView
    private lateinit var speedUpBtns: List<Pair<Double, Button>>
    private lateinit var silentBtn: Button
    private lateinit var fuelText: TextView
    private lateinit var fuelBar: SeekBar
    private lateinit var readout: TextView
    private lateinit var dashShows: TextView
    private lateinit var logView: TextView
    private lateinit var checklist: Checklist
    private var lastChecklist = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dash = DashPeripheral(this, this)
        load()
        setContentView(build())
        askPermissions()
        lastTick = SystemClock.elapsedRealtime()
        main.post(tick)
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        save()
        if (bike.ignitionOn) dash.powerOff()
        super.onDestroy()
    }

    // ---- The loop ---------------------------------------------------------

    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val real = now - lastTick
            lastTick = now
            val ride = (real * speedUp).toLong()
            rideTime += ride
            val wasRunning = bike.engineOn
            bike.step(ride)
            if (wasRunning && !bike.engineOn) log("Out of fuel: the engine stopped")

            // The real dash reports every 5 s; sped up, as often as Bluetooth keeps up with.
            val every = maxOf(MIN_READING_MS, (READING_MS / speedUp).toLong())
            if (bike.ignitionOn && !silent && now - lastReading >= every) {
                sendReading()
                lastReading = now
            }
            if (now - lastUi >= 250) {
                refresh()
                lastUi = now
            }
            if (now - lastChecklist >= 3000) {
                checklist.refresh()
                lastChecklist = now
            }
            if (now - lastSave >= 5000) {
                save()
                lastSave = now
            }
            main.postDelayed(this, 100)
        }
    }

    private fun sendReading() {
        // Ride time first, so Zuki Sim stamps the reading with it (not its own
        // clock, which can be hours behind after Skip 2 h or a restart).
        dash.send(bike.simFrame(rideTime, speedUp))
        dash.send(bike.reading())
    }

    /** Ride time and position only, so Zuki Sim hears about a change straight away. */
    private fun sendSim() {
        if (bike.ignitionOn && !silent) dash.send(bike.simFrame(rideTime, speedUp))
    }

    // ---- Controls ---------------------------------------------------------

    private fun toggleIgnition() {
        if (bike.ignitionOn) {
            bike.ignitionOn = false
            // Tell Zuki Sim the bike is off before the link goes.
            dash.send(bike.simFrame(rideTime, speedUp))
            log("Ignition off")
            main.postDelayed({ if (!bike.ignitionOn) dash.powerOff() }, 300)
        } else {
            bike.ignitionOn = true
            log("Ignition on")
            dash.powerOn()
        }
        refresh()
    }

    private fun toggleEngine() {
        if (!bike.ignitionOn) return toast("Switch the ignition on first")
        if (!bike.engineOn && bike.fuelL <= 0.0) return toast("No fuel")
        bike.engineOn = !bike.engineOn
        log(if (bike.engineOn) "Engine started (${bike.engineC.toInt()} °C)" else "Engine stopped")
        sendSim()
        refresh()
    }

    private fun setSpeedUp(x: Double) {
        speedUp = x
        log("Time ${x.toInt()}×")
        sendSim()
        refresh()
    }

    private fun skip2h() {
        if (bike.engineOn) return toast("Stop the engine first: Skip is for a break")
        val twoHours = 2 * 60 * 60_000L
        rideTime += twoHours
        bike.step(twoHours)
        log("Skipped 2 h (engine now ${bike.engineC.toInt()} °C)")
        sendSim()
        refresh()
    }

    private fun toggleSilent() {
        silent = !silent
        log(if (silent) "Gone silent: still connected, sending nothing" else "Sending again")
        refresh()
    }

    // ---- What the phone sends ---------------------------------------------

    override fun onFrame(frame: ByteArray) {
        val s = FrameText.describe(frame)
        // The clock repeats every couple of seconds: show it, don't log it.
        if (s.screen != "Clock") log("<- ${s.screen}: ${s.text}")
        shown[s.screen] = s.text
        refresh()
    }

    override fun log(line: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(rideTime))
        logLines.addFirst("$t  $line")
        while (logLines.size > 150) logLines.removeLast()
        if (::logView.isInitialized) logView.text = logLines.joinToString("\n")
    }

    override fun onLinkChanged() {
        refresh()
        if (::checklist.isInitialized) checklist.refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::checklist.isInitialized) checklist.refresh()
    }

    // ---- Screen ------------------------------------------------------------

    private fun refresh() {
        if (!::logView.isInitialized) return
        status.text = when {
            dash.resetting -> if (bike.ignitionOn) "Bluetooth coming on…" else "Bluetooth going off…"
            !bike.ignitionOn -> "Ignition off: the dash is dark"
            dash.device == null && dash.advertising -> "Waiting for the phone (as ${DashPeripheral.NAME})"
            dash.device == null -> "Starting…"
            silent -> "Connected, silent"
            else -> "Connected to ${dash.device?.address}"
        }
        ignitionBtn.text = if (bike.ignitionOn) "Ignition: On" else "Ignition: Off"
        engineBtn.text = if (bike.engineOn) "Engine: Running" else "Engine: Off"
        engineBtn.isEnabled = bike.ignitionOn
        speedText.text = "Speed ${bike.speedKmh.toInt()} km/h  (throttle ${bike.targetKmh} km/h)"
        val d = SimpleDateFormat("EEE d MMM, HH:mm:ss", Locale.US).format(Date(rideTime))
        timeText.text = "Ride time $d  (${speedUp.toInt()}×)"
        for ((x, b) in speedUpBtns) b.alpha = if (x == speedUp) 1f else 0.5f
        silentBtn.text = if (silent) "Go Silent: On" else "Go Silent: Off"
        fuelText.text = "Fuel %.1f L of %.0f  (%d bars)".format(bike.fuelL, Bike.TANK_L, bike.fuelBars)
        readout.text = "ODO %d km   Trip A %.1f   Trip B %.1f\nEngine %d °C   Position %.5f, %.5f".format(
            bike.odoKm.toLong(), bike.tripAKm, bike.tripBKm, Math.round(bike.engineC), bike.lat, bike.lng,
        )
        dashShows.text = if (shown.isEmpty()) "Nothing yet" else shown.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    }

    private fun build(): View {
        val pad = dp(16)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun title(s: String) = TextView(this).apply {
            text = s
            textSize = 13f
            alpha = 0.7f
            setPadding(0, dp(18), 0, dp(6))
            typeface = Typeface.DEFAULT_BOLD
        }
        fun label(size: Float = 15f, mono: Boolean = false) = TextView(this).apply {
            textSize = size
            if (mono) typeface = Typeface.MONOSPACE
        }
        fun btn(s: String, onClick: () -> Unit) = Button(this).apply {
            text = s
            isAllCaps = false
            setOnClickListener { onClick() }
        }
        fun row(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            for (v in views) addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        col.addView(TextView(this).apply {
            text = "Zuki Dash Sim"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        })
        status = label(14f)
        col.addView(status)
        checklist = Checklist(this, ::dp) { bike.ignitionOn }
        col.addView(checklist.view)
        checklist.refresh()

        col.addView(title("Bike"))
        ignitionBtn = btn("Ignition") { toggleIgnition() }
        engineBtn = btn("Engine") { toggleEngine() }
        col.addView(row(ignitionBtn, engineBtn))
        speedText = label()
        col.addView(speedText)
        speedBar = SeekBar(this).apply {
            max = 140
            progress = bike.targetKmh
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    bike.targetKmh = p
                    refresh()
                }
                override fun onStartTrackingTouch(s: SeekBar) = Unit
                override fun onStopTrackingTouch(s: SeekBar) = Unit
            })
        }
        col.addView(speedBar)
        col.addView(row(btn("Stop") { speedBar.progress = 0 }, btn("30") { speedBar.progress = 30 },
            btn("60") { speedBar.progress = 60 }, btn("100") { speedBar.progress = 100 }))

        col.addView(title("Time"))
        timeText = label(14f)
        col.addView(timeText)
        speedUpBtns = listOf(1.0, 10.0, 60.0).map { x -> x to btn("${x.toInt()}×") { setSpeedUp(x) } }
        col.addView(row(*speedUpBtns.map { it.second }.toTypedArray(), btn("Skip 2 h") { skip2h() }))

        col.addView(title("Faults"))
        silentBtn = btn("Go Silent") { toggleSilent() }
        col.addView(row(btn("Drop Link") { dash.dropLink() }, silentBtn))

        col.addView(title("Fuel And Trips"))
        fuelText = label(14f)
        col.addView(fuelText)
        fuelBar = SeekBar(this).apply {
            max = (Bike.TANK_L * 10).toInt()
            progress = (bike.fuelL * 10).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) bike.fuelL = p / 10.0
                    refresh()
                }
                override fun onStartTrackingTouch(s: SeekBar) = Unit
                override fun onStopTrackingTouch(s: SeekBar) = Unit
            })
        }
        col.addView(fuelBar)
        col.addView(row(btn("Reset Trip A") { bike.tripAKm = 0.0; refresh() }, btn("Reset Trip B") { bike.tripBKm = 0.0; refresh() }))
        readout = label(14f, mono = true)
        col.addView(readout)

        col.addView(title("What The Dash Shows"))
        dashShows = label(14f)
        col.addView(dashShows)

        col.addView(title("Log"))
        col.addView(row(btn("Copy Log") { copyLog() }))
        logView = label(11f, mono = true)
        logView.text = logLines.joinToString("\n")
        col.addView(logView)

        refresh()
        return ScrollView(this).apply { addView(col) }
    }

    private fun copyLog() {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("Zuki Dash Sim log", logLines.reversed().joinToString("\n")))
        toast("Log copied")
    }

    // ---- Saved between runs -------------------------------------------------

    private fun load() {
        val p = getSharedPreferences("sim", Context.MODE_PRIVATE)
        if (!p.contains("odo")) {
            startWherePhoneIs()
            return
        }
        bike.odoKm = p.getFloat("odo", 12_345f).toDouble()
        bike.tripAKm = p.getFloat("tripA", 0f).toDouble()
        bike.tripBKm = p.getFloat("tripB", 0f).toDouble()
        bike.fuelL = p.getFloat("fuel", 10f).toDouble()
        bike.movedKm = p.getFloat("moved", 0f).toDouble()
        bike.lat = java.lang.Double.longBitsToDouble(p.getLong("lat", java.lang.Double.doubleToLongBits(bike.lat)))
        bike.lng = java.lang.Double.longBitsToDouble(p.getLong("lng", java.lang.Double.doubleToLongBits(bike.lng)))
        // Ride time never goes back: carry on from where it got to, plus the time since.
        val savedRide = p.getLong("ride", 0L)
        val savedWall = p.getLong("wall", 0L)
        val since = (System.currentTimeMillis() - savedWall).coerceAtLeast(0L)
        rideTime = maxOf(savedRide + since, System.currentTimeMillis())
        // The engine cooled while the app was closed.
        bike.engineC = p.getFloat("engine", Bike.AMBIENT_C.toFloat()).toDouble()
        bike.step(since)
        startWherePhoneIs()
    }

    private fun save() {
        getSharedPreferences("sim", Context.MODE_PRIVATE).edit()
            .putFloat("odo", bike.odoKm.toFloat())
            .putFloat("tripA", bike.tripAKm.toFloat())
            .putFloat("tripB", bike.tripBKm.toFloat())
            .putFloat("fuel", bike.fuelL.toFloat())
            .putFloat("moved", bike.movedKm.toFloat())
            .putFloat("engine", bike.engineC.toFloat())
            .putLong("lat", java.lang.Double.doubleToLongBits(bike.lat))
            .putLong("lng", java.lang.Double.doubleToLongBits(bike.lng))
            .putLong("ride", rideTime)
            .putLong("wall", System.currentTimeMillis())
            .apply()
    }

    /**
     * Puts the made-up ride where this phone is: on the first run, and again
     * whenever it has ended up far away (rides before the loop went in
     * wandered off across the map).
     */
    @SuppressLint("MissingPermission")
    private fun startWherePhoneIs() {
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        val here = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time } ?: return
        val away = FloatArray(1)
        android.location.Location.distanceBetween(bike.lat, bike.lng, here.latitude, here.longitude, away)
        if (bike.movedKm > 0.0 && away[0] < FAR_M) return
        bike.lat = here.latitude
        bike.lng = here.longitude
        bike.movedKm = 0.0
        log("Made-up ride starts where this phone is")
    }

    private fun askPermissions() {
        val want = buildList {
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) {
                add(android.Manifest.permission.BLUETOOTH_ADVERTISE)
                add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (want.isNotEmpty()) requestPermissions(want.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startWherePhoneIs()
        refresh()
        if (::checklist.isInitialized) checklist.refresh()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val READING_MS = 5000.0
        const val MIN_READING_MS = 250L
        const val FAR_M = 150_000f
    }
}
