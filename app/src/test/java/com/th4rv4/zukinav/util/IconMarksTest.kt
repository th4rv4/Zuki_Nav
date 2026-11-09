package com.th4rv4.zukinav.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IconMarksTest {

    @Test fun roundTrip() {
        val m = IconMarks()
            .with(7, IconMarks.Mark.WRONG)
            .with(1, IconMarks.Mark.RIGHT)
            .with(4, IconMarks.Mark.RIGHT)
        assertEquals("1:R,4:R,7:W", m.encode())
        val back = IconMarks.decode(m.encode())
        assertEquals(IconMarks.Mark.WRONG, back[7])
        assertEquals(2, back.count(IconMarks.Mark.RIGHT))
    }

    @Test fun clearAndIgnoreJunk() {
        val m = IconMarks.decode("1:R,x:Q,,5:W,9").with(1, null)
        assertNull(m[1])
        assertEquals("5:W", m.encode())
    }

    @Test fun summary() {
        val s = IconMarks.decode("1:R,2:W").summary("Gixxer 250 / SF 250 / FFV", 3)
        assertEquals(
            "Zuki Nav Dash Test - Gixxer 250 / SF 250 / FFV\nRight: 1\nWrong: 2\nUntested: 3",
            s,
        )
    }
}
