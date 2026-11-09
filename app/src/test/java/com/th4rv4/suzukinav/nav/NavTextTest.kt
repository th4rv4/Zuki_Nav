package com.th4rv4.suzukinav.nav

import com.th4rv4.suzukinav.protocol.ClusterIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class NavTextTest {

    private val now = LocalTime.of(19, 0)

    @Test fun classicLayout() {
        val p = NavText.parse("350 m", "towards MG Road", "12 min · 4.5 km · 7:42 pm ETA", null, now)
        assertEquals(350.0, p.metersToTurn!!, 0.01)
        assertEquals("towards MG Road", p.instruction)
        assertEquals(4500.0, p.remainingMeters!!, 0.01)
        assertEquals(LocalTime.of(19, 42), p.eta)
        assertNull(p.textIcon)
    }

    @Test fun newerTitleLayout() {
        val p = NavText.parse("1.2 km · Turn left onto Right Bank Rd", null, "25 min · 13 km · 19:25", "1.2 km", now)
        assertEquals(1200.0, p.metersToTurn!!, 0.01)
        assertEquals(ClusterIcon.TURN_LEFT, p.textIcon)
        assertEquals(LocalTime.of(19, 25), p.eta)
    }

    @Test fun etaFromDurationOnly() {
        assertEquals(LocalTime.of(20, 5), NavText.parseEta("1 hr 5 min · 40 km", now))
    }

    @Test fun twelveAm() {
        assertEquals(LocalTime.of(0, 15), NavText.parseEta("12:15 am", now))
    }

    @Test fun maneuvers() {
        fun m(s: String) = NavText.maneuverFromText(s, leftHandTraffic = true)
        assertEquals(ClusterIcon.SHARP_RIGHT, m("Sharp right onto NH 48"))
        assertEquals(ClusterIcon.SLIGHT_LEFT, m("Slight left"))
        assertEquals(ClusterIcon.KEEP_RIGHT, m("Keep right at the fork"))
        assertEquals(ClusterIcon.U_TURN, m("Make a U-turn"))
        assertEquals(ClusterIcon.RAB_CW_90, m("At the roundabout, take the 1st exit onto Ring Rd"))
        assertEquals(ClusterIcon.RAB_CW_180, m("At the roundabout, take the 2nd exit"))
        assertEquals(ClusterIcon.RAB_CW_270, m("At the roundabout, take the third exit"))
        assertEquals(ClusterIcon.DESTINATION, m("Your destination will be on the left"))
        assertEquals(ClusterIcon.TURN_LEFT, m("Turn left onto Harding Rd"))
        assertEquals(ClusterIcon.STRAIGHT, m("Continue onto Right Bank Rd"))
        assertEquals(ClusterIcon.STRAIGHT, m("Head north on MG Rd"))
        assertEquals(ClusterIcon.OFF_RAMP_LEFT, m("Take the exit on the left"))
        assertNull(m("towards Koramangala"))
    }

    @Test fun etaStringIsNotAnArrival() {
        assertNull(NavText.maneuverFromText("Arrive at 16:51"))
    }

    @Test fun units() {
        assertEquals(804.672, NavText.toMeters("0.5 mi")!!, 0.01)
        assertEquals(152.4, NavText.toMeters("500 ft")!!, 0.01)
        assertEquals(2500.0, NavText.toMeters("2,5 km")!!, 0.01)
    }
}
