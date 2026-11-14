package com.th4rv4.zukinav.trips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTest {
    private fun p(lat: Double, lng: Double, at: Long = 0, speed: Int = 40, e: Int? = null) = RoutePoint(lat, lng, at, speed, e)

    @Test fun encodeRoundTrip() {
        val a = p(28.613939, 77.209021, 1_700_000_000_000, 62, 91)
        assertEquals(a, Route.decode(Route.encode(a)))
        val b = p(28.6, 77.2, 5, 0, null)
        assertEquals(b, Route.decode(Route.encode(b)))
        assertNull(Route.decode("junk"))
    }

    @Test fun metresIsAboutRight() {
        // 0.001° of latitude is about 111 m.
        val m = Route.metres(p(28.0, 77.0), p(28.001, 77.0))
        assertTrue(m in 110.0..112.5)
    }

    @Test fun thinKeepsEnds() {
        val pts = (0 until 100).map { p(28.0 + it * 0.001, 77.0, it.toLong()) }
        val t = Route.thin(pts, 10)
        assertEquals(10, t.size)
        assertEquals(pts.first(), t.first())
        assertEquals(pts.last(), t.last())
    }

    @Test fun mapsLinkHasAtMostEightStops() {
        val pts = (0 until 50).map { p(28.0 + it * 0.001, 77.0, it.toLong()) }
        val url = Route.googleMapsUrl(pts)!!
        assertTrue(url.startsWith("https://www.google.com/maps/dir/?api=1"))
        assertEquals(8, url.substringAfter("&waypoints=").split("%7C").size)
        assertNull(Route.googleMapsUrl(pts.take(1)))
    }

    @Test fun dropsJitterAndJumps() {
        val a = p(28.0, 77.0, 0)
        assertFalse(Route.worthKeeping(a, p(28.00002, 77.0, 2_000)))
        assertTrue(Route.worthKeeping(a, p(28.0003, 77.0, 2_000)))
        // 1 km in 2 s is a GPS jump.
        assertFalse(Route.worthKeeping(a, p(28.009, 77.0, 2_000)))
    }

    @Test fun trimDropsParkedTail() {
        val pts = listOf(p(1.0, 1.0, 1_000), p(1.0, 1.1, 50_000), p(1.0, 1.2, 200_000))
        assertEquals(2, Route.trim(pts, 1_000, 55_000).size)
    }
}
