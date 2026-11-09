package com.th4rv4.suzukinav.nav

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.th4rv4.suzukinav.protocol.ClusterIcon
import com.th4rv4.suzukinav.util.AppLog
import com.th4rv4.suzukinav.util.Prefs
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
                metersToTurn = parsed.metersToTurn,
                instruction = parsed.instruction,
                remainingMeters = (parsed.remainingMeters ?: lastRemaining).also { lastRemaining = it },
                eta = (parsed.eta ?: lastEta).also { lastEta = it },
            ),
        )
    }

    private var lastExtrasKeys: Set<String> = emptySet()

    /**
     * Logs every text field of the Maps notification whenever a key appears
     * that was not there before. Maps keeps moving the remaining distance
     * around, so this shows where it went.
     */
    @Suppress("DEPRECATION") // Bundle.get: we want every value, whatever its type.
    private fun logExtrasIfNew(e: android.os.Bundle) {
        val keys = e.keySet().filter { e.get(it) is CharSequence || e.get(it) is Number || e.get(it) is Boolean }.toSet()
        if (keys == lastExtrasKeys) return
        lastExtrasKeys = keys
        val dump = keys.sorted().joinToString("; ") { k -> "$k=${e.get(k)}" }
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
    }
}
