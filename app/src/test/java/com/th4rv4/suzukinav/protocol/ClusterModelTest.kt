package com.th4rv4.suzukinav.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterModelTest {

    private fun m(name: String?) = ClusterModels.fromBleName(name)

    @Test fun everyKnownCode() {
        val expected = mapOf(
            "SCE01ABCDEFG" to "e-ACCESS",
            "SCS01ABCDEFG" to "Access TFT Edition",
            "SCS11ABCDEFG" to "Burgman Street TFT Edition",
            "SBS41ABCDEFG" to "Access",
            "SAS21ABCDEFG" to "Access",
            "SAS01ABCDEFG" to "Access 125",
            "SAS02ABCDEFG" to "Access 125",
            "SAS11ABCDEFG" to "Burgman Street",
            "SAS12ABCDEFG" to "Burgman Street",
            "SBS11ABCDEFG" to "Burgman Street",
            "SBS12ABCDEFG" to "Burgman Street",
            "SBS21ABCDEFG" to "Avenis",
            "SBS22ABCDEFG" to "Avenis",
            "SBS31ABCDEFG" to "Burgman Street EX",
            "SBS32ABCDEFG" to "Burgman Street EX",
            "SBS51ABCDEFG" to "Burgman Street",
            "SBM01ABCDEFG" to "V-Strom SX",
            "SBM11ABCDEFG" to "Gixxer / Gixxer SF",
            "SBM21ABCDEFG" to "Gixxer 250 / SF 250 / FFV",
            "SBM22ABCDEFG" to "Gixxer 250 / SF 250 / FFV",
        )
        for ((name, model) in expected) {
            assertEquals(name, model, m(name).displayName)
            assertTrue(name, ClusterModels.looksLikeCluster(name))
        }
    }

    @Test fun familyRules() {
        assertEquals(SuzukiFrames.Checksum.INVERTED, m("SAS01ABCDEFG").checksum)
        assertEquals(ClusterModel.StatusLayout.A, m("SAS01ABCDEFG").statusLayout)
        assertEquals(SuzukiFrames.Checksum.SUM, m("SBM21ABCDEFG").checksum)
        assertEquals(ClusterModel.StatusLayout.B, m("SBM21ABCDEFG").statusLayout)
        assertEquals(SuzukiFrames.Checksum.SUM, m("SCE01ABCDEFG").checksum)
        assertEquals(ClusterModel.StatusLayout.NONE, m("SCE01ABCDEFG").statusLayout)
        assertTrue(m("SCS11ABCDEFG").etaLeadingSpace)
        assertFalse(m("SBM21ABCDEFG").etaLeadingSpace)
    }

    @Test fun accessIconQuirks() {
        for (name in listOf("SCE01ABCDEFG", "SBS41ABCDEFG", "SAS21ABCDEFG", "SBS51ABCDEFG")) {
            assertEquals(name, ClusterIcon.TAKE_RAMP.id, m(name).adjustIcon(ClusterIcon.OFF_RAMP_LEFT.id))
            assertEquals(name, ClusterIcon.OFF_RAMP_LEFT.id, m(name).adjustIcon(ClusterIcon.RAB_CCW_45.id))
        }
        assertEquals(ClusterIcon.OFF_RAMP_LEFT.id, m("SBM21ABCDEFG").adjustIcon(ClusterIcon.OFF_RAMP_LEFT.id))
        assertEquals(ClusterIcon.TURN_LEFT.id, m("SCE01ABCDEFG").adjustIcon(ClusterIcon.TURN_LEFT.id))
    }

    @Test fun unknownCodesFallBackByFamily() {
        assertEquals(SuzukiFrames.Checksum.INVERTED, m("SAX99ABCDEFG").checksum)
        assertEquals(ClusterModel.StatusLayout.NONE, m("SCX99ABCDEFG").statusLayout)
        assertEquals(ClusterModel.Type.MOTORCYCLE, m("SBM99ABCDEFG").type)
        assertEquals(SuzukiFrames.Checksum.SUM, m(null).checksum)
        assertFalse(ClusterModels.looksLikeCluster("Galaxy Buds"))
    }
}
