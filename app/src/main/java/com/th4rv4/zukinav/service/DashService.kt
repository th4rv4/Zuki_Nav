package com.th4rv4.zukinav.service

import com.th4rv4.zukinav.widget.RangeWidget
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.format.DateFormat
import com.th4rv4.zukinav.R
import com.th4rv4.zukinav.alerts.Alert
import com.th4rv4.zukinav.ble.ClusterLink
import com.th4rv4.zukinav.fuel.FuelStore
import com.th4rv4.zukinav.fuel.Refill
import com.th4rv4.zukinav.maint.MaintNotifier
import com.th4rv4.zukinav.maint.MaintStore
import com.th4rv4.zukinav.nav.NavRepository
import com.th4rv4.zukinav.nav.RouteWatch
import com.th4rv4.zukinav.nav.NavSnapshot
import com.th4rv4.zukinav.protocol.BikeData
import com.th4rv4.zukinav.protocol.ClusterIcon
import com.th4rv4.zukinav.protocol.ClusterModel
import com.th4rv4.zukinav.protocol.ClusterModels
import com.th4rv4.zukinav.protocol.DashDistance
import com.th4rv4.zukinav.protocol.SuzukiFrames
import com.th4rv4.zukinav.thermal.ThermalSession
import com.th4rv4.zukinav.thermal.Thermals
import com.th4rv4.zukinav.trips.DropJudge
import com.th4rv4.zukinav.trips.DropWatch
import com.th4rv4.zukinav.trips.TripLog
import com.th4rv4.zukinav.ui.MainActivity
import com.th4rv4.zukinav.util.AppLog
import com.th4rv4.zukinav.util.Prefs
import java.time.LocalTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Keeps the link to the cluster and feeds it, with the same timing as Suzuki
 * Ride Connect:
 *  - after connecting, the hello frame 4 times, 200 ms apart
 *  - while navigating, the nav frame every 200 ms
 *  - the status/clock frame every 2 s
 *  - when navigation ends, the nav frame with the active flag cleared, 10 times
 */
class DashService : Service(), ClusterLink.Listener {

    private lateinit var prefs: Prefs
    private lateinit var link: ClusterLink
    private val main = Handler(Looper.getMainLooper())

    private var tick = 0
    private var helloLeft = 0
    private var endFramesLeft = 0
    private var navWasActive = false
    /** A Maps route reached the dash since the last clear (for the log only). */
    private var routeShown = false
    /** Dash Test frame (an icon or test text) and until when to show it. */
    private var testFrame: ((ClusterModel) -> ByteArray)? = null
    private var testUntil = 0L
    /** Startup animation: ticks to wait after the hello, then frames left to show. */
    private var splashWait = 0
    private var splashLeft = 0
    private var greetingPending = false

    // The text on the dash's call screen right now (greeting or alert), if any.
    private var retries = 0
    /** When the bike was last lost (or the search began); 0 while connected. */
    private var lostAt = 0L
    /** In battery-saving mode, alternate a quick direct try with a quiet wait. */
    private var directTurn = true
    private var slowLogged = false
    private var deepLogged = false
    private var lastConnectWasBackground = false
    private var lastNavHex: String? = null
    private var statusLogged = false
    /** Status frames still to send with the "clear" flags set. */
    private var clearSmsLeft = 0
    private var clearMissedLeft = 0

    /** A direct connect that hangs is abandoned after this and retried. */
    private val connectTimeout = Runnable {
        when (link.state) {
            ClusterLink.State.CONNECTING -> {
                AppLog.d("Dash", if (lastConnectWasBackground) "Still no bike, trying directly" else "Bike not answering, retrying")
                link.disconnect()
            }
            // e.g. Bluetooth was off: the link never left DISCONNECTED, so
            // nothing else would schedule another attempt.
            ClusterLink.State.DISCONNECTED -> if (prefs.autoConnect) connectIfNeeded()
            ClusterLink.State.READY -> Unit
        }
    }

    /** The bike went off or a ride started or ended: the notification says so. */
    private val tripListener: () -> Unit = { main.post { updateNotification() } }

    private val navListener: (NavSnapshot?) -> Unit = { nav ->
        updateNotification()
        // A route starting wakes the loop at once instead of at the next minute.
        if (nav != null && !loopFast) wake()
    }

