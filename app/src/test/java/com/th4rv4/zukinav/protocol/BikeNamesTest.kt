package com.th4rv4.zukinav.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class BikeNamesTest {
    @Test fun variants() {
        assertEquals(3, BikeNames.variants("Gixxer 250 / SF 250 / FFV").size)
        assertEquals(listOf("V-Strom SX"), BikeNames.variants("V-Strom SX"))
        assertEquals("Gixxer SF 250", BikeNames.name("Gixxer 250 / SF 250 / FFV", "Gixxer SF 250"))
        assertEquals("Gixxer 250", BikeNames.name("Gixxer 250 / SF 250 / FFV", "Avenis"))
    }

    @Test fun lettering() {
        assertEquals("GIXXER SF" to "250", BikeNames.lettering("Gixxer SF 250"))
        assertEquals("GIXXER SF" to "250 FFV", BikeNames.lettering("Gixxer SF 250 FFV"))
        assertEquals("V-STROM" to "SX", BikeNames.lettering("V-Strom SX"))
        assertEquals("GIXXER" to "SF", BikeNames.lettering("Gixxer SF"))
        assertEquals("ACCESS" to "125", BikeNames.lettering("Access 125"))
        assertEquals("ACCESS TFT EDITION" to "", BikeNames.lettering("Access TFT Edition"))
        assertEquals("E-ACCESS" to "", BikeNames.lettering("e-ACCESS"))
    }
}
