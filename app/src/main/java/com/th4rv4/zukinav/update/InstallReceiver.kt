package com.th4rv4.zukinav.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.th4rv4.zukinav.util.AppLog

/** Receives the installer's result and shows Android's "Update this app?" prompt. */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    Updater.finished(false, "Android didn't show the install prompt.")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                AppLog.d("Update", "Installed ${intent.getStringExtra(EXTRA_VERSION)}")
                Updater.finished(true, null)
            }
            else -> {
                AppLog.d("Update", "Install ended: status $status, $message")
                Updater.finished(false, message)
            }
        }
    }

    companion object {
        const val EXTRA_VERSION = "version"
    }
}
