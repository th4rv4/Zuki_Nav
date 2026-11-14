package com.th4rv4.zukinav.update

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {
    private val all = listOf(
        "1.6.9" to "• Route maps",
        "1.7.0" to "• Bike tab",
        "1.7.5" to "• Backup",
        "1.8.0" to "• Battery",
        "1.9.0" to "• Clock log",
    )

    @Test fun skippedVersionsAllShow() {
        assertEquals(
            "v1.9.0\n• Clock log\n\nv1.8.0\n• Battery\n\nv1.7.5\n• Backup\n\nv1.7.0\n• Bike tab",
            ReleaseNotes.since(all, "1.6.9", "1.9.0"),
        )
    }

    @Test fun oneStepShowsPlainNotes() {
        assertEquals("• Clock log", ReleaseNotes.since(all, "1.8.0", "1.9.0"))
    }

    @Test fun nothingNewer() {
        assertEquals("", ReleaseNotes.since(all, "1.9.0", "1.9.0"))
    }

    @Test fun ignoresNewerThanLatestAndDuplicates() {
        val list = all + ("2.0.0" to "• Future") + ("v1.9.0" to "• dup")
        assertEquals("v1.9.0\n• Clock log\n\nv1.8.0\n• Battery", ReleaseNotes.since(list, "1.7.5", "1.9.0"))
    }

    @Test fun capsLongLists() {
        val many = (1..20).map { "1.$it.0" to "• change $it" }
        val text = ReleaseNotes.since(many, "0.9.0", "1.20.0")
        assertEquals(ReleaseNotes.MAX_RELEASES, Regex("""(?m)^v""").findAll(text).count())
        assert(text.endsWith("Older changes are on GitHub."))
    }
}
