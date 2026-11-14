package com.th4rv4.zukinav.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.th4rv4.zukinav.trips.TripStore
import com.th4rv4.zukinav.util.AppLog
import com.th4rv4.zukinav.util.Prefs
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One zip with everything Zuki Nav keeps: the files in its private storage
 * (trips, routes, fuel, maintenance, avatar) and its settings.
 *
 * manifest.json  what's inside and when it was made
 * prefs.json     settings, with their types
 * files/...      the app's private files
 */
object Backup {

    const val FORMAT = 1
    /** "ZukiNav-2026-11-15_21-14.zip": the date and time it was made. */
    fun fileName(at: Long): String = "ZukiNav-" + java.time.Instant.ofEpochMilli(at)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm", java.util.Locale.US)) + ".zip"

    data class Info(val createdAt: Long, val appVersion: String, val trips: Int, val routes: Int, val bytes: Long)

    /** Settings that belong to this install only, so a restore leaves them alone. */
    private val LOCAL_KEYS = setOf(
        Prefs.K_BACKUP_URI, Prefs.K_BACKUP_FOLDER, Prefs.K_BACKUP_LAST, Prefs.K_BACKUP_LAST_SIZE, Prefs.K_BACKUP_AFTER_TRIP,
        "active_trip",
    )

    private val lock = Any()

    /** Writes a backup to [out]. Returns its summary. */
    fun write(ctx: Context, out: OutputStream): Info = synchronized(lock) {
        val app = ctx.applicationContext
        val appVersion = version(app)
        val files = app.filesDir
        val all = files.walkTopDown().filter { it.isFile && !it.name.endsWith(".tmp") }.toList()
        val trips = TripStore.all(app).size
        val routes = all.count { it.parentFile?.name == "routes" }
        val created = System.currentTimeMillis()
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(
                JSONObject()
                    .put("app", "com.th4rv4.zukinav")
                    .put("format", FORMAT)
                    .put("created", created)
                    .put("version", appVersion)
                    .put("trips", trips)
                    .put("routes", routes)
                    .toString(2).toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("prefs.json"))
            zip.write(Prefs(app).exportAll(LOCAL_KEYS).toString().toByteArray())
            zip.closeEntry()
            for (f in all) {
                zip.putNextEntry(ZipEntry("files/" + f.relativeTo(files).invariantSeparatorsPath))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        Info(created, appVersion, trips, routes, all.sumOf { it.length() })
    }

    /** Reads the manifest of a backup without changing anything. Null if it isn't a Zuki Nav backup. */
    fun peek(input: InputStream): Info? {
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: return null
                if (e.name == "manifest.json") {
                    val m = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    if (m.optString("app") != "com.th4rv4.zukinav") return null
                    if (m.optInt("format", 0) > FORMAT) throw NewerBackup()
                    return Info(m.optLong("created"), m.optString("version"), m.optInt("trips"), m.optInt("routes"), 0)
                }
            }
        }
    }

    class NewerBackup : Exception("This backup is from a newer Zuki Nav. Update the app first.")

    /**
     * Replaces the app's data with the backup's. Unpacks into a fresh folder
     * first, so a broken file changes nothing.
     */
    fun restore(ctx: Context, input: InputStream) = synchronized(lock) {
        val app = ctx.applicationContext
        val staging = File(app.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var prefs: JSONObject? = null
        var sawManifest = false
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                when {
                    e.name == "manifest.json" -> {
                        val m = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                        require(m.optString("app") == "com.th4rv4.zukinav") { "Not a Zuki Nav backup" }
                        if (m.optInt("format", 0) > FORMAT) throw NewerBackup()
                        sawManifest = true
                    }
                    e.name == "prefs.json" -> prefs = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    e.name.startsWith("files/") && !e.isDirectory -> {
                        val rel = e.name.removePrefix("files/")
                        val target = File(staging, rel)
                        // Never write outside the staging folder.
                        require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "Bad entry" }
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
        require(sawManifest) { "Not a Zuki Nav backup" }

        // Unpacked fine: swap the data in.
        val files = app.filesDir
        files.listFiles()?.forEach { it.deleteRecursively() }
        staging.walkTopDown().filter { it.isFile }.forEach { f ->
            val target = File(files, f.relativeTo(staging).path)
            target.parentFile?.mkdirs()
            f.copyTo(target, overwrite = true)
        }
        staging.deleteRecursively()
        prefs?.let { Prefs(app).importAll(it, LOCAL_KEYS) }
        AppLog.d("Backup", "Restored a backup")
    }

    /**
     * Backs up into the rider's chosen folder as a new file named with the
     * date and time, then deletes the previous one, so the folder only ever
     * holds the latest backup. False if there is no folder or it can't be
     * written; the previous backup is then left as it was.
     */
    fun toSavedFolder(ctx: Context): Boolean {
        val prefs = Prefs(ctx)
        val tree = prefs.backupFolder?.let { Uri.parse(it) } ?: return false
        val resolver = ctx.contentResolver
        return try {
            val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                tree, android.provider.DocumentsContract.getTreeDocumentId(tree),
            )
            val name = fileName(System.currentTimeMillis())
            val doc = android.provider.DocumentsContract.createDocument(resolver, parent, "application/zip", name) ?: return false
            val info = try {
                (resolver.openOutputStream(doc, "wt") ?: throw IllegalStateException("No output")).use { write(ctx, it) }
            } catch (e: Exception) {
                try { android.provider.DocumentsContract.deleteDocument(resolver, doc) } catch (_: Exception) {}
                throw e
            }
            // The new one is safely written: remove the old one.
            val old = prefs.backupUri?.let { Uri.parse(it) }
            if (old != null && old != doc) {
                try {
                    android.provider.DocumentsContract.deleteDocument(resolver, old)
                } catch (e: Exception) {
                    AppLog.d("Backup", "Could not delete the previous backup: $e")
                }
            }
            prefs.backupUri = doc.toString()
            prefs.backupLastAt = info.createdAt
            prefs.backupLastSize = info.bytes
            AppLog.d("Backup", "Backed up ${info.trips} trips to $name")
            true
        } catch (e: Exception) {
            AppLog.d("Backup", "Could not back up: $e")
            false
        }
    }

    /** The name of the folder or file at [uri], for showing in Settings. */
    fun displayName(ctx: Context, uri: Uri, tree: Boolean): String? = try {
        val doc = if (tree) android.provider.DocumentsContract.buildDocumentUriUsingTree(
            uri, android.provider.DocumentsContract.getTreeDocumentId(uri),
        ) else uri
        ctx.contentResolver.query(doc, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /** After a trip is saved: back up in the background if the rider asked for it. */
    fun afterTrip(ctx: Context) {
        val prefs = Prefs(ctx)
        if (!prefs.backupAfterTrip || prefs.backupFolder == null) return
        val app = ctx.applicationContext
        Thread { toSavedFolder(app) }.start()
    }

    @Suppress("DEPRECATION")
    fun version(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    /** Keeps write access to the chosen backup folder after the app restarts. */
    fun keepAccess(ctx: Context, uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: Exception) {
            AppLog.d("Backup", "Could not keep access to the backup folder: $e")
        }
    }
}
