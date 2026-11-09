package com.th4rv4.suzukinav.protocol

/**
 * Every vehicle Suzuki Ride Connect 8.0 knows, keyed by the cluster's BLE
 * name. Names are 12 characters, e.g. "SBM21xxxxxxx": character 1 is the
 * cluster family, characters 2-4 the model code (`DeviceListingScanActivity.i()`).
 *
 * The families differ in small ways, all reproduced here:
 *  - A: checksum is 255 minus the sum; status frame without weather.
 *  - B: plain-sum checksum; status frame with weather/temperature bytes.
 *  - C (TFT scooters): plain-sum checksum; a leading '0' in the ETA is sent
 *    as a space. The Suzuki app's status frame for these is broken (it
 *    reads a field it never sets and ends up sending 30 zero bytes), so no
 *    status frame is sent to them.
 * Models the Suzuki app calls "Access" (and the BS51 Burgman) use two
 * different turn-icon IDs, see [adjustIcon].
 */
data class ClusterModel(
    /** Characters from index 1 of the BLE name that identify the model. */
    val code: String,
    val displayName: String,
    val type: Type,
    val family: Family,
    /** Uses the Access artwork for left off-ramps and the first CCW roundabout exit. */
    val accessIcons: Boolean = false,
) {
    enum class Type { MOTORCYCLE, SCOOTER }
    enum class Family { A, B, C }
    enum class StatusLayout { A, B, NONE }

    val checksum: SuzukiFrames.Checksum
        get() = if (family == Family.A) SuzukiFrames.Checksum.INVERTED else SuzukiFrames.Checksum.SUM

    val statusLayout: StatusLayout
        get() = when (family) {
            Family.A -> StatusLayout.A
            Family.B -> StatusLayout.B
            Family.C -> StatusLayout.NONE
        }

    /** C-series clusters get a space instead of a leading zero in the ETA (`d2.D()`). */
    val etaLeadingSpace: Boolean get() = family == Family.C

    /**
     * The Suzuki app sends 38 instead of 44 for a left off-ramp, and 44
     * instead of 46 for the first counter-clockwise roundabout exit, on
     * Access-type clusters (`d2.C()`, Mappls IDs 74 and 58).
     */
    fun adjustIcon(id: Int): Int = if (!accessIcons) id else when (id) {
        ClusterIcon.OFF_RAMP_LEFT.id -> ClusterIcon.TAKE_RAMP.id
        ClusterIcon.RAB_CCW_45.id -> ClusterIcon.OFF_RAMP_LEFT.id
        else -> id
    }
}

object ClusterModels {

    private val M = ClusterModel.Type.MOTORCYCLE
    private val S = ClusterModel.Type.SCOOTER
    private val A = ClusterModel.Family.A
    private val B = ClusterModel.Family.B
    private val C = ClusterModel.Family.C

    /** Most specific codes first: matching is by prefix. */
    val ALL: List<ClusterModel> = listOf(
        // C-series (TFT)
        ClusterModel("CE", "e-ACCESS", S, C, accessIcons = true),
        ClusterModel("CS0", "Access TFT Edition", S, C, accessIcons = true),
        ClusterModel("CS1", "Burgman Street TFT Edition", S, C, accessIcons = true),
        // Access (current generation)
        ClusterModel("BS4", "Access", S, B, accessIcons = true),
        ClusterModel("AS21", "Access", S, A, accessIcons = true),
        // A-series
        ClusterModel("AS01", "Access 125", S, A),
        ClusterModel("AS02", "Access 125", S, A),
        ClusterModel("AS11", "Burgman Street", S, A),
        ClusterModel("AS12", "Burgman Street", S, A),
        // B-series scooters
        ClusterModel("BS11", "Burgman Street", S, B),
        ClusterModel("BS12", "Burgman Street", S, B),
        ClusterModel("BS21", "Avenis", S, B),
        ClusterModel("BS22", "Avenis", S, B),
        ClusterModel("BS31", "Burgman Street EX", S, B),
        ClusterModel("BS32", "Burgman Street EX", S, B),
        ClusterModel("BS51", "Burgman Street", S, B, accessIcons = true),
        // B-series motorcycles
        ClusterModel("BM01", "V-Strom SX", M, B),
        ClusterModel("BM11", "Gixxer / Gixxer SF", M, B),
        ClusterModel("BM21", "Gixxer 250 / SF 250 / FFV", M, B),
        ClusterModel("BM22", "Gixxer 250 / SF 250 / FFV", M, B),
    )

    /**
     * The model for a cluster's BLE name. Unknown codes still work: the
     * family letter decides the checksum and frame layout.
     */
    fun fromBleName(name: String?): ClusterModel {
        val n = name?.trim().orEmpty()
        if (n.length >= 2) {
            val rest = n.substring(1)
            ALL.firstOrNull { rest.startsWith(it.code) }?.let { return it }
        }
        val family = when (n.getOrNull(1)) {
            'A' -> ClusterModel.Family.A
            'C' -> ClusterModel.Family.C
            else -> ClusterModel.Family.B
        }
        val type = if (n.getOrNull(2) == 'M') M else S
        val label = if (n.isEmpty()) "Unknown Suzuki" else "Suzuki ${n.take(5)}"
        return ClusterModel(n.drop(1).take(4), label, type, family)
    }

    /** True for names the Suzuki app lists as clusters. */
    fun looksLikeCluster(name: String?): Boolean =
        name != null && name.length == 12 && (name.contains("SA") || name.contains("SB") || name.contains("SC"))

    /** Distinct model names, for the README / UI. */
    val supportedNames: List<String> get() = ALL.map { it.displayName }.distinct()
}
