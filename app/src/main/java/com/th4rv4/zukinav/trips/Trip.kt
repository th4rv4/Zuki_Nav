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
    /** When the bike first moved; null for rides saved before this was kept. */
    val movedAt: Long? = null,
    /**
     * The engine was warmed up before moving off: the time from switching on
     * to [movedAt] is shown as Engine Warm Up and counts as part of the ride.
     * Otherwise the ride starts when the bike first moved.
     */
    val warmUp: Boolean = false,
    /** Engine temperature at switch-on and when moving off, for the warm-up. */
    val warmFromC: Int? = null,
    val warmToC: Int? = null,
) {
    val id: Long get() = start

    /** When the ride starts for the rider: switch-on with a warm-up, else the first move. */
    val rideStart: Long get() = if (warmUp) start else movedAt?.coerceIn(start, end) ?: start

    /** The warm-up before moving off, in seconds; 0 without one. */
    val warmUpSeconds: Long get() = if (warmUp && movedAt != null) ((movedAt - start) / 1000).coerceAtLeast(0) else 0

    /** Start to end, stops included. */
    val durationSeconds: Long get() = ((end - rideStart) / 1000).coerceAtLeast(0)

    /** Time with the ignition on: start to end, without the stops between merged rides. */
    val ridingSeconds: Long get() = (durationSeconds - stopSeconds).coerceAtLeast(0)

    /** Short halts at signals and in traffic: not moving, not a stop, not the warm-up. */
    val trafficSeconds: Long get() = (ridingSeconds - movingSeconds - warmUpSeconds).coerceAtLeast(0)

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
    /** Part of the stop spent warming a cooled engine back up, at rest, before moving off; 0 if none. */
    val warmUpSeconds: Long = 0,
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
        // The stop between them runs until the bike moved again, so the
        // second ride's warm-up is part of it, not a second warm-up.
        val resumed = second.movedAt?.coerceIn(second.start, second.end) ?: second.start
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
            stopSeconds = first.stopSeconds + second.stopSeconds + gap + ((resumed - second.start) / 1000),
            legs = joinLegs(first, second),
            stopList = joinStops(first, second),
            movedAt = first.movedAt ?: second.movedAt,
        ).tidy()
    }

    /** A trip's legs, or itself as one leg; null when an older merge kept no details. */
    private fun legsOf(t: Trip): List<Leg>? = when {
        t.legs.isNotEmpty() -> t.legs
        t.stops > 0 -> null
        else -> listOf(Leg(t.rideStart, t.end, t.distanceKm, t.movingSeconds, t.topSpeedKmh))
    }

    private fun joinLegs(a: Trip, b: Trip): List<Leg> {
        val la = legsOf(a) ?: return emptyList()
        val lb = legsOf(b) ?: return emptyList()
        // The second ride's first part starts when the bike moved again.
        val moved = b.movedAt?.coerceIn(b.start, b.end)
        val lbFixed = if (moved == null || lb.isEmpty()) lb else listOf(lb.first().copy(start = maxOf(lb.first().start, moved))) + lb.drop(1)
        return la + lbFixed
    }

    private fun joinStops(a: Trip, b: Trip): List<Stop> {
        if (legsOf(a) == null || legsOf(b) == null) return emptyList()
        val between = Stop(
            arrive = a.end,
            depart = b.movedAt?.coerceIn(b.start, b.end) ?: b.start,
            lat = a.endLat ?: b.startLat,
            lng = a.endLng ?: b.startLng,
            place = a.endPlace ?: b.startPlace,
        )
        return a.stopList + between + b.stopList
    }

    /** The ride right after [t] in [all], if any. */
    fun next(t: Trip, all: List<Trip>): Trip? = all.filter { it.start > t.start }.minByOrNull { it.start }
}

/** A leg with no riding in it: the bike didn't go anywhere between two stops. */
private fun Leg.empty(): Boolean = distanceKm < 0.05 && movingSeconds < 30

/**
 * Stops as the rider sees them:
 *  - no stop before the bike first moved (switched on, then sat a while),
 *  - stops with no riding between them are one stop (switched off, on, and
 *    off again without moving).
 * Works on saved rides too.
 */
fun Trip.tidy(): Trip {
    if (stopList.isEmpty() || legs.size != stopList.size + 1) return this
    val legs = legs.toMutableList()
    val stops = stopList.toMutableList()
    var moved = movedAt
    // Before the first move.
    while (stops.isNotEmpty() && legs.first().empty()) {
        val gone = stops.removeAt(0)
        legs.removeAt(0)
        if (moved == null || moved < gone.depart) moved = gone.depart
    }
    // Back to back.
    var i = 0
    while (i < stops.size - 1) {
        if (legs[i + 1].empty()) {
            val a = stops[i]
            val b = stops[i + 1]
            stops[i] = a.copy(
                depart = b.depart,
                lat = a.lat ?: b.lat,
                lng = a.lng ?: b.lng,
                place = a.place ?: b.place,
                label = a.label ?: b.label,
                warmUpSeconds = if (b.warmUpSeconds > 0) b.warmUpSeconds else a.warmUpSeconds,
            )
            stops.removeAt(i + 1)
            legs.removeAt(i + 1)
        } else {
            i++
        }
    }
    if (stops.size == stopList.size && moved == movedAt) return this
    return copy(
        legs = if (stops.isEmpty()) emptyList() else legs,
        stopList = stops,
        stops = stops.size,
        stopSeconds = stops.sumOf { it.seconds },
        movedAt = moved,
        // The warm-up only ever comes from the ride itself, never from a removed stop.
        warmUp = warmUp && moved == movedAt,
    )
}
