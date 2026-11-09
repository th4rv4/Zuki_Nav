package com.th4rv4.zukinav.nav

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.th4rv4.zukinav.protocol.ClusterIcon
import com.th4rv4.zukinav.util.AppLog
import com.th4rv4.zukinav.util.Prefs
import java.time.LocalTime

/**
 * Reads Google Maps' turn-by-turn notification and publishes it to
 * [NavRepository]. The user grants access once under
 * Settings > Notifications > Device & app notifications / Notification access.
 */
class MapsNavListener : NotificationListenerService() {

    private lateinit var prefs: Prefs
    private var lastIcon: ClusterIcon? = null
    // While Maps says "Rerouting..." its status line is a bare "Arrive ";
    // keep showing the last ETA / remaining distance instead of zeros.
    private var lastEta: LocalTime? = null
    private var lastRemaining: Double? = null
    /** Same arrow bitmap -> same answer; Maps reposts the notification every second or so. */
    private val arrowCache = HashMap<Int, ClusterIcon>()

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onListenerConnected() {
        AppLog.d("Maps", "Notification access active")
        try {
            activeNotifications?.forEach { if (isMapsGuidance(it)) handle(it) }
        } catch (e: Exception) {
            AppLog.d("Maps", "Could not read active notifications: $e")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isMapsGuidance(sbn)) handle(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (isMapsGuidance(sbn)) {
            AppLog.d("Maps", "Guidance notification removed")
            lastEta = null
            lastRemaining = null
            NavRepository.scheduleEnd()
        }
    }

    private fun isMapsGuidance(sbn: StatusBarNotification): Boolean =
        sbn.packageName == MapsIconTemplates.MAPS_PKG &&
            (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
            (sbn.notification.category == Notification.CATEGORY_NAVIGATION || sbn.isOngoing)

    private fun handle(sbn: StatusBarNotification) {
        val e = sbn.notification.extras
        val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = e.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val sub = e.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val critical = e.getCharSequence(EXTRA_SHORT_CRITICAL_TEXT)?.toString()
        val lht = prefs.leftHandTraffic

        val parsed = NavText.parse(title, text, sub, critical, LocalTime.now(), lht)
        logExtrasIfNew(e)

        // The text wins when it has a distance; otherwise use the progress bar.
        val (barToTurn, barRemaining) = NavText.fromProgress(
            e.getInt(Notification.EXTRA_PROGRESS, 0),
            e.getInt(Notification.EXTRA_PROGRESS_MAX, 0),
            progressPoints(e),
        )
        val toTurn = parsed.metersToTurn ?: barToTurn
        val remaining = parsed.remainingMeters ?: barRemaining

        var source = "none"
        var icon: ClusterIcon? = null

        val sig = largeIconSignature(sbn)
        if (sig != null) {
            val key = sig.cells.contentHashCode()
            val cachedIcon = arrowCache[key]
            if (cachedIcon != null) {
                icon = cachedIcon
                source = "maps-icon"
            } else {
                val m = MapsIconTemplates.matcher(this).match(sig)
                if (m != null && m.distance <= IconMatcher.MAX_DISTANCE && m.margin >= IconMatcher.MIN_MARGIN) {
                    icon = m.icon
                    source = "maps-icon"
                    if (arrowCache.size > 64) arrowCache.clear()
                    arrowCache[key] = m.icon
                    AppLog.d("Maps", "Arrow ${m.drawableName} -> ${m.icon.label} (d=%.3f, margin=%.3f)".format(m.distance, m.margin))
                } else if (m != null) {
                    AppLog.d("Maps", "Arrow unsure: best ${m.drawableName} d=%.3f margin=%.3f".format(m.distance, m.margin))
                }
            }
        }
        // Maps draws a plain "depart" arrow for "Head northeast"; the dash has
        // a compass arrow for it, as the Suzuki app shows.
        if (parsed.textIcon != null && ClusterIcon.compassDegrees(parsed.textIcon) != null) {
            icon = parsed.textIcon
            source = "text"
        }
        if (icon == null && parsed.textIcon != null) {
            icon = parsed.textIcon
            source = "text"
        }
        if (icon == null && lastIcon != null) {
            icon = lastIcon
            source = "previous"
        }
        if (icon != null) lastIcon = icon

        AppLog.d("Maps", "title=\"$title\" text=\"$text\" sub=\"$sub\" -> ${icon?.label} [$source]")
        NavRepository.update(
            NavSnapshot(
                icon = icon,
                iconSource = source,
                metersToTurn = toTurn,
                instruction = parsed.instruction,
                remainingMeters = (remaining ?: lastRemaining).also { lastRemaining = it },
                eta = (parsed.eta ?: lastEta).also { lastEta = it },
            ),
        )
    }

    private var lastExtrasKeys: Set<String> = emptySet()
    private var pointsLogged = false

    /**
     * Positions of the ProgressStyle points (one per maneuver). Stored by
     * the framework as a list of Bundles under "android.progressPoints",
     * each with an int "position". Read defensively; the first point is
     * logged for diagnostics.
     */
    @Suppress("DEPRECATION")
    private fun progressPoints(e: android.os.Bundle): List<Int> = try {
        val raw = e.get(EXTRA_PROGRESS_POINTS)
        val bundles: List<android.os.Bundle> = when (raw) {
            is List<*> -> raw.filterIsInstance<android.os.Bundle>()
            is Array<*> -> raw.filterIsInstance<android.os.Bundle>()
            else -> emptyList()
        }
        if (!pointsLogged && bundles.isNotEmpty()) {
            pointsLogged = true
            val first = bundles.first()
            AppLog.d("Maps", "progress points: ${bundles.size}, first: " +
                first.keySet().joinToString(", ") { k -> "$k=${first.get(k)}" })
        }
        bundles.mapNotNull { b -> b.get("position") as? Int }
    } catch (ex: Exception) {
        AppLog.d("Maps", "Could not read progress points: $ex")
        emptyList()
    }

    /**
     * Logs every text field of the Maps notification whenever a key appears
     * that was not there before. Maps keeps moving the remaining distance
     * around, so this shows where it went.
     */
    @Suppress("DEPRECATION") // Bundle.get: we want every value, whatever its type.
    private fun logExtrasIfNew(e: android.os.Bundle) {
        val keys = e.keySet().toSet()
        if (keys == lastExtrasKeys) return
        lastExtrasKeys = keys
        val dump = keys.sorted().joinToString("; ") { k ->
            when (val v = e.get(k)) {
                is CharSequence, is Number, is Boolean -> "$k=$v"
                is List<*> -> "$k=[${v.size} x ${v.firstOrNull()?.javaClass?.simpleName}]"
                is Array<*> -> "$k=[${v.size} x ${v.firstOrNull()?.javaClass?.simpleName}]"
                null -> "$k=null"
                else -> "$k=<${v.javaClass.simpleName}>"
            }
        }
        AppLog.d("Maps", "extras: $dump")
    }

    private fun largeIconSignature(sbn: StatusBarNotification): IconSignature? {
        val icon = sbn.notification.getLargeIcon() ?: return null
        return try {
            val d = icon.loadDrawable(this) ?: return null
            val size = 96
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, size, size)
            d.draw(Canvas(bmp))
            MapsIconTemplates.signatureOf(bmp).also { bmp.recycle() }
        } catch (e: Exception) {
            AppLog.d("Maps", "Could not read arrow: $e")
            null
        }
    }

    companion object {
        /** Notification.Builder.setShortCriticalText (Android 16). */
        const val EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText"

        /** Notification.ProgressStyle points (Android 16). */
        const val EXTRA_PROGRESS_POINTS = "android.progressPoints"
    }
}
