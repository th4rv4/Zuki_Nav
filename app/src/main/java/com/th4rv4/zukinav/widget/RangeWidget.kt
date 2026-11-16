package com.th4rv4.zukinav.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import com.th4rv4.zukinav.R
import com.th4rv4.zukinav.ble.ClusterLink
import com.th4rv4.zukinav.fuel.FuelStore
import com.th4rv4.zukinav.protocol.BikeData
import com.th4rv4.zukinav.service.DashService
import com.th4rv4.zukinav.ui.MainActivity
import com.th4rv4.zukinav.util.Prefs
import java.util.Locale

/**
 * 2×2 home screen widget: fuel range (or battery on e-ACCESS), the gauge,
 * ODO and a dot that turns green while the bike is connected.
 *
 * It never refreshes on a timer. The app pushes an update when something it
 * shows has actually changed, so it costs nothing while the bike is parked.
 */
class RangeWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        last = null
        push(ctx, manager, ids)
    }

    /** Resized, or the launcher's grid changed: square it up again. */
    override fun onAppWidgetOptionsChanged(ctx: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        last = null
        push(ctx, manager, intArrayOf(id))
    }

    companion object {
        /** What the widgets show right now, so repeated calls cost nothing. */
        @Volatile private var last: String? = null

        private val BARS = intArrayOf(R.id.bar1, R.id.bar2, R.id.bar3, R.id.bar4, R.id.bar5, R.id.bar6)

        /** Call whenever the bike, the fuel gauge, the link or the theme may have changed. */
        fun refresh(ctx: Context) {
            try {
                val manager = AppWidgetManager.getInstance(ctx) ?: return
                val ids = manager.getAppWidgetIds(ComponentName(ctx, RangeWidget::class.java))
                if (ids.isEmpty()) return
                push(ctx, manager, ids)
            } catch (_: Exception) {
            }
        }

        private fun push(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
            val prefs = Prefs(ctx)
            val data = DashService.liveBike ?: prefs.lastBike
            val connected = DashService.linkState == ClusterLink.State.READY
            val bars = data?.fuelBars
            val battery = if (bars == null) data?.batteryPercent else null
            val range = if (bars != null) data?.let { FuelStore.range(ctx, it.odoKm) } else null
            val colors = Colors.of(ctx, prefs.theme)

            val value = when {
                battery != null -> battery.toString()
                range != null -> range.km.toString()
                else -> "--"
            }
            val unit = if (battery != null) "%" else "km"
            val odo = data?.let { "ODO " + String.format(Locale.US, "%,d", it.odoKm) } ?: "ODO --"
            val key = listOf(value, unit, odo, bars, battery, connected, colors.bg).joinToString("|")
            if (key == last) return
            last = key

            val low = (bars != null && bars <= 1) || (battery != null && battery <= 15)
            val fill = if (low) colors.warn else colors.ok
            fun build(): RemoteViews {
                val v = RemoteViews(ctx.packageName, R.layout.widget_range)
                v.setInt(R.id.widget_bg, "setColorFilter", colors.bg)
                v.setImageViewResource(R.id.widget_icon, if (battery != null) R.drawable.ic_battery else R.drawable.ic_fuel)
                v.setInt(R.id.widget_icon, "setColorFilter", if (data == null) colors.muted else fill)
                v.setInt(R.id.widget_dot, "setColorFilter", if (connected) colors.ok else colors.track)
                v.setTextViewText(R.id.widget_value, value)
                v.setTextColor(R.id.widget_value, colors.text)
                v.setTextViewText(R.id.widget_unit, unit)
                v.setTextColor(R.id.widget_unit, colors.muted)
                v.setTextViewText(R.id.widget_odo, odo)
                v.setTextColor(R.id.widget_odo, colors.muted)
                // Battery models have no bars; the percentage says it all.
                v.setViewVisibility(R.id.widget_bars, if (battery != null) android.view.View.GONE else android.view.View.VISIBLE)
                BARS.forEachIndexed { i, id ->
                    v.setInt(id, "setColorFilter", if (bars != null && i < bars) fill else colors.track)
                }
                val label = when {
                    battery != null -> "Battery $battery percent"
                    range != null -> "Range about ${range.km} kilometres"
                    else -> "Range not known yet"
                } + ", $odo, " + if (connected) "bike connected" else "bike not connected"
                v.setContentDescription(R.id.widget_root, label)

                val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                v.setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntent.getActivity(ctx, 41, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
                )
                return v
            }

            // Each widget is drawn as a square in the middle of its cell, whatever the grid.
            for (id in ids) {
                val views = try {
                    squared(ctx, manager.getAppWidgetOptions(id)) { build() }
                } catch (_: Exception) {
                    build()
                }
                manager.updateAppWidget(id, views)
            }
        }

        /**
         * Launcher cells are rarely square (a 5×6 grid makes them tall), so the
         * card is padded down to a square centred in whatever space it's given.
         * On Android 12+ the launcher lists each exact size; older ones give a
         * range, of which portrait uses the min width and max height.
         */
        private fun squared(ctx: Context, options: Bundle, build: () -> RemoteViews): RemoteViews {
            val density = ctx.resources.displayMetrics.density
            fun pad(v: RemoteViews, wDp: Float, hDp: Float): RemoteViews {
                if (wDp <= 0f || hDp <= 0f) return v
                val side = minOf(wDp, hDp)
                val x = ((wDp - side) / 2f * density).toInt()
                val y = ((hDp - side) / 2f * density).toInt()
                v.setViewPadding(R.id.widget_root, x, y, x, y)
                return v
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                @Suppress("DEPRECATION")
                val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                if (!sizes.isNullOrEmpty()) {
                    return RemoteViews(sizes.associateWith { pad(build(), it.width, it.height) })
                }
            }
            val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).toFloat()
            val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).toFloat()
            return pad(build(), w, h)
        }
    }

    /** The widget's own colours, matching the app's theme without touching the app's palette. */
    private data class Colors(val bg: Int, val text: Int, val muted: Int, val track: Int, val ok: Int, val warn: Int) {
        companion object {
            fun of(ctx: Context, theme: Int): Colors {
                val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                return when {
                    theme == Prefs.THEME_LIGHT || (theme == Prefs.THEME_SYSTEM && !night) -> Colors(
                        bg = 0xFFFFFFFF.toInt(), text = 0xFF0E1422.toInt(), muted = 0xFF667085.toInt(),
                        track = 0xFFE3E6EE.toInt(), ok = 0xFF16884A.toInt(), warn = 0xFF8F5D00.toInt(),
                    )
                    theme == Prefs.THEME_AMOLED -> Colors(
                        bg = 0xFF000000.toInt(), text = 0xFFF2F4F8.toInt(), muted = 0xFF7C8496.toInt(),
                        track = 0xFF22242B.toInt(), ok = 0xFF4ADE9A.toInt(), warn = 0xFFFFC966.toInt(),
                    )
                    else -> Colors(
                        bg = 0xFF141821.toInt(), text = 0xFFEEF2F8.toInt(), muted = 0xFF8792A6.toInt(),
                        track = 0xFF2A2F3B.toInt(), ok = 0xFF4ADE9A.toInt(), warn = 0xFFFFC966.toInt(),
                    )
                }
            }
        }
    }
}
