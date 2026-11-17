package com.th4rv4.zukinav.alerts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract

/**
 * Finds the name a caller is saved under, so the dash shows that rather than
 * the name the network sends with the call. Needs the Contacts permission;
 * nothing leaves the phone.
 */
object ContactNames {

    class Lookup(
        /** The saved contact name, or null when the caller isn't saved or couldn't be looked up. */
        val name: String?,
        /** The caller's number, when the notification carries it. */
        val number: String?,
        /** The contacts were searched, so a null [name] means the caller isn't saved. */
        val checked: Boolean,
        /** For the log: where the name on the dash comes from. */
        val source: String,
    )

    fun allowed(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /**
     * [uris] are the call's Person uris ("tel:+91…" or a contacts link), in
     * order of trust; [title] is the notification title, used when it is a
     * bare number.
     */
    fun lookup(ctx: Context, uris: List<String>, title: String?): Lookup {
        val number = uris.firstNotNullOfOrNull { numberOf(it) } ?: title?.takeIf { looksLikeNumber(it) }?.trim()
        if (!allowed(ctx)) return Lookup(null, number, false, "phone app (Contacts not allowed)")
        try {
            for (u in uris) {
                if (!u.startsWith("content://com.android.contacts")) continue
                nameAt(ctx, Uri.parse(u), ContactsContract.Contacts.DISPLAY_NAME)?.let {
                    return Lookup(it, number, true, "contacts")
                }
            }
            if (number == null) return Lookup(null, null, false, "phone app (no number on the call)")
            val filter = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            val name = nameAt(ctx, filter, ContactsContract.PhoneLookup.DISPLAY_NAME)
            return if (name != null) Lookup(name, number, true, "contacts")
            else Lookup(null, number, true, "phone app (number not saved)")
        } catch (e: Exception) {
            return Lookup(null, number, false, "phone app (contacts lookup failed: ${e.javaClass.simpleName})")
        }
    }

    private fun nameAt(ctx: Context, uri: Uri, column: String): String? =
        ctx.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.trim()?.takeIf { it.isNotEmpty() } else null
        }

    private fun numberOf(uri: String): String? {
        if (!uri.startsWith("tel:", ignoreCase = true)) return null
        return Uri.decode(uri.substring(4)).trim().takeIf { looksLikeNumber(it) }
    }

    private fun looksLikeNumber(s: String): Boolean =
        Regex("""^\+?\d{4,15}$""").matches(s.replace(Regex("""[\s\-().‎‏‪-‮]"""), ""))
}
