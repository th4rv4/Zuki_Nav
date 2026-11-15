package com.th4rv4.zukinav.papers

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.th4rv4.zukinav.R
import com.th4rv4.zukinav.ui.MainActivity
import com.th4rv4.zukinav.util.AppLog
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "PUC expires in 15 days" and "PUC has expired", from the twice-daily maintenance check. */
object PaperNotifier {

    private const val CHANNEL = "papers"
    private const val NOTIF_BASE = 4000

    fun check(ctx: Context) {
        val today = LocalDate.now()
        for (p in PaperStore.all(ctx)) {
            val left = p.daysLeft(today) ?: continue
            val stage = when {
                left <= 0 -> 2
                left <= PaperStore.SOON_DAYS -> 1
                else -> 0
            }
            if (stage <= p.notified) continue
            show(ctx, p, left)
            PaperStore.setNotified(ctx, p.id, stage)
        }
    }

    fun cancel(ctx: Context, id: Long) =
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_BASE + (id % 1000).toInt())

    private fun show(ctx: Context, p: Paper, left: Long) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Papers", NotificationManager.IMPORTANCE_DEFAULT))
        val date = LocalDate.ofEpochDay(p.expires ?: return).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US))
        val title = when {
            left < 0 -> "${p.name} has expired"
            left == 0L -> "${p.name} expires today"
            left == 1L -> "${p.name} expires tomorrow"
            else -> "${p.name} expires in $left days"
        }
        val id = NOTIF_BASE + (p.id % 1000).toInt()
        val open = PendingIntent.getActivity(
            ctx, id,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_MAINTENANCE, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nav)
            .setContentTitle(title)
            .setContentText(if (left < 0) "It ran out on $date. Renew it, then update the date in Zuki Nav." else "Valid until $date")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(id, n)
            AppLog.d("Papers", title)
        } catch (e: SecurityException) {
            AppLog.d("Papers", "Cannot show the reminder: $e")
        }
    }
}
