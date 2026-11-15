package com.th4rv4.zukinav.trips

/** Works out the legs and stops of a trip merged before stops were kept, from its route. */
object TripStops {

    /** A gap this long between two GPS points means the bike was off. */
    const val STOP_GAP_MS = 120_000L

    /**
     * Legs and stops found in [points]; null if the route doesn't show any.
     * With [expected] (the trip's stop count) only that many of the longest
     * gaps count, so a long red light isn't taken for a stop.
     */
    fun fromRoute(points: List<RoutePoint>, expected: Int? = null): Pair<List<Leg>, List<Stop>>? {
        if (points.size < 2) return null
        var gaps = (1 until points.size).filter { points[it].at - points[it - 1].at > STOP_GAP_MS }
        if (expected != null) {
            gaps = gaps.sortedByDescending { points[it].at - points[it - 1].at }.take(expected).sorted()
        }
        if (gaps.isEmpty()) return null
        val parts = ArrayList<List<RoutePoint>>()
        var from = 0
        for (g in gaps) {
            parts += points.subList(from, g)
            from = g
        }
        parts += points.subList(from, points.size)
        if (parts.size < 2) return null
        val legs = parts.map { p ->
            var km = 0.0
            for (i in 1 until p.size) km += Route.metres(p[i - 1], p[i]) / 1000.0
            Leg(
                start = p.first().at,
                end = p.last().at,
                distanceKm = km,
                movingSeconds = (p.last().at - p.first().at) / 1000,
                topSpeedKmh = p.maxOf { it.speedKmh },
            )
        }
        val stops = (0 until parts.size - 1).map { i ->
            val last = parts[i].last()
            Stop(arrive = last.at, depart = parts[i + 1].first().at, lat = last.lat, lng = last.lng)
        }
        return legs to stops
    }
}
