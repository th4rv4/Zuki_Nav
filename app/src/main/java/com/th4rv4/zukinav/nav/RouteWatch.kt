package com.th4rv4.zukinav.nav

import android.content.Context
import com.th4rv4.zukinav.trips.TripLog
import com.th4rv4.zukinav.util.AppLog
import com.th4rv4.zukinav.util.Prefs

/**
 * Keeps [RouteState] up to date from the Maps listener and saved across
 * restarts, and tells the trip log when a route opens or closes. Main thread.
 */
object RouteWatch {

    private var state: RouteState? = null
    private var savedAt = 0L

    private fun state(ctx: Context): RouteState =
        state ?: RouteState.decode(Prefs(ctx).routeState).also { state = it }

    fun isOpen(ctx: Context, now: Long = System.currentTimeMillis()) = state(ctx).isOpen(now)

    /** When an open route will count as closed if Maps stays quiet; null if not counting down. */
    fun closesAt(ctx: Context, now: Long = System.currentTimeMillis()) = state(ctx).closesAt(now)

    fun onPosted(ctx: Context, title: String?) = change(ctx) { s, now -> s.onPosted(title, now) }

    fun onRemoved(ctx: Context) = change(ctx) { s, now -> s.onRemoved(now) }

    fun onNoneShown(ctx: Context) = change(ctx) { s, now -> s.onNoneShown(now) }

    private fun change(ctx: Context, block: (RouteState, Long) -> Unit) {
        val s = state(ctx)
        val now = System.currentTimeMillis()
        val before = s.encode()
        val wasOpen = s.isOpen(now)
        block(s, now)
        val after = s.encode()
        // lastSeen moves on every repost; save that part at most every 30 s.
        if (after.substringBeforeLast(';') != before.substringBeforeLast(';') || now - savedAt > 30_000L) {
            Prefs(ctx).routeState = after
            savedAt = now
        }
        val open = s.isOpen(now)
        if (open != wasOpen) {
            AppLog.d("Trips", if (open) "Maps route open" else "Maps route closed")
            TripLog.onRouteChanged(ctx)
        } else if (s.closesAt(now) != null) {
            TripLog.onRouteChanged(ctx)
        }
    }
}
