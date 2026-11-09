package com.th4rv4.suzukinav.service

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
import com.th4rv4.suzukinav.R
import com.th4rv4.suzukinav.ble.ClusterLink
import com.th4rv4.suzukinav.nav.NavRepository
import com.th4rv4.suzukinav.nav.NavSnapshot
import com.th4rv4.suzukinav.protocol.ClusterIcon
import com.th4rv4.suzukinav.protocol.DashDistance
import com.th4rv4.suzukinav.protocol.SuzukiFrames
import com.th4rv4.suzukinav.ui.MainActivity
import com.th4rv4.suzukinav.util.AppLog
import com.th4rv4.suzukinav.util.Prefs
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
    private var testIconId: Int? = null
    private var testUntil = 0L
    private var reconnectInBackground = false

    private val navListener: (NavSnapshot?) -> Unit = { updateNotification() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        link = ClusterLink(this, this)
        instance = this
        NavRepository.addListener(navListener)
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
        link.connect(addr, background = reconnectInBackground)
    }

    override fun onStateChanged(state: ClusterLink.State) {
        main.post {
            AppLog.d("Dash", "Link $state")
            when (state) {
                ClusterLink.State.READY -> {
                    reconnectInBackground = false
                    helloLeft = 4
                    tick = 0
                    main.removeCallbacks(loop)
                    main.post(loop)
                }
                ClusterLink.State.DISCONNECTED -> {
                    main.removeCallbacks(loop)
                    if (prefs.autoConnect && instance != null) {
                        // From now on let Android wait for the bike in the background.
                        reconnectInBackground = true
                        main.postDelayed({ connectIfNeeded() }, 2_000)
                    }
                }
                ClusterLink.State.CONNECTING -> Unit
            }
            updateNotification()
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
        val mode = SuzukiFrames.checksumFor(name)

        if (helloLeft > 0) {
            val first = name != null && prefs.isFirstPairing(name)
            link.send(SuzukiFrames.hello(prefs.riderName, first, mode))
            helloLeft--
            if (helloLeft == 0 && name != null) prefs.markPaired(name)
            tick++
            return
        }

        if (tick % 10 == 0) {
            link.send(
                SuzukiFrames.status(LocalTime.now(), speedAlertKmh = prefs.speedAlertKmh, mode = mode),
                coalesceKey = "status",
            )
        }
        tick++

        val now = System.currentTimeMillis()
        val test = testIconId?.takeIf { now < testUntil }
        if (test == null) testIconId = null
        val nav = NavRepository.current

        when {
            test != null -> {
                link.send(testFrame(test, mode), coalesceKey = "nav")
                navWasActive = true
            }
            nav != null -> {
                link.send(navFrame(nav, mode), coalesceKey = "nav")
                navWasActive = true
                endFramesLeft = 0
            }
            navWasActive -> {
                navWasActive = false
                endFramesLeft = 10
                AppLog.d("Dash", "Navigation ended, clearing dash")
            }
        }
        if (endFramesLeft > 0 && test == null && nav == null) {
            link.send(
                SuzukiFrames.navigation(
                    ClusterIcon.STRAIGHT.id, DashDistance.ZERO, "000000", DashDistance.ZERO,
                    active = false, mode = mode,
                ),
                coalesceKey = "nav",
            )
            endFramesLeft--
        }
    }

    private fun navFrame(nav: NavSnapshot, mode: SuzukiFrames.Checksum): ByteArray =
        SuzukiFrames.navigation(
            iconId = (nav.icon ?: ClusterIcon.STRAIGHT).id,
            toTurn = nav.metersToTurn?.let { DashDistance.fromMeters(it, roundDownTo10 = true) } ?: DashDistance.ZERO,
            eta = SuzukiFrames.etaField(nav.eta, DateFormat.is24HourFormat(this)),
            remaining = nav.remainingMeters?.let { DashDistance.fromMeters(it) } ?: DashDistance.ZERO,
            mode = mode,
        )

    private fun testFrame(iconId: Int, mode: SuzukiFrames.Checksum): ByteArray =
        SuzukiFrames.navigation(
            iconId = iconId,
            toTurn = DashDistance.fromMeters(350.0, roundDownTo10 = true),
            eta = SuzukiFrames.etaField(LocalTime.now().plusMinutes(12), DateFormat.is24HourFormat(this)),
            remaining = DashDistance.fromMeters(12_300.0),
            mode = mode,
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
        const val ACTION_STOP = "com.th4rv4.suzukinav.STOP"

        @Volatile var instance: DashService? = null
            private set

        private val stateListeners = CopyOnWriteArrayList<() -> Unit>()
        fun addStateListener(l: () -> Unit) { stateListeners.add(l) }
        fun removeStateListener(l: () -> Unit) { stateListeners.remove(l) }
        private fun notifyState() { stateListeners.forEach { it() } }

        val linkState: ClusterLink.State
            get() = instance?.link?.state ?: ClusterLink.State.DISCONNECTED

        fun hasConnectPermission(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < 31 ||
                ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, DashService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, DashService::class.java).setAction(ACTION_STOP))
        }

        /** Shows [iconId] on the dash for 8 s with dummy distances, to check the icon table. */
        fun testIcon(iconId: Int) {
            val s = instance ?: return
            s.main.post {
                s.testIconId = iconId
                s.testUntil = System.currentTimeMillis() + 8_000
                AppLog.d("Dash", "Testing icon $iconId (${ClusterIcon.fromId(iconId)?.label ?: "unlisted"})")
            }
        }
    }
}
