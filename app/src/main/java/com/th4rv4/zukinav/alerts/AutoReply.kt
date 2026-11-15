package com.th4rv4.zukinav.alerts

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.th4rv4.zukinav.util.AppLog
import com.th4rv4.zukinav.util.Prefs

/**
 * Auto-Reply While Riding: answers a WhatsApp chat through the Reply button
 * on WhatsApp's own notification, so no extra permission is needed and
 * nothing is sent by SMS.
 *
 * Each chat gets one reply per window (or one per ride), however many
 * messages it sends.
 */
class AutoReply(private val ctx: Context, private val prefs: Prefs) {

    /** Chat -> when it was last answered, for this ride. */
    private val answered = HashMap<String, Long>()
    private var riding = false

    /** Call for every notification; [connected] is whether the bike is linked now. */
    fun onNotification(sbn: StatusBarNotification, connected: Boolean) {
        if (!connected) {
            // A new ride starts with a clean slate.
            if (riding) answered.clear()
            riding = false
            return
        }
        riding = true
        if (!prefs.autoReply) return
        if (!AlertTracker.isWhatsApp(sbn.packageName)) return
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (n.category != null && n.category != Notification.CATEGORY_MESSAGE) return
        val e = n.extras
        if (e.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false) || e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) != null) return
        val chat = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        if (chat.isEmpty()) return
        if (prefs.autoReplyContactsOnly && CallFilter.isUnknown(chat)) return
        // Our own reply can come back as an update of the same notification.
        if (lastIsMine(e)) return

        val now = System.currentTimeMillis()
        val key: String = n.shortcutId?.takeIf { it.isNotBlank() } ?: chat
        val every = prefs.autoReplyEveryMin
        val last = answered[key]
        if (last != null && (every == 0 || now - last < every * 60_000L)) return

        val action = replyAction(n) ?: return
        val inputs = action.remoteInputs ?: return
        val text = prefs.autoReplyText
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(inputs, fill, results)
        try {
            action.actionIntent.send(ctx, 0, fill)
            answered[key] = now
            AppLog.d("Reply", "Auto-replied to a WhatsApp chat")
        } catch (ex: Exception) {
            AppLog.d("Reply", "Could not auto-reply: $ex")
        }
    }

    /** The notification's Reply action: the one marked as reply, else any with a text box. */
    private fun replyAction(n: Notification): Notification.Action? {
        val withInput = n.actions?.filter { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }.orEmpty()
        return withInput.firstOrNull { it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY } ?: withInput.firstOrNull()
    }

    /** True when the newest message in the notification is one we (or the rider) sent. */
    @Suppress("DEPRECATION")
    private fun lastIsMine(e: Bundle): Boolean = try {
        val msgs = e.getParcelableArray(Notification.EXTRA_MESSAGES)
        val last = (msgs?.lastOrNull() as? Bundle)
        last != null && last.getCharSequence("sender") == null && last.get("sender_person") == null
    } catch (_: Exception) { false }
}
