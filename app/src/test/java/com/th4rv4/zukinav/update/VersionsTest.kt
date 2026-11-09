package com.th4rv4.zukinav.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test fun newerReleases() {
        assertTrue(Versions.isNewer("v0.2.1", "0.2.0"))
        assertTrue(Versions.isNewer("0.3.0", "0.2.9"))
        assertTrue(Versions.isNewer("v0.10.0", "0.9.0"))
        assertTrue(Versions.isNewer("v1.0", "0.9.9"))
    }

    @Test fun sameOrOlder() {
        assertFalse(Versions.isNewer("v0.2.1", "0.2.1"))
        assertFalse(Versions.isNewer("v0.2.0", "0.2.1"))
        assertEquals(0, Versions.compare("v1.0", "1.0.0"))
    }

    @Test fun handBuildsAreOlderThanReleases() {
        assertTrue(Versions.isNewer("v0.2.1", "0.0.0-ab12cd3"))
        assertTrue(Versions.isNewer("v0.2.1", "0.2.1-ab12cd3"))
    }
}
