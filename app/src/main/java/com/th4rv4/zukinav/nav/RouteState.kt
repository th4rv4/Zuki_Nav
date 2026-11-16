package com.th4rv4.zukinav.nav

/**
 * Whether a Google Maps route is open, from its guidance notification.
 *
 * The route is open while the notification is up ("Rerouting..." included).
 * When it goes away the route counts as closed right away if Maps had said
 * "Arriving", otherwise only after it has stayed away for [GONE_MS]: Maps
 * takes it down and puts it back now and then mid-route.
 */
class RouteState {

    private var present = false
    private var arriving = false
    private var removedAt: Long? = null

    /** When the notification was last seen; 0 if never. */
    var lastSeen = 0L
        private set

    fun onPosted(title: String?, now: Long) {
        present = true
        arriving = title?.trim()?.startsWith("Arriving", ignoreCase = true) == true
        removedAt = null
        lastSeen = now
    }

    fun onRemoved(now: Long) {
        if (!present) return
        present = false
        lastSeen = now
        removedAt = if (arriving) null else now
    }

    /** The notification listener came back and found no guidance up. */
    fun onNoneShown(now: Long) {
        if (present) onRemoved(now)
    }

    fun isOpen(now: Long): Boolean {
        if (present) return true
        val gone = removedAt ?: return false
        return now - gone < GONE_MS
    }

    /** When the route will count as closed if nothing changes; null if open with the notification up, or already closed. */
    fun closesAt(now: Long): Long? {
        if (present) return null
        val gone = removedAt ?: return null
        return (gone + GONE_MS).takeIf { it > now }
    }

    fun encode(): String = listOf(present, arriving, removedAt ?: "", lastSeen).joinToString(";")

    companion object {
        /** Gone this long without "Arriving" first: the rider closed the route. */
        const val GONE_MS = 60_000L

        fun decode(s: String?): RouteState {
            val r = RouteState()
            val p = s?.split(';') ?: return r
            if (p.size != 4) return r
            r.present = p[0] == "true"
            r.arriving = p[1] == "true"
            r.removedAt = p[2].toLongOrNull()
            r.lastSeen = p[3].toLongOrNull() ?: 0L
            return r
        }
    }
}
