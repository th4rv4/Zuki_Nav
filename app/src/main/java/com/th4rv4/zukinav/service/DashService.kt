package com.th4rv4.zukinav.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import com.th4rv4.zukinav.nav.NavRepository
import com.th4rv4.zukinav.nav.NavSnapshot
import com.th4rv4.zukinav.protocol.BikeData
import com.th4rv4.zukinav.protocol.ClusterIcon
import com.th4rv4.zukinav.protocol.ClusterModel
import com.th4rv4.zukinav.protocol.ClusterModels
import com.th4rv4.zukinav.protocol.DashDistance
import com.th4rv4.zukinav.protocol.SuzukiFrames
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
    /** Dash Test frame (an icon or test text) and until when to show it. */
    private var testFrame: ((ClusterModel) -> ByteArray)? = null
    private var testUntil = 0L
    /** Startup animation: ticks to wait after the hello, then frames left to show. */
    private var splashWait = 0
    private var splashLeft = 0
    private var greetingPending = false
    private var retries = 0
    private var lastNavHex: String? = null
    private var statusLogged = false
    /** Status frames still to send with the "clear" flags set. */
    private var clearSmsLeft = 0
    private var clearMissedLeft = 0

    /** A direct connect that hangs is abandoned after this and retried. */
    private val connectTimeout = Runnable {
        when (link.state) {
            ClusterLink.State.CONNECTING -> {
                AppLog.d("Dash", "Bike not answering, retrying")
                link.disconnect()
            }
            // e.g. Bluetooth was off: the link never left DISCONNECTED, so
            // nothing else would schedule another attempt.
            ClusterLink.State.DISCONNECTED -> if (prefs.autoConnect) connectIfNeeded()
            ClusterLink.State.READY -> Unit
        }
    }

    private val navListener: (NavSnapshot?) -> Unit = { updateNotification() }

    /** Latest fuel, ODO and trip readings from the dash, while connected. */
    @Volatile private var bike: BikeData? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        link = ClusterLink(this, this)
        instance = this
        NavRepository.addListener(navListener)
        TripLog.finishIfIdle(this)
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
        if (bike != null) TripLog.onDisconnected(this)
        instance = null
        main.removeCallbacksAndMessages(null)
        NavRepository.removeListener(navListener)
        link.release()
        notifyState()
        super.onDestroy()
    }

    // ---- connection -------------------------------------------------------

    private fun connectIfNeeded() {
        if (link.state != ClusterLink.State.DISCONNECTED) return
        val addr = prefs.clusterAddress
        if (addr == null) {
            AppLog.d("Dash", "No cluster chosen yet")
            return
        }
        if (!hasConnectPermission(this)) {
            AppLog.d("Dash", "Bluetooth permission missing")
            return
        }
        // Direct connects, retried with backoff (see onStateChanged). Android's
        // background auto-connect was tried first and could sit for minutes
        // after the bike came back.
        link.connect(addr, background = false)
        main.removeCallbacks(connectTimeout)
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    override fun onStateChanged(state: ClusterLink.State) {
        main.post {
            AppLog.d("Dash", "Link $state")
            when (state) {
                ClusterLink.State.READY -> {
                    main.removeCallbacks(connectTimeout)
                    retries = 0
                    lastNavHex = null
                    statusLogged = false
                    helloLeft = 4
                    tick = 0
                    main.removeCallbacks(loop)
                    main.post(loop)
                }
                ClusterLink.State.DISCONNECTED -> {
                    if (bike != null) TripLog.onDisconnected(this)
                    bike = null
                    main.removeCallbacks(loop)
                    main.removeCallbacks(connectTimeout)
                    if (prefs.autoConnect && instance != null) {
                        // 2 s, 4 s, 8 s, then every 15 s while the bike is off.
                        val delay = minOf(2_000L shl retries.coerceAtMost(3), 15_000L)
                        retries++
                        AppLog.d("Dash", "Reconnecting in ${delay / 1000} s")
                        main.postDelayed({ connectIfNeeded() }, delay)
                    }
                }
                ClusterLink.State.CONNECTING -> Unit
            }
            updateNotification()
            notifyState()
        }
    }

    // ---- from the dash ------------------------------------------------------

    override fun onNotification(value: ByteArray) {
        val data = BikeData.parse(value) ?: return
        main.post { onBikeData(data) }
    }

    private fun onBikeData(data: BikeData) {
        TripLog.onReading(this, data)
        val changed = data.copy(speedKmh = 0) != bike?.copy(speedKmh = 0)
        bike = data
        prefs.lastBikeAt = System.currentTimeMillis()
        if (changed) {
            prefs.lastBike = data
            AppLog.d("Bike", "ODO ${data.odoKm} km, Trip A ${data.tripAKm}, Trip B ${data.tripBKm}, " +
                (data.fuelBars?.let { "fuel $it/${BikeData.MAX_FUEL_BARS}" } ?: "battery ${data.batteryPercent}%"))
            notifyState()
        }
    }

    // ---- send loop --------------------------------------------------------

    private val loop = object : Runnable {
        override fun run() {
            if (link.state != ClusterLink.State.READY) return
            step()
            main.postDelayed(this, 200)
        }
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

        if (tick % 10 == 0 && model.statusLayout != ClusterModel.StatusLayout.NONE) {
            val status = SuzukiFrames.status(
                LocalTime.now(), speedAlertKmh = prefs.speedAlertKmh,
                clearSms = clearSmsLeft > 0, clearMissedCall = clearMissedLeft > 0,
                mode = mode, layout = model.statusLayout,
            )
            if (clearSmsLeft > 0) clearSmsLeft--
            if (clearMissedLeft > 0) clearMissedLeft--
            if (!statusLogged) {
                AppLog.d("Dash", "-> ${SuzukiFrames.hex(status)} (status, every 2 s)")
                statusLogged = true
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
        val splash = nav == null && splashWait == 0 && splashLeft > 0
        if (nav == null && splashWait > 0) splashWait--

        when {
            nav != null -> {
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
                AppLog.d("Dash", "Navigation ended, clearing dash")
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

    /** The greeting uses the dash's incoming-call screen, the only one with free text. */
    private fun showGreeting(mode: SuzukiFrames.Checksum) {
        greetingPending = false
        if (NavRepository.current != null) return
        val frame = SuzukiFrames.call(prefs.startupGreeting, ringing = true, mode = mode)
        AppLog.d("Dash", "-> ${SuzukiFrames.hex(frame)} (greeting)")
        repeat(3) { i -> main.postDelayed({ link.send(frame) }, i * 200L) }
    }

    /** Nav screen with dots in the number spots: the dash has no blank character. */
    private fun dotsFrame(icon: Int, mode: SuzukiFrames.Checksum): ByteArray =
        SuzukiFrames.navigationText(icon, "....", "......", "....", mode)

    /** Sends a call or message alert 3 times, 200 ms apart, as Ride Connect does. */
    private fun showAlert(alert: Alert) {
        if (link.state != ClusterLink.State.READY) {
            AppLog.d("Dash", "Not connected, alert dropped")
            return
        }
        val mode = ClusterModels.fromBleName(link.deviceName ?: prefs.clusterName).checksum
        val frame = when (alert) {
            is Alert.Ringing -> SuzukiFrames.call(alert.caller, ringing = true, whatsapp = alert.whatsapp, mode = mode)
            is Alert.Answered -> SuzukiFrames.call(alert.caller, ringing = false, whatsapp = alert.whatsapp, mode = mode)
            is Alert.Missed -> SuzukiFrames.missedCall(alert.caller, alert.count, alert.whatsapp, mode)
            is Alert.Message -> SuzukiFrames.message(alert.sender, alert.count, alert.whatsapp, mode)
            Alert.ClearMissed -> { clearMissedLeft = CLEAR_REPEATS; null }
            Alert.ClearMessages -> { clearSmsLeft = CLEAR_REPEATS; null }
        } ?: return
        AppLog.d("Dash", "-> ${SuzukiFrames.hex(frame)} (alert)")
        repeat(3) { i -> main.postDelayed({ link.send(frame) }, i * 200L) }
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

    private fun updateNotification() {
        if (instance == null) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DashService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val nav = NavRepository.current
        val title = when (link.state) {
            ClusterLink.State.READY -> "Connected To Dash"
            ClusterLink.State.CONNECTING -> "Waiting For Bike"
            ClusterLink.State.DISCONNECTED -> "Not Connected"
        }
        val text = nav?.let { "${it.icon?.label ?: "-"} · ${it.instruction ?: ""}" } ?: "No Active Navigation"
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nav)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL = "dash_link"
        private const val NOTIF_ID = 17
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val CLEAR_REPEATS = 3

        /** Compass arrows clockwise from north: one turn of the spinning arrow. */
        private val SPIN = listOf(
            ClusterIcon.HEAD_NORTH, ClusterIcon.HEAD_NORTHEAST, ClusterIcon.HEAD_EAST, ClusterIcon.HEAD_SOUTHEAST,
            ClusterIcon.HEAD_SOUTH, ClusterIcon.HEAD_SOUTHWEST, ClusterIcon.HEAD_WEST, ClusterIcon.HEAD_NORTHWEST,
        ).map { it.id }
        /** Two turns, one arrow every 200 ms: about 3 s. */
        private const val SPIN_FRAMES = 16
        /** Let the "Welcome" screen show for about 3 s before the animation. */
        private const val SPLASH_WAIT_TICKS = 15
        const val ACTION_STOP = "com.th4rv4.zukinav.STOP"

        @Volatile var instance: DashService? = null
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

        /** Shows a call or message alert on the dash, if it is connected. */
        fun sendAlert(alert: Alert) {
            val s = instance ?: return
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
                val f = SuzukiFrames.navigationText(ClusterIcon.STRAIGHT.id, toTurn, eta, remaining)
                AppLog.d("Dash", "Testing text \"$toTurn\" \"$eta\" \"$remaining\": ${SuzukiFrames.hex(f)}")
            }
            return true
        }
    }
}