    /**
     * Follows the phone's Bluetooth switch. When it goes off the link is
     * closed straight away (Android doesn't always report the drop, which
     * left the app looking connected); when it comes back on, the app
     * reconnects by itself.
     */
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> main.post { onBluetoothOff() }
                BluetoothAdapter.STATE_ON -> main.post { onBluetoothOn() }
            }
        }
    }

    /** Latest fuel, ODO and trip readings from the dash, while connected. */
    @Volatile private var bike: BikeData? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLog.attach(this)
        prefs = Prefs(this)
        link = ClusterLink(this, this)
        instance = this
        lostAt = System.currentTimeMillis()
        bluetoothOff = !isBluetoothOn()
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothReceiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(bluetoothReceiver, filter)
        NavRepository.addListener(navListener)
        TripLog.addListener(tripListener)
        TripLog.finishIfIdle(this)
        Wake.rewatch(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> connectIfNeeded()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        DropWatch.cancel()
        if (bike != null) {
            TripLog.onDisconnected(this)
            com.th4rv4.zukinav.trips.Parking.onBikeOff(this)
        }
        instance = null
        try { unregisterReceiver(bluetoothReceiver) } catch (_: Exception) {}
        main.removeCallbacksAndMessages(null)
        NavRepository.removeListener(navListener)
        TripLog.removeListener(tripListener)
        link.release()
        notifyState()
        RangeWidget.refresh(this)
        super.onDestroy()
    }

    // ---- connection -------------------------------------------------------

    private fun isBluetoothOn(): Boolean =
        (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.isEnabled == true

    private fun onBluetoothOff() {
        if (bluetoothOff) return
        bluetoothOff = true
        AppLog.d("Dash", "Bluetooth turned off, waiting for it to come back")
        main.removeCallbacks(connectTimeout)
        link.disconnect()
        updateNotification()
        notifyState()
    }

    private fun onBluetoothOn() {
        if (!bluetoothOff) return
        bluetoothOff = false
        retries = 0
        lostAt = System.currentTimeMillis()
        slowLogged = false
        deepLogged = false
        directTurn = true
        AppLog.d("Dash", "Bluetooth back on, reconnecting")
        // Give the Bluetooth stack a moment to settle.
        main.postDelayed({ connectIfNeeded() }, 1_500)
        updateNotification()
        notifyState()
    }

    /** True while Wake On Ignition is being set up: the dash only shows up in Android's list when nothing is connected to it. */
    private var setupHold = false
    private val releaseHold = Runnable { holdForSetup(false) }
    private var bondLogged = false

    private fun connectIfNeeded() {
        if (link.state != ClusterLink.State.DISCONNECTED) return
        if (bluetoothOff || setupHold) return
        if (isBikeBonded(this) && !bondLogged) {
            AppLog.d("Dash", "The bike is paired in Android's Bluetooth settings; the dash refuses the app while it is. Forget it there.")
            bondLogged = true
        }
        val addr = prefs.clusterAddress
        if (addr == null) {
            AppLog.d("Dash", "No cluster chosen yet")
            return
        }
        if (!hasConnectPermission(this)) {
            AppLog.d("Dash", "Bluetooth permission missing")
            return
        }
        // For the first minutes after losing the bike: direct connects,
        // retried with backoff (see onStateChanged), so short stops reconnect
        // quickly. After that, to spare the battery while the bike is parked,
        // Android's low-power background connect waits for it, with a quick
        // direct try every couple of minutes (the background connect alone
        // can take minutes to notice the bike).
        // ...except while a dash lost mid-ride may come back any moment.
        val slow = isSaving() && !TripLog.dashLost && !DropWatch.watching
        // Wake On Ignition: Android watches for the bike, so stop until it does.
        // ...but not while a Maps route is open: the notification shows the way left.
        if (slow && prefs.autoConnect && Wake.isSetUp(this) && !RouteWatch.isOpen(this)) {
            AppLog.d("Dash", "No bike for ${FAST_WINDOW_MS / 60_000} min, sleeping until Android sees it again")
            link.disconnect()
            stopSelf()
            return
        }
        // Deep sleep: after an hour parked, only Android's low-power wait.
        val deep = lostAt > 0 && System.currentTimeMillis() - lostAt > DEEP_SLEEP_MS
        if (slow && !slowLogged) {
            AppLog.d("Dash", "No bike for ${FAST_WINDOW_MS / 60_000} min, saving battery: waiting quietly, quick check every 2 min")
            slowLogged = true
        }
        if (deep && !deepLogged) {
            AppLog.d("Dash", "No bike for ${DEEP_SLEEP_MS / 60_000} min, deep sleep: low-power wait only")
            deepLogged = true
        }
        val background = slow && (deep || !directTurn)
        if (slow) directTurn = !directTurn
        lastConnectWasBackground = background
        link.connect(addr, background = background)
        main.removeCallbacks(connectTimeout)
        main.postDelayed(connectTimeout, if (background) SLOW_WAIT_MS else CONNECT_TIMEOUT_MS)
    }

    private fun isSaving(): Boolean =
        lostAt > 0 && System.currentTimeMillis() - lostAt > FAST_WINDOW_MS

    override fun onStateChanged(state: ClusterLink.State) {
        main.post {
            AppLog.d("Dash", "Link $state")
            when (state) {
                ClusterLink.State.READY -> {
                    main.removeCallbacks(connectTimeout)
                    // After the welcome and greeting have had their turn.
                    main.removeCallbacks(showOwed)
                    main.postDelayed(showOwed, OWED_DELAY_MS)
                    DropWatch.cancel()
                    main.removeCallbacks(lostGiveUp)
                    if (TripLog.dashLost) AppLog.d("Dash", "Dash is back, the ride carries on")
                    lastReadingAt = System.currentTimeMillis()
                    main.removeCallbacks(silentCheck)
                    main.postDelayed(silentCheck, SILENT_CHECK_MS)
                    retries = 0
                    lostAt = 0
                    slowLogged = false
                    deepLogged = false
                    directTurn = true
                    lastNavHex = null
                    statusLogged = false
                    helloLeft = 4
                    tick = 0
                    statusAt = 0L
                    main.removeCallbacks(loop)
                    main.post(loop)
                }
                ClusterLink.State.DISCONNECTED -> {
                    if (lostAt == 0L) lostAt = System.currentTimeMillis()
                    main.removeCallbacks(silentCheck)
                    val last = bike
                    if (last != null) {
                        TripLog.onDisconnected(this)
                        val fresh = System.currentTimeMillis() - lastReadingAt < DropJudge.FRESH_READING_MS
                        DropWatch.start(this, last.speedKmh.takeIf { fresh }) { onDropJudged(it) }
                    }
                    bike = null
                    main.removeCallbacks(loop)
                    main.removeCallbacks(connectTimeout)
                    if (bluetoothOff) {
                        AppLog.d("Dash", "Waiting for Bluetooth")
                    } else if (prefs.autoConnect && instance != null) {
                        // 2 s, 4 s, 8 s, then every 15 s while the bike is off;
                        // in battery-saving mode straight on to the next step.
                        val delay = if (isSaving()) 500L else minOf(2_000L shl retries.coerceAtMost(3), 15_000L)
                        retries++
                        if (!isSaving()) AppLog.d("Dash", "Reconnecting in ${delay / 1000} s")
                        main.postDelayed({ connectIfNeeded() }, delay)
                    }
                }
                ClusterLink.State.CONNECTING -> Unit
            }
            updateNotification()
            notifyState()
            RangeWidget.refresh(this)
        }
    }

    // ---- alerts that came while the dash was away ----------------------------

    private val showOwed = Runnable {
        if (link.state != ClusterLink.State.READY) return@Runnable
        val missed = owedMissed
        val message = owedMessage
        if (missed == null && message == null) return@Runnable
        AppLog.d("Dash", "Showing what came in while the dash was away")
        missed?.let { showAlert(it) }
        // One after the other, so each gets its moment on screen.
        message?.let { m -> main.postDelayed({ if (owedMessage === m) showAlert(m) }, if (missed != null) 5_000L else 0L) }
    }

    // ---- dash dropping out --------------------------------------------------

    /** When the last reading came from the dash; it sends one every 5 s. */
    private var lastReadingAt = 0L

    /** Where the last drop happened, for the parked spot if the dash never comes back. */
    private var dropLat: Double? = null
    private var dropLng: Double? = null

    /**
     * Connected but the dash has gone quiet: it sometimes stops talking
     * without dropping the link. Reconnect instead of waiting for it.
     */
    private val silentCheck = object : Runnable {
        override fun run() {
            if (link.state != ClusterLink.State.READY) return
            val quiet = System.currentTimeMillis() - lastReadingAt
            if (quiet >= SILENT_MS) {
                AppLog.d("Dash", "Dash silent for ${quiet / 1000} s, reconnecting")
                link.disconnect()
                return
            }
            main.postDelayed(this, SILENT_CHECK_MS)
        }
    }

    /** What DropWatch made of the dash going quiet. */
    private fun onDropJudged(j: DropJudge) {
        if (link.state == ClusterLink.State.READY) return
        dropLat = j.dropLat
        dropLng = j.dropLng
        when (j.verdict) {
            DropJudge.Verdict.DASH_LOST -> {
                AppLog.d("Dash", "Dash dropped while riding (${j.reason}): the ride goes on, reconnecting every 15 s")
                TripLog.onDashLost(this)
                // Back to quick retries, whatever the backoff had reached.
                lostAt = System.currentTimeMillis()
                main.removeCallbacks(lostGiveUp)
                main.postDelayed(lostGiveUp, DropJudge.LOST_LIMIT_MS)
            }
            else -> {
                AppLog.d("Dash", "Bike switched off (${j.reason})")
                TripLog.confirmBikeOff(this)
                com.th4rv4.zukinav.trips.Parking.onBikeOff(this, j.dropLat, j.dropLng)
            }
        }
        updateNotification()
        notifyState()
    }

    /** A dash lost mid-ride that never came back: count it as the bike off where it dropped. */
    private val lostGiveUp = Runnable {
        if (link.state == ClusterLink.State.READY || !TripLog.dashLost) return@Runnable
        AppLog.d("Dash", "Dash not back in ${DropJudge.LOST_LIMIT_MS / 60_000} min: counting it as the bike off where it dropped")
        TripLog.confirmBikeOff(this)
        com.th4rv4.zukinav.trips.Parking.onBikeOff(this, dropLat, dropLng)
        updateNotification()
        notifyState()
    }

    // ---- from the dash ------------------------------------------------------

    override fun onNotification(value: ByteArray) {
        val data = BikeData.parse(value) ?: return
        main.post { onBikeData(data) }
    }

    private fun onBikeData(data: BikeData) {
        lastReadingAt = System.currentTimeMillis()
        TripLog.onReading(this, data)
        data.fuelBars?.let { bars -> FuelStore.onReading(this, data.odoKm, bars)?.let { askAboutRefill(it) } }
        if (data.odoKm > 0) MaintNotifier.show(this, MaintStore.onOdo(this, data.odoKm))
        data.engineTempC?.let { c ->
            val events = Thermals.onReading(c, System.currentTimeMillis(), prefs.engineWarmC, prefs.engineHotC)
            if (ThermalSession.Event.OVERHEAT in events && prefs.overheatDashAlert) {
                AppLog.d("Thermals", "Engine $c°C: overheat alert")
                showDashMessage(OVERHEAT_TEXT)
            }
            if (ThermalSession.Event.WARMED_UP in events && prefs.warmedUpDashAlert) {
                AppLog.d("Thermals", "Engine $c°C: warmed up alert")
                showDashMessage(WARMED_UP_TEXT)
            }
        }
        val changed = data.copy(speedKmh = 0) != bike?.copy(speedKmh = 0)
        val speedChanged = data.speedKmh != bike?.speedKmh
        bike = data
        updateNotification()
        prefs.lastBikeAt = System.currentTimeMillis()
        if (changed) {
            prefs.lastBike = data
            AppLog.d("Bike", "ODO ${data.odoKm} km, Trip A ${data.tripAKm}, Trip B ${data.tripBKm}, " +
                (data.fuelBars?.let { "fuel $it/${BikeData.MAX_FUEL_BARS}" } ?: "battery ${data.batteryPercent}%") +
                (data.engineTempC?.let { ", engine $it°C" } ?: ""))
            notifyState()
            RangeWidget.refresh(this)
        } else if (speedChanged) {
            // The Ride tab's speedometer follows the bike.
            notifyState()
        }
    }

    // ---- send loop --------------------------------------------------------

    private val loop = object : Runnable {
        override fun run() {
            if (link.state != ClusterLink.State.READY) return
            step()
            // Every 200 ms while something is moving on the dash. On a ride
            // without a route only the clock goes out, so the loop sleeps until
            // the next minute; anything that needs the dash sooner calls wake().
            loopFast = busy()
            main.postDelayed(this, if (loopFast) 200 else msToNextMinute())
        }
    }

    /** True while the loop runs every 200 ms. */
    private var loopFast = false

    /** Runs the send loop now, e.g. a route started or a flag needs sending. */
    fun wake() {
        main.post {
            if (link.state != ClusterLink.State.READY) return@post
            main.removeCallbacks(loop)
            main.post(loop)
        }
    }

    /** Just after the next minute starts, so the dash clock flips on time. */
    private fun msToNextMinute(): Long {
        val now = System.currentTimeMillis()
        return (60_000L - now % 60_000L + 50L).coerceIn(200L, IDLE_MAX_MS)
    }

    /** Navigation, the startup animation, a Dash Test or the end of a route: frames every 200 ms. */
    private fun busy(): Boolean =
        NavRepository.current != null || helloLeft > 0 || splashWait > 0 || splashLeft > 0 ||
            testFrame != null || endFramesLeft > 0 || navWasActive || greetingPending

    /** When the status frame (clock, speed alert, clear flags) last went out, and with what. */
    private var statusAt = 0L
    private var statusMinute = -1
    private var statusSpeedAlert = -1

    /**
     * During a route (or anything else moving on the dash) every 2 s, as
     * always. On a ride without a route only when the minute changes, so the
     * dash clock stays right, or straight away when something in it changed.
     */
    private fun statusDue(now: Long): Boolean {
        if (statusAt == 0L || clearSmsLeft > 0 || clearMissedLeft > 0) return true
        if (prefs.speedAlertKmh != statusSpeedAlert) return true
        if (busy()) return now - statusAt >= 2_000
        return LocalTime.now().minute != statusMinute || now - statusAt >= 60_000
    }

    private fun step() {
        val name = link.deviceName ?: prefs.clusterName
        val model = ClusterModels.fromBleName(name)
        val mode = model.checksum

        if (helloLeft > 0) {
            val first = name != null && prefs.isFirstPairing(name)
            val hello = SuzukiFrames.hello(prefs.riderName, first, mode)
            if (helloLeft == 4) AppLog.d("Dash", "-> ${SuzukiFrames.hex(hello)} (hello, ${model.displayName})")
            link.send(hello)
            helloLeft--
            if (helloLeft == 0) {
                if (name != null) prefs.markPaired(name)
                startSplash()
            }
            tick++
            return
        }

        val nowMs = System.currentTimeMillis()
        if (model.statusLayout != ClusterModel.StatusLayout.NONE && statusDue(nowMs)) {
            statusAt = nowMs
            statusMinute = LocalTime.now().minute
            statusSpeedAlert = prefs.speedAlertKmh
            val status = SuzukiFrames.status(
                LocalTime.now(), speedAlertKmh = prefs.speedAlertKmh,
                clearSms = clearSmsLeft > 0, clearMissedCall = clearMissedLeft > 0,
                mode = mode, layout = model.statusLayout,
            )
            if (clearSmsLeft > 0) clearSmsLeft--
            if (clearMissedLeft > 0) clearMissedLeft--
            if (!statusLogged) {
                AppLog.d("Dash", "-> ${SuzukiFrames.hex(status)} (status: every 2 s during a route, else once a minute)")
                statusLogged = true
            } else if (!busy()) {
                // Once a minute without a route: worth a line, to see the dash keeps up.
                AppLog.d("Dash", "-> status (clock ${LocalTime.now().withNano(0)})")
            }
            link.send(status, coalesceKey = "status")
        }
        tick++

        val now = System.currentTimeMillis()
        val test = testFrame?.takeIf { now < testUntil }
        if (test == null) testFrame = null
        val nav = NavRepository.current
        // Navigation always wins: a route cancels the startup animation,
        // the greeting and any Dash Test straight away.
        if (nav != null && (splashWait > 0 || splashLeft > 0 || greetingPending || testFrame != null)) {
            AppLog.d("Dash", "Route active, startup animation and tests skipped")
            splashWait = 0
            splashLeft = 0
            greetingPending = false
            testFrame = null
        }
        // A route starting mid-greeting closes it: the call screen would hide the arrows.
        if (nav != null && callText != null) {
            AppLog.d("Dash", "Route active, greeting closed")
            closeCallText()
        }
        val splash = nav == null && splashWait == 0 && splashLeft > 0
        if (nav == null && splashWait > 0) splashWait--

        when {
            nav != null -> {
                routeShown = true
                val frame = navFrame(nav, model)
                val hex = SuzukiFrames.hex(frame)
                if (hex != lastNavHex) {
                    AppLog.d("Dash", "-> $hex")
                    lastNavHex = hex
                }
                link.send(frame, coalesceKey = "nav")
                navWasActive = true
                endFramesLeft = 0
            }
            splash -> {
                val icon = SPIN[(SPIN_FRAMES - splashLeft) % SPIN.size]
                link.send(dotsFrame(icon, mode), coalesceKey = "nav")
                navWasActive = true
                splashLeft--
            }
            test != null && splashWait == 0 && splashLeft == 0 -> {
                link.send(test(model), coalesceKey = "nav")
                navWasActive = true
            }
            navWasActive && splashWait == 0 -> {
                navWasActive = false
                // A short clear is enough before the greeting.
                endFramesLeft = if (greetingPending) 3 else 10
                AppLog.d("Dash", if (routeShown) "Navigation ended, clearing dash" else "Startup done, clearing dash")
                routeShown = false
            }
        }
        if (endFramesLeft > 0 && test == null && nav == null && !splash) {
            link.send(
                SuzukiFrames.navigation(
                    ClusterIcon.STRAIGHT.id, DashDistance.ZERO, "000000", DashDistance.ZERO,
                    active = false, mode = mode, etaLeadingSpace = model.etaLeadingSpace,
                ),
                coalesceKey = "nav",
            )
            endFramesLeft--
            if (endFramesLeft == 0 && greetingPending) showGreeting(mode)
        }
    }

    /** After the "Welcome" screen: spin the compass arrow, if turned on and no route is active. */
    private fun startSplash() {
        val greeting = prefs.startupGreeting.isNotBlank()
        if (!prefs.startupAnimation && !greeting) return
        if (NavRepository.current != null) {
            AppLog.d("Dash", "Route active, startup animation skipped")
            return
        }
        splashWait = SPLASH_WAIT_TICKS
        splashLeft = if (prefs.startupAnimation) SPIN_FRAMES else 0
        greetingPending = greeting
        // Greeting without the animation: clear the (empty) screen, then greet.
        if (splashLeft == 0) navWasActive = true
    }

    /**
     * After Welcome and the animation: the rider's greeting. It stays on the
     * call screen, the only screen the app can close on time (marking the
     * "call" answered), so it lasts exactly Shown For; the message screen
     * would keep it up for 10 s.
     */
    private fun showGreeting(mode: SuzukiFrames.Checksum) {
        greetingPending = false
        if (NavRepository.current != null) return
        showCallText(prefs.startupGreeting, mode, prefs.greetingSeconds * 1000L)
    }

    /**
     * Puts [text] on the dash's SMS message screen ("SMS1" over the text).
     * It reads as a note, not a call to pick up. Without a route the dash
     * shows a big envelope for about 10 s, and nothing the app sends closes it
     * sooner; during a route the arrow and distance stay up and the text
     * replaces ETA and remaining for those 10 s. Afterwards the status frames
     * clear the unread-message icon it lit next to the clock.
     */
    private fun showMessageText(text: String, mode: SuzukiFrames.Checksum) {
        val show = SuzukiFrames.message(text, count = 1, whatsapp = false, mode = mode)
        AppLog.d("Dash", "-> ${SuzukiFrames.hex(show)} (\"$text\")")
        repeat(3) { i -> main.postDelayed({ link.send(show) }, i * 200L) }
        main.removeCallbacks(clearMessageIcon)
        main.postDelayed(clearMessageIcon, MESSAGE_SCREEN_MS)
    }

    private val clearMessageIcon = Runnable { clearSmsLeft = CLEAR_REPEATS; wake() }
    private val clearMissedIcon = Runnable { clearMissedLeft = CLEAR_REPEATS; wake() }

    // The greeting on the call screen right now, if any.
    private var callText: String? = null
    private var callMode = SuzukiFrames.Checksum.SUM
    private val closeCall = Runnable { closeCallText() }

    /** Puts [text] on the call screen for [ms], then marks it answered, which closes it. */
    private fun showCallText(text: String, mode: SuzukiFrames.Checksum, ms: Long) {
        main.removeCallbacks(closeCall)
        callText = text
        callMode = mode
        val show = SuzukiFrames.call(text, ringing = true, mode = mode)
        AppLog.d("Dash", "-> ${SuzukiFrames.hex(show)} (\"$text\" for ${ms / 1000} s)")
        repeat(3) { i -> main.postDelayed({ link.send(show) }, i * 200L) }
        main.postDelayed(closeCall, ms)
    }

    private fun closeCallText() {
        main.removeCallbacks(closeCall)
        val text = callText ?: return
        callText = null
        val close = SuzukiFrames.call(text, ringing = false, mode = callMode)
        repeat(3) { i -> main.postDelayed({ link.send(close) }, i * 200L) }
    }

    /**
     * A short alert on the message screen. It never hides the turn arrow:
     * during a route the text shows below it, so alerts show with or
     * without one.
     */
    private fun showDashMessage(text: String): Boolean {
        if (link.state != ClusterLink.State.READY) return false
        showMessageText(text, ClusterModels.fromBleName(link.deviceName ?: prefs.clusterName).checksum)
        return true
    }

    /** Reject Unknown Callers with Show On Dash: REJECTED on the dash. */
    fun showRejectedCall() {
        main.post { showDashMessage(REJECTED_TEXT) }
    }

    /** From Dash Test: shows one of the engine alerts now. False if the dash isn't connected. */
    fun testThermalAlert(overheat: Boolean): Boolean =
        showDashMessage(if (overheat) OVERHEAT_TEXT else WARMED_UP_TEXT)

    /** Nav screen with dots in the number spots: the dash has no blank character. */
    private fun dotsFrame(icon: Int, mode: SuzukiFrames.Checksum): ByteArray =
        SuzukiFrames.navigationText(icon, "....", "......", "....", mode)

    /** Sends a call or message alert 3 times, 200 ms apart, as Ride Connect does. */
    private fun showAlert(alert: Alert) {
        // Clearing is kept until the dash is back: the status frames sent
        // after reconnecting carry it, so an indicator lit before the bike
        // was switched off doesn't stay on.
        when (alert) {
            Alert.ClearMissed -> { owedMissed = null; clearMissedLeft = CLEAR_REPEATS; wake(); return }
            Alert.ClearMessages -> { owedMessage = null; clearSmsLeft = CLEAR_REPEATS; wake(); return }
            else -> Unit
        }
        if (link.state != ClusterLink.State.READY) {
            // A missed call or unread message that stays is shown once the
            // dash is back; a call ringing now, or a passing one, is past.
            when {
                alert is Alert.Missed && !alert.fleeting -> owedMissed = alert
                alert is Alert.Message && !alert.fleeting -> owedMessage = alert
                else -> {
                    AppLog.d("Dash", "Not connected, alert dropped")
                    return
                }
            }
            AppLog.d("Dash", "Not connected, showing it when the dash is back")
            return
        }
        if (alert is Alert.Missed) owedMissed = null
        if (alert is Alert.Message) owedMessage = null
        val mode = ClusterModels.fromBleName(link.deviceName ?: prefs.clusterName).checksum
        val frame = when (alert) {
            is Alert.Ringing -> SuzukiFrames.call(alert.caller, ringing = true, whatsapp = alert.whatsapp, mode = mode)
            is Alert.Answered -> SuzukiFrames.call(alert.caller, ringing = false, whatsapp = alert.whatsapp, mode = mode)
            is Alert.Missed -> SuzukiFrames.missedCall(alert.caller, alert.count, alert.whatsapp, mode)
            is Alert.Message -> SuzukiFrames.message(alert.sender, alert.count, alert.whatsapp, mode)
            Alert.ClearMissed, Alert.ClearMessages -> null
        } ?: return
        // Shown, then cleared so it doesn't stay as unread; anything that
        // should stay cancels a clear still waiting.
        when (alert) {
            is Alert.Message -> {
                main.removeCallbacks(clearMessageIcon)
                if (alert.fleeting) main.postDelayed(clearMessageIcon, MESSAGE_SCREEN_MS)
            }
            is Alert.Missed -> {
                main.removeCallbacks(clearMissedIcon)
                if (alert.fleeting) main.postDelayed(clearMissedIcon, FLEETING_MISSED_MS)
            }
            else -> Unit
        }
        var after = 0L
        if (alert is Alert.Missed) {
            // A missed call frame doesn't close the dash's ringing screen; it
            // stays up until its own timeout, up to 15 s after the call
            // ended. Closing the call first, the way an answered call does,
            // takes it down straight away.
            val close = SuzukiFrames.call(alert.caller, ringing = false, whatsapp = alert.whatsapp, mode = mode)
            AppLog.d("Dash", "-> ${SuzukiFrames.hex(close)} (call ended)")
            repeat(3) { i -> main.postDelayed({ link.send(close) }, i * 200L) }
            after = 600L
        }
        AppLog.d("Dash", "-> ${SuzukiFrames.hex(frame)} (alert)")
        repeat(3) { i -> main.postDelayed({ link.send(frame) }, after + i * 200L) }
    }

    private fun navFrame(nav: NavSnapshot, model: ClusterModel): ByteArray =
        SuzukiFrames.navigation(
            iconId = model.adjustIcon((nav.icon ?: ClusterIcon.STRAIGHT).id),
            toTurn = nav.metersToTurn?.let { DashDistance.fromMeters(it, roundDownTo10 = true) } ?: DashDistance.ZERO,
            eta = SuzukiFrames.etaField(nav.eta, DateFormat.is24HourFormat(this)),
            remaining = nav.remainingMeters?.let { DashDistance.fromMeters(it) } ?: DashDistance.ZERO,
            mode = model.checksum,
            etaLeadingSpace = model.etaLeadingSpace,
        )

    /** Raw icon ID, deliberately not passed through [ClusterModel.adjustIcon]. */
    private fun iconTestFrame(iconId: Int, model: ClusterModel): ByteArray =
        SuzukiFrames.navigation(
            iconId = iconId,
            toTurn = DashDistance.fromMeters(350.0, roundDownTo10 = true),
            eta = SuzukiFrames.etaField(LocalTime.now().plusMinutes(12), DateFormat.is24HourFormat(this)),
            remaining = DashDistance.fromMeters(12_300.0),
            mode = model.checksum,
            etaLeadingSpace = model.etaLeadingSpace,
        )

    // ---- notification -----------------------------------------------------

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_link), NotificationManager.IMPORTANCE_LOW),
        )
        try {
            // connectedDevice needs BLUETOOTH_CONNECT to be granted at this point.
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            AppLog.d("Dash", "Cannot run in the foreground: $e")
            stopSelf()
        }
    }

    /** What the notification last said: Maps updates every second, the text far less often. */
    private var lastNotificationText: String? = null

    private fun updateNotification() {
        if (instance == null) return
        val n = buildNotification()
        val e = n.extras
        val key = "${e.getCharSequence(Notification.EXTRA_TITLE)}\u0000${e.getCharSequence(Notification.EXTRA_TEXT)}\u0000" +
            "${e.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)}\u0000${if (e.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) n.`when` else 0}"
        if (key == lastNotificationText) return
        lastNotificationText = key
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)
    }

    /** "Refuelled?" notification; tapping it opens the refill form. */
    private fun askAboutRefill(r: Refill) {
        if (!prefs.askAtRefill) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(REFILL_CHANNEL, "Refills", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_REFILL, r.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, REFILL_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nav)
            .setContentTitle("Refuelled?")
            .setContentText("Fuel went from ${r.barsBefore} to ${r.barsAfter} bars at ${r.odoKm} km. Tap to add litres and amount paid.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(REFILL_NOTIF_ID, n)
        } catch (e: SecurityException) {
            AppLog.d("Fuel", "Cannot show the refill notification: $e")
        }
    }

    /** Engine temperature as last shown: refreshed at most every 30 s, at once when it turns hot. */
    private var shownEngineC: Int? = null
    private var shownEngineAt = 0L

    private fun engineForNotification(now: Long): Int? {
        val live = bike?.engineTempC?.takeIf { link.state == ClusterLink.State.READY } ?: return null.also { shownEngineC = null }
        val hot = prefs.engineHotC
        val shown = shownEngineC
        if (shown == null || now - shownEngineAt >= ENGINE_REFRESH_MS || (live >= hot) != (shown >= hot)) {
            shownEngineC = live
            shownEngineAt = now
        }
        return shownEngineC
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_RIDE, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val now = System.currentTimeMillis()
        val linkNow = when {
            bluetoothOff -> NotifText.Link.BLUETOOTH_OFF
            link.state == ClusterLink.State.READY -> NotifText.Link.CONNECTED
            TripLog.dashLost -> NotifText.Link.DASH_LOST
            TripLog.bikeOff -> NotifText.Link.BIKE_OFF
            else -> NotifText.Link.WAITING
        }
        val nav = NavRepository.current?.takeIf { RouteWatch.isOpen(this, now) }
        val data = bike ?: prefs.lastBike
        val range = data?.takeIf { it.fuelBars != null }?.let { FuelStore.range(this, it.odoKm)?.km }
        val shown = NotifText.compose(
            NotifText.Input(
                link = linkNow,
                engineC = engineForNotification(now),
                hotC = prefs.engineHotC,
                routeKmLeft = nav?.remainingMeters?.let { it / 1000.0 },
                eta = nav?.eta,
                rangeKm = range,
            ),
        )
        val title: CharSequence = if (shown.hot) {
            android.text.SpannableString(shown.title).apply {
                setSpan(android.text.style.ForegroundColorSpan(HOT_COLOR), 0, length, 0)
            }
        } else {
            shown.title
        }
        // The ride timer, while riding.
        val rideStart = if (linkNow == NotifText.Link.CONNECTED) TripLog.active(this)?.start else null
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nav)
            .setContentTitle(title)
            .setContentText(shown.text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .apply {
                if (shown.hot) setColor(HOT_COLOR)
                if (rideStart != null) {
                    setWhen(rideStart)
                    setShowWhen(true)
                    setUsesChronometer(true)
                } else {
                    setShowWhen(false)
                }
            }
            .build()
    }

    companion object {
        private const val CHANNEL = "dash_link"
        private const val NOTIF_ID = 17

        /** How often the engine temperature in the notification may change. */
        private const val ENGINE_REFRESH_MS = 30_000L
        private val HOT_COLOR = 0xFFFF5A6E.toInt()
        private const val REFILL_CHANNEL = "refills"
        const val REFILL_NOTIF_ID = 18
        private const val CONNECT_TIMEOUT_MS = 20_000L
        /** The dash sends a reading every 5 s; none for this long while connected means it has stopped talking. */
        private const val SILENT_MS = 20_000L
        private const val SILENT_CHECK_MS = 5_000L
        /** Fast reconnect attempts for this long after losing the bike. */
        private const val FAST_WINDOW_MS = 5 * 60_000L
        /** Then a quiet background wait of this long between quick direct tries. */
        private const val SLOW_WAIT_MS = 2 * 60_000L
        /** After this long parked, no more quick direct tries. */
        private const val DEEP_SLEEP_MS = 60 * 60_000L
        private const val CLEAR_REPEATS = 3
        /** Longest the send loop sleeps on a ride without a route. */
        private const val IDLE_MAX_MS = 60_050L

        /** Compass arrows clockwise from north: one turn of the spinning arrow. */
        private val SPIN = listOf(
            ClusterIcon.HEAD_NORTH, ClusterIcon.HEAD_NORTHEAST, ClusterIcon.HEAD_EAST, ClusterIcon.HEAD_SOUTHEAST,
            ClusterIcon.HEAD_SOUTH, ClusterIcon.HEAD_SOUTHWEST, ClusterIcon.HEAD_WEST, ClusterIcon.HEAD_NORTHWEST,
        ).map { it.id }
        /** One turn, one arrow every 200 ms: about 1.6 s. */
        private const val SPIN_FRAMES = 8
        /** Let the "Welcome" screen show for about 3 s before the animation. */
        private const val SPLASH_WAIT_TICKS = 15
        /** Longest Engine Overheat stays over the arrows during a route. */
        /** How long the dash keeps a message up by itself. */
        private const val MESSAGE_SCREEN_MS = 11_000L
        /** An unsaved number's missed call: just long enough to close the ringing screen. */
        private const val FLEETING_MISSED_MS = 4_000L
        /** After reconnecting, wait for the welcome, startup animation and greeting first. */
        private const val OWED_DELAY_MS = 12_000L
        const val OVERHEAT_TEXT = "ENGINE HOT"
        const val REJECTED_TEXT = "REJECTED"
        const val WARMED_UP_TEXT = "WARMED UP"
        const val ACTION_STOP = "com.th4rv4.zukinav.STOP"

        @Volatile var instance: DashService? = null
            private set

        /** The phone's Bluetooth is off; the link waits and reconnects when it is back. */
        @Volatile var bluetoothOff = false
            private set

        private val stateListeners = CopyOnWriteArrayList<() -> Unit>()
        fun addStateListener(l: () -> Unit) { stateListeners.add(l) }
        fun removeStateListener(l: () -> Unit) { stateListeners.remove(l) }
        private fun notifyState() { stateListeners.forEach { it() } }

        val linkState: ClusterLink.State
            get() = instance?.link?.state ?: ClusterLink.State.DISCONNECTED

        /** Live readings from the dash, or null when not connected or none yet. */
        val liveBike: BikeData?
            get() = instance?.bike

        fun hasConnectPermission(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < 31 ||
                ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, DashService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, DashService::class.java).setAction(ACTION_STOP))
        }

        /**
         * Wake On Ignition setup: let go of the dash so it advertises and
         * Android can find it, then reconnect when [hold] is false again.
         */
        fun holdForSetup(hold: Boolean) {
            val s = instance ?: return
            s.main.post {
                s.setupHold = hold
                s.main.removeCallbacks(s.releaseHold)
                if (hold) {
                    AppLog.d("Wake", "Letting go of the dash so Android can find it")
                    s.main.removeCallbacks(s.connectTimeout)
                    s.link.disconnect()
                    // Never stay let go for long, whatever happens to the setup.
                    s.main.postDelayed(s.releaseHold, 2 * 60_000L)
                } else {
                    s.retries = 0
                    s.lostAt = System.currentTimeMillis()
                    s.connectIfNeeded()
                }
            }
        }

        /**
         * True if the bike is paired in Android's Bluetooth settings. The dash
         * then refuses Zuki Nav's connection, so the app asks to forget it.
         */
        fun isBikeBonded(ctx: Context): Boolean {
            if (!hasConnectPermission(ctx)) return false
            val addr = Prefs(ctx).clusterAddress ?: return false
            return try {
                val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return false
                adapter.bondedDevices?.any { it.address.equals(addr, ignoreCase = true) } == true
            } catch (e: SecurityException) {
                false
            }
        }

        /** Shows a call or message alert on the dash, if it is connected. */
        /**
         * The latest missed call and unread message that couldn't reach the
         * dash. Kept here rather than in the link itself, so they survive the
         * link sleeping for Wake On Ignition (or being stopped) and still show
         * when the bike is back.
         */
        private var owedMissed: Alert.Missed? = null
        private var owedMessage: Alert.Message? = null

        fun sendAlert(alert: Alert) {
            val s = instance
            if (s == null) {
                // Link not running (asleep until the bike is seen): keep what stays.
                when {
                    alert == Alert.ClearMissed -> owedMissed = null
                    alert == Alert.ClearMessages -> owedMessage = null
                    alert is Alert.Missed && !alert.fleeting -> owedMissed = alert
                    alert is Alert.Message && !alert.fleeting -> owedMessage = alert
                    else -> return
                }
                if (alert is Alert.Missed || alert is Alert.Message) {
                    AppLog.d("Dash", "Dash link asleep, showing it when the bike is back")
                }
                return
            }
            s.main.post { s.showAlert(alert) }
        }

        /**
         * Shows [iconId] on the dash for 8 s with dummy distances, to check the
         * icon table. False while a route is active: navigation comes first.
         */
        fun testIcon(iconId: Int): Boolean {
            val s = instance ?: return false
            if (NavRepository.current != null) return false
            s.main.post {
                s.testFrame = { m -> s.iconTestFrame(iconId, m) }
                s.testUntil = System.currentTimeMillis() + 8_000
                s.wake()
                AppLog.d("Dash", "Testing icon $iconId (${ClusterIcon.fromId(iconId)?.label ?: "unlisted"})")
            }
            return true
        }

        /**
         * Puts free text in the distance, ETA and remaining spots for 15 s, to
         * see what the dash can show there. False while a route is active.
         */
        fun testText(toTurn: String, eta: String, remaining: String): Boolean {
            val s = instance ?: return false
            if (NavRepository.current != null) return false
            s.main.post {
                s.testFrame = { m ->
                    SuzukiFrames.navigationText(ClusterIcon.STRAIGHT.id, toTurn, eta, remaining, m.checksum)
                }
                s.testUntil = System.currentTimeMillis() + 15_000
                s.wake()
                val f = SuzukiFrames.navigationText(ClusterIcon.STRAIGHT.id, toTurn, eta, remaining)
                AppLog.d("Dash", "Testing text \"$toTurn\" \"$eta\" \"$remaining\": ${SuzukiFrames.hex(f)}")
            }
            return true
        }
    }
}
