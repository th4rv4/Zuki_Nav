package com.th4rv4.zukinav.alerts

/**
 * Missed While Parked: whether a missed call or unread message that came in
 * while the bike was off still shows on the dash on the next ride.
 */
object ParkedAlerts {
    const val NEVER = 0
    const val MIN_30 = 1
    const val HOUR_1 = 2
    const val ALWAYS = 3

    val LABELS = listOf("Never", "30 Min", "1 Hr", "Always")

    /** Whether one that came in at [at] still shows at [now]. */
    fun keeps(choice: Int, at: Long, now: Long): Boolean = when (choice) {
        MIN_30 -> now - at <= 30 * 60_000L
        HOUR_1 -> now - at <= 60 * 60_000L
        ALWAYS -> true
        else -> false
    }
}
