package com.th4rv4.zukinav.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import com.th4rv4.zukinav.util.AppLog
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/** A release published on GitHub. */
data class Release(
    val version: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String,
    val apkSize: Long,
)

/**
 * Checks the project's GitHub releases for a newer build, downloads its APK
 * and hands it to Android's installer. Every CI build is signed with the same
 * key, so a release installs over the current app and keeps its settings.
 */
object Updater {

    const val REPO = "th4rv4/Zuki_Nav"
    private const val LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases"

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val latest: String) : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val done: Long, val total: Long) : State
        /** Waiting for the rider to confirm in Android's prompt. */
        data class Installing(val release: Release) : State
        data class Failed(val message: String, val release: Release? = null) : State
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    @Volatile var state: State = State.Idle
        private set

    fun addListener(l: (State) -> Unit) { listeners.add(l) }
    fun removeListener(l: (State) -> Unit) { listeners.remove(l) }

    private fun set(s: State) = main.post {
        state = s
        listeners.forEach { it(s) }
    }

    val busy: Boolean
        get() = state is State.Checking || state is State.Downloading || state is State.Installing

    /** Looks for a newer release than [installed]. */
    fun check(installed: String) {
        if (busy) return
        set(State.Checking)
        Thread {
            try {
                val r = fetchLatest()
                AppLog.d("Update", "Latest release ${r.version}, installed $installed")
                set(if (Versions.isNewer(r.version, installed)) State.Available(r) else State.UpToDate(r.version))
            } catch (e: Exception) {
                AppLog.d("Update", "Check failed: $e")
                set(State.Failed("Couldn't reach GitHub. Check your connection."))
            }
        }.start()
    }

    /** Downloads [release] straight into an install session, then asks Android to install it. */
    fun install(ctx: Context, release: Release) {
        if (busy) return
        val app = ctx.applicationContext
        set(State.Downloading(release, 0, release.apkSize))
        Thread {
            val installer = app.packageManager.packageInstaller
            var sessionId = -1
            try {
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(app.packageName)
                if (release.apkSize > 0) params.setSize(release.apkSize)
                sessionId = installer.createSession(params)
                installer.openSession(sessionId).use { session ->
                    val conn = open(release.apkUrl)
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                    conn.inputStream.use { input ->
                        session.openWrite("zuki-nav.apk", 0, total.takeIf { it > 0 } ?: -1).use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastShown = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (done - lastShown > 128 * 1024) {
                                    lastShown = done
                                    set(State.Downloading(release, done, total))
                                }
                            }
                            session.fsync(out)
                        }
                    }
                    AppLog.d("Update", "Downloaded ${release.version}, asking Android to install")
                    set(State.Installing(release))
                    val intent = Intent(app, InstallReceiver::class.java).putExtra(InstallReceiver.EXTRA_VERSION, release.version)
                    // Mutable: the installer adds the result to this intent.
                    val pi = PendingIntent.getBroadcast(
                        app, sessionId, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                    )
                    session.commit(pi.intentSender)
                }
            } catch (e: Exception) {
                AppLog.d("Update", "Download failed: $e")
                if (sessionId >= 0) try { installer.abandonSession(sessionId) } catch (_: Exception) {}
                set(State.Failed("Download failed. Try again.", release))
            }
        }.start()
    }

    /** Called by [InstallReceiver] when Android reports how the install went. */
    internal fun finished(ok: Boolean, message: String?) {
        val release = (state as? State.Installing)?.release
        if (ok) {
            set(State.Idle) // The app restarts on the new version.
        } else {
            set(
                if (release != null) State.Available(release)
                else State.Failed(message ?: "Install cancelled."),
            )
        }
    }

    private fun fetchLatest(): Release {
        val conn = open(LATEST)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(body)
        val assets = json.getJSONArray("assets")
        var apk: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) { apk = a; break }
        }
        apk ?: error("release has no APK")
        return Release(
            version = json.getString("tag_name").removePrefix("v"),
            notes = cleanNotes(json.optString("body")),
            pageUrl = json.optString("html_url", RELEASES_PAGE),
            apkUrl = apk.getString("browser_download_url"),
            apkSize = apk.optLong("size", -1),
        )
    }

    /** The release notes list commits as "- message (abc1234)"; keep the messages. */
    private fun cleanNotes(body: String): String =
        body.lines()
            .map { it.trim() }
            .filter { it.startsWith("- ") || it.startsWith("* ") }
            .joinToString("\n") { "• " + it.drop(2).replace(Regex("""\s*\(?\b[0-9a-f]{7,40}\b\)?$"""), "") }
            .ifEmpty { body.trim() }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ZukiNav")
        }
}
