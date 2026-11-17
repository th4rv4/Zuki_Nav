package com.th4rv4.zukinav.service

import com.th4rv4.zukinav.service.NotifText.Input
import com.th4rv4.zukinav.service.NotifText.Link
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class NotifTextTest {

    @Test fun ridingWithARoute() {
        val s = NotifText.compose(Input(Link.CONNECTED, engineC = 82, routeKmLeft = 8.46, eta = LocalTime.of(18, 2), rangeKm = 260))
        assertEquals("Connected", s.title)
        assertEquals("8.4 km to go · ETA 6:02 PM · Engine 82°C", s.text)
    }

    @Test fun ridingWithoutARoute() {
        val s = NotifText.compose(Input(Link.CONNECTED, engineC = 82, rangeKm = 267))
        assertEquals("≈ 267 km range · Engine 82°C", s.text)
    }

    @Test fun engineHot() {
        val s = NotifText.compose(Input(Link.CONNECTED, engineC = 103, hotC = 100, rangeKm = 200))
        assertTrue(s.hot)
        assertEquals("Engine Hot · 103°C", s.title)
        assertEquals("≈ 200 km range", s.text)
    }

    @Test fun bikeOffWithARoute() {
        val s = NotifText.compose(Input(Link.BIKE_OFF, engineC = null, routeKmLeft = 248.7, eta = LocalTime.of(21, 40)))
        assertEquals("Bike Off", s.title)
        assertEquals("248 km to go · ETA 9:40 PM", s.text)
    }

    @Test fun waiting() {
        val s = NotifText.compose(Input(Link.WAITING, rangeKm = 267))
        assertEquals("Waiting For Bike", s.title)
        assertEquals("", s.text)
    }

    @Test fun distanceSteps() {
        assertEquals("9.9 km", NotifText.distance(9.97))
        assertEquals("10 km", NotifText.distance(10.4))
        assertEquals("0.3 km", NotifText.distance(0.35))
    }

    @Test fun dashLostMidRide() {
        val s = NotifText.compose(Input(Link.DASH_LOST, rangeKm = 120))
        assertEquals("Dash Not Answering", s.title)
        assertEquals("", s.text)
    }
}
