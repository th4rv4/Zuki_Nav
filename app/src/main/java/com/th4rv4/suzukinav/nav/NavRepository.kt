package com.th4rv4.suzukinav.nav

import android.os.Handler
import android.os.Looper
import com.th4rv4.suzukinav.protocol.ClusterIcon
import java.time.LocalTime
import java.util.concurrent.CopyOnWriteArrayList

/** Latest guidance from Google Maps, shared between the listener, the service and the UI. */
data class NavSnapshot(
    val icon: ClusterIcon?,
    /** "maps-icon", "text", "previous" or "none" - how [icon] was decided. */
    val iconSource: String,
    val metersToTurn: Double?,
    val instruction: String?,
    val remainingMeters: Double?,
    val eta: LocalTime?,
    val updatedAt: Long = System.currentTimeMillis(),
)

object NavRepository {

    /** Maps removes and reposts its notification mid-route; only end guidance if it stays gone. */
    private const val END_DEBOUNCE_MS = 4_000L

    @Volatile var current: NavSnapshot? = null
        private set

    private val listeners = CopyOnWriteArrayList<(NavSnapshot?) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val endRunnable = Runnable {
        current = null
        listeners.forEach { it(null) }
    }

    fun update(s: NavSnapshot) {
        main.removeCallbacks(endRunnable)
        current = s
        main.post { listeners.forEach { it(s) } }
    }

    fun scheduleEnd() {
        main.removeCallbacks(endRunnable)
        main.postDelayed(endRunnable, END_DEBOUNCE_MS)
    }

    fun addListener(l: (NavSnapshot?) -> Unit) { listeners.add(l) }
    fun removeListener(l: (NavSnapshot?) -> Unit) { listeners.remove(l) }
}
