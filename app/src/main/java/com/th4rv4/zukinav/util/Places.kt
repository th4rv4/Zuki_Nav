package com.th4rv4.zukinav.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import java.util.Locale
import java.util.concurrent.Executors

/** Where the bike is, for the start and end of a trip. Uses Android's own location and geocoder. */
object Places {

    private val executor = Executors.newSingleThreadExecutor()

    fun hasForeground(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Trips end with the ignition off and the app in the background, so "all the time" is needed. */
    fun hasBackground(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasPermission(ctx: Context): Boolean = hasForeground(ctx) && hasBackground(ctx)

    /** Calls [done] on a background thread with the current position, or null. */
    fun locate(ctx: Context, done: (Double, Double) -> Unit) {
        if (!hasForeground(ctx)) return
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        try {
            val provider = when {
                Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> LocationManager.GPS_PROVIDER
            }
            val fallback = { lastKnown(lm)?.let { done(it.latitude, it.longitude) } }
            if (Build.VERSION.SDK_INT >= 30) {
                lm.getCurrentLocation(provider, null, executor) { loc: Location? ->
                    if (loc != null) done(loc.latitude, loc.longitude) else fallback()
                }
            } else {
                executor.execute { fallback() }
            }
        } catch (e: SecurityException) {
            AppLog.d("Places", "No location permission")
        } catch (e: Exception) {
            AppLog.d("Places", "Location failed: $e")
        }
    }

    @Suppress("MissingPermission")
    private fun lastKnown(lm: LocationManager): Location? = try {
        lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    } catch (_: Exception) {
        null
    }

    /** A short place name such as "Koramangala". Blocking: call off the main thread. */
    @Suppress("DEPRECATION") // The callback form needs Android 13.
    fun name(ctx: Context, lat: Double, lng: Double): String? = try {
        if (!Geocoder.isPresent()) null
        else Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lng, 1)?.firstOrNull()?.let { a ->
            a.subLocality ?: a.locality ?: a.subAdminArea ?: a.thoroughfare
        }
    } catch (e: Exception) {
        AppLog.d("Places", "No place name: $e")
        null
    }
}
