package com.th4rv4.zukinav.util

/**
 * Where the Zuki Sim test build plugs in. Unset in the real app, so a frame
 * the real dash never sends is simply ignored there.
 */
object SimHooks {

    /** A simulated dash's own frame (type 'Z'): ride time, speed-up and a made-up GPS position. */
    @Volatile var onSimFrame: ((ByteArray) -> Unit)? = null

    /**
     * Finds the simulated dash by its name and hands back its current
     * address, or null if it isn't there. A phone playing the dash changes
     * its Bluetooth address every few minutes (a real dash keeps one), so
     * the test build looks it up before each connect.
     */
    @Volatile var findDash: (((String?) -> Unit) -> Unit)? = null

    /** Zuki Sim's Fake Alerts: the phone's call state while a made-up call rings or is answered. */
    @Volatile var fakePhoneState: com.th4rv4.zukinav.alerts.AlertTracker.PhoneState? = null

    /** True in the test build. */
    val isSim: Boolean get() = onSimFrame != null
}
