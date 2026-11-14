package com.th4rv4.zukinav.maint

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.th4rv4.zukinav.R
import com.th4rv4.zukinav.ui.MainActivity
import com.th4rv4.zukinav.util.AppLog
import com.th4rv4.zukinav.util.Prefs

/** Phone notifications for maintenance reminders, and a daily check for date-based ones. */
object MaintNotifier {

    private const val CHANNEL = "maintenance"
    private const val NOTIF_BASE = 3000
    const val ACTION_DONE = "com.th4rv4.zukinav.maint.DONE"
    const val ACTION_SNOOZE = "com.th4rv4.zukinav.maint.SNOOZE"
    const val ACTION_CHECK = "com.th4rv4.zukinav.maint.CHECK"
    const val EXTRA_ID = "reminder"

    fun notifyId(id: Long): Int = NOTIF_BASE + (id % 1000).toInt()

    fun show(ctx: Context, alerts: List<Alert>) {
        if (alerts.isEmpty()) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Maintenance", NotificationManager.IMPORTANCE_DEFAULT))
        val s = MaintStore.state(ctx)
        val early = Prefs(ctx).maintEarlyKm
        for (a in alerts) {
            val r = s.reminders.firstOrNull { it.id == a.reminder.id } ?: a.reminder
            val st = MaintBook.status(r, s.odoKm, System.currentTimeMillis(), early)
            val id = notifyId(r.id)
            val open = PendingIntent.getActivity(
                ctx, id,
                Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_MAINTENANCE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val b = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_nav)
                .setContentTitle(MaintBook.headline(a, st))
                .setContentText(MaintBook.detail(r, st))
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(Notification.Action.Builder(null, "Mark Done", action(ctx, ACTION_DONE, r.id, id)).build())
            if (s.odoKm != null) {
                b.addAction(Notification.Action.Builder(null, "Snooze ${MaintBook.SNOOZE_KM} km", action(ctx, ACTION_SNOOZE, r.id, id + 500)).build())
            }
            try {
                nm.notify(id, b.build())
                AppLog.d("Maintenance", "${a.kind}: ${r.name}")
            } catch (e: SecurityException) {
                AppLog.d("Maintenance", "Cannot show the reminder: $e")
            }
        }
    }

    private fun action(ctx: Context, what: String, id: Long, code: Int): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code,
            Intent(ctx, MaintReceiver::class.java).setAction(what).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** A daily check, so date-based reminders arrive even if the bike isn't connected. */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ctx, MaintReceiver::class.java).setAction(ACTION_CHECK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am.setInexactRepeating(
            AlarmManager.RTC, System.currentTimeMillis() + AlarmManager.INTERVAL_HOUR,
            AlarmManager.INTERVAL_HALF_DAY, pi,
        )
    }
}

/** Mark Done and Snooze from a reminder notification, and the twice-daily check. */
class MaintReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(MaintNotifier.EXTRA_ID, -1L)
        when (intent.action) {
            MaintNotifier.ACTION_CHECK -> MaintNotifier.show(context, MaintStore.checkNow(context))
            MaintNotifier.ACTION_DONE -> if (id >= 0) {
                MaintStore.markDone(context, id)
                cancel(context, id)
            }
            MaintNotifier.ACTION_SNOOZE -> if (id >= 0) {
                MaintStore.snooze(context, id)
                cancel(context, id)
            }
        }
    }

    private fun cancel(ctx: Context, id: Long) =
        ctx.getSystemService(NotificationManager::class.java).cancel(MaintNotifier.notifyId(id))
}
