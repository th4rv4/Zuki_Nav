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
    /** Highest and average engine temperature in °C; null if the dash sent none. */
    val maxEngineC: Int? = null,
    val avgEngineC: Int? = null,
    /** Ignition-off stops inside this trip, from merging rides, and how long they lasted. */
    val stops: Int = 0,
    val stopSeconds: Long = 0,
    /** For merged trips: each riding part, and the stops between them (one fewer). Empty for a plain ride. */
    val legs: List<Leg> = emptyList(),
    val stopList: List<Stop> = emptyList(),
) {
    val id: Long get() = start

    /** Start to end, stops included. */
    val durationSeconds: Long get() = ((end - start) / 1000).coerceAtLeast(0)

    /** Time with the ignition on: start to end, without the stops between merged rides. */
    val ridingSeconds: Long get() = (durationSeconds - stopSeconds).coerceAtLeast(0)

    /** Average while moving, or null for very short rides. */
    val averageKmh: Double?
        get() = if (movingSeconds >= 60) distanceKm / (movingSeconds / 3600.0) else null
}

/** One riding part of a merged trip, between stops. */
data class Leg(val start: Long, val end: Long, val distanceKm: Double, val movingSeconds: Long, val topSpeedKmh: Int)

/** A stop between two legs: where, when, and the rider's own label for it. */
data class Stop(
    val arrive: Long,
    val depart: Long,
    val lat: Double? = null,
    val lng: Double? = null,
    val place: String? = null,
    val label: String? = null,
) {
    val seconds: Long get() = ((depart - arrive) / 1000).coerceAtLeast(0)
}

/** Joins two rides into one trip. */
object TripMerge {

    /** [a] and [b] in either order; the earlier one keeps its id, name and start. */
    fun merge(a: Trip, b: Trip): Trip {
        val (first, second) = if (a.start <= b.start) a to b else b to a
        val gap = ((second.start - first.end) / 1000).coerceAtLeast(0)
        val moving = first.movingSeconds + second.movingSeconds
        val avgEngine = when {
            first.avgEngineC == null -> second.avgEngineC
            second.avgEngineC == null -> first.avgEngineC
            moving <= 0 -> (first.avgEngineC + second.avgEngineC) / 2
            else -> Math.round(
                (first.avgEngineC * first.movingSeconds + second.avgEngineC * second.movingSeconds).toDouble() / moving,
            ).toInt()
        }
        return first.copy(
            end = second.end,
            distanceKm = first.distanceKm + second.distanceKm,
            movingSeconds = moving,
            topSpeedKmh = maxOf(first.topSpeedKmh, second.topSpeedKmh),
            endOdo = second.endOdo,
            name = first.name ?: second.name,
            starred = first.starred || second.starred,
            startLat = first.startLat ?: second.startLat,
            startLng = first.startLng ?: second.startLng,
            startPlace = first.startPlace ?: second.startPlace,
            endLat = second.endLat ?: first.endLat,
            endLng = second.endLng ?: first.endLng,
            endPlace = second.endPlace ?: first.endPlace,
            maxEngineC = listOfNotNull(first.maxEngineC, second.maxEngineC).maxOrNull(),
            avgEngineC = avgEngine,
            stops = first.stops + second.stops + 1,
            stopSeconds = first.stopSeconds + second.stopSeconds + gap,
            legs = joinLegs(first, second),
            stopList = joinStops(first, second),
        )
    }

    /** A trip's legs, or itself as one leg; null when an older merge kept no details. */
    private fun legsOf(t: Trip): List<Leg>? = when {
        t.legs.isNotEmpty() -> t.legs
        t.stops > 0 -> null
        else -> listOf(Leg(t.start, t.end, t.distanceKm, t.movingSeconds, t.topSpeedKmh))
    }

    private fun joinLegs(a: Trip, b: Trip): List<Leg> {
        val la = legsOf(a) ?: return emptyList()
        val lb = legsOf(b) ?: return emptyList()
        return la + lb
    }

    private fun joinStops(a: Trip, b: Trip): List<Stop> {
        if (legsOf(a) == null || legsOf(b) == null) return emptyList()
        val between = Stop(
            arrive = a.end,
            depart = b.start,
            lat = a.endLat ?: b.startLat,
            lng = a.endLng ?: b.startLng,
            place = a.endPlace ?: b.startPlace,
        )
        return a.stopList + between + b.stopList
    }

    /** The ride right after [t] in [all], if any. */
    fun next(t: Trip, all: List<Trip>): Trip? = all.filter { it.start > t.start }.minByOrNull { it.start }
}
