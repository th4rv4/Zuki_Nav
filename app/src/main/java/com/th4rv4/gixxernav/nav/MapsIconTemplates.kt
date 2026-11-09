package com.th4rv4.gixxernav.nav

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import com.th4rv4.gixxernav.util.AppLog

/**
 * Builds the arrow templates from the Google Maps app installed on this phone.
 * Maps ships its turn arrows as named drawables (`maneuver_turn_sharp_left`,
 * ...); each one is rendered, fingerprinted and labelled with its name.
 * Rebuilt when Maps updates.
 */
object MapsIconTemplates {

    const val MAPS_PKG = "com.google.android.apps.maps"
    private const val RENDER_PX = 96

    @Volatile private var cached: IconMatcher? = null
    @Volatile private var cachedVersion: Long = -1

    fun matcher(context: Context): IconMatcher {
        val version = mapsVersion(context)
        cached?.let { if (version == cachedVersion) return it }
        synchronized(this) {
            cached?.let { if (version == cachedVersion) return it }
            val built = build(context)
            cached = built
            cachedVersion = version
            return built
        }
    }

    private fun mapsVersion(context: Context): Long = try {
        val info = context.packageManager.getPackageInfo(MAPS_PKG, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: PackageManager.NameNotFoundException) {
        -2
    }

    private fun build(context: Context): IconMatcher {
        val res = try {
            context.packageManager.getResourcesForApplication(MAPS_PKG)
        } catch (e: Exception) {
            AppLog.d("Icons", "Google Maps not found, arrow matching disabled: $e")
            return IconMatcher(emptyList())
        }
        val out = ArrayList<Pair<String, IconSignature>>()
        var missing = 0
        for (name in MapsManeuvers.NAMES) {
            try {
                @Suppress("DiscouragedApi")
                val id = res.getIdentifier(name, "drawable", MAPS_PKG)
                if (id == 0) { missing++; continue }
                val sig = signatureOf(res.getDrawable(id, null))
                if (sig == null) missing++ else out += name to sig
            } catch (e: Exception) {
                missing++
            }
        }
        AppLog.d("Icons", "Loaded ${out.size} Maps arrow templates ($missing not found)")
        return IconMatcher(out)
    }

    fun signatureOf(d: Drawable): IconSignature? {
        val bmp = Bitmap.createBitmap(RENDER_PX, RENDER_PX, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, RENDER_PX, RENDER_PX)
        d.draw(Canvas(bmp))
        return signatureOf(bmp).also { bmp.recycle() }
    }

    fun signatureOf(bmp: Bitmap): IconSignature? {
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return IconSignature.of(px, bmp.width, bmp.height)
    }
}
