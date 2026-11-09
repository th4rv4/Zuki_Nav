package com.th4rv4.zukinav.trips

/** One saved ride, from ignition on to ignition off. */
data class Trip(
    /** Start time in ms; also the trip's id. */
    val start: Long,
    val end: Long,
    val distanceKm: Double,
    /** Time spent moving, in seconds. */
    val movingSeconds: Long,
    val topSpeedKmh: Int,
    val startOdo: Int,
    val endOdo: Int,
    val name: String? = null,
    val starred: Boolean = false,
    val startLat: Double? = null,
    val startLng: Double? = null,
    val endLat: Double? = null,
    val endLng: Double? = null,
    val startPlace: String? = null,
    val endPlace: String? = null,
) {
    val id: Long get() = start

    val durationSeconds: Long get() = ((end - start) / 1000).coerceAtLeast(0)

    /** Average while moving, or null for very short rides. */
    val averageKmh: Double?
        get() = if (movingSeconds >= 60) distanceKm / (movingSeconds / 3600.0) else null
}
