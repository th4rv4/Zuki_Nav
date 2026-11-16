package com.th4rv4.zukinav.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsIndexTest {

    private fun titles(q: String) = SettingsIndex.search(q).map { it.title }

    @Test fun findsByTitle() {
        assertEquals("Speed Alert", titles("speed")[0])
        assertTrue("Reject Unknown Callers" in titles("reject"))
    }

    @Test fun findsByMeaning() {
        assertTrue("Skip Business SMS" in titles("otp"))
        assertTrue("Engine Heat Warning" in titles("overheat"))
        assertTrue("Papers" in titles("insurance"))
        assertTrue("Battery" !in titles("battery") && "Setup Checklist" in titles("battery"))
        assertTrue("Theme" in titles("dark mode"))
    }

    @Test fun titleMatchesComeFirst() {
        val r = titles("temp")
        assertEquals("Engine Warm-Up Temp", r[0])
        assertTrue(r.size >= 3)
    }

    @Test fun everyWordMustMatch() {
        assertTrue(titles("fuel reserve") == listOf("Reserve"))
        assertTrue(titles("zzz").isEmpty())
        assertTrue(titles("  ").isEmpty())
    }
}
