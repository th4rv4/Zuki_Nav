package com.th4rv4.zukinav.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineStateTest {

    @Test fun noLimitsNoLabel() {
        assertNull(EngineState.of(86, 0, 0))
    }

    @Test fun bothLimits() {
        assertEquals(EngineState.WARMING_UP, EngineState.of(45, 70, 110))
        assertEquals(EngineState.NORMAL, EngineState.of(70, 70, 110))
        assertEquals(EngineState.NORMAL, EngineState.of(109, 70, 110))
        assertEquals(EngineState.HOT, EngineState.of(110, 70, 110))
    }

    @Test fun oneLimit() {
        assertEquals(EngineState.NORMAL, EngineState.of(45, 0, 110))
        assertEquals(EngineState.HOT, EngineState.of(115, 0, 110))
        assertEquals(EngineState.WARMING_UP, EngineState.of(45, 70, 0))
        assertEquals(EngineState.NORMAL, EngineState.of(130, 70, 0))
    }
}
