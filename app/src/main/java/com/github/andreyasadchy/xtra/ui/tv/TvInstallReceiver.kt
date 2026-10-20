package com.github.andreyasadchy.xtra.ui.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import com.github.andreyasadchy.xtra.R

/**
 * Receives the installer's answer for the session created by [TvUpdater.install]: asks the user to confirm,
 * or reports a failure. Not exported, it's only reached through the PendingIntent given to the installer.
 */
class TvInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TvUpdater.ACTION_INSTALL_STATUS) return
        try {
            val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            if (sessionId == -1 || sessionId != TvUpdater.pendingSessionId(context)) return
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    } ?: return
                    // the app is in the foreground while installing; on newer Android versions this
                    // start can be blocked if the user already left the app
                    try {
                        context.startActivity(
                            confirm
                                .removeFlags(
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                                )
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                        Toast.makeText(context, R.string.tv_not_available, Toast.LENGTH_LONG).show()
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> TvUpdater.clearPendingSessionId(context, sessionId)
                Int.MIN_VALUE -> Unit
                else -> {
                    TvUpdater.clearPendingSessionId(context, sessionId)
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                    Toast.makeText(context, context.getString(R.string.tv_update_install_failed, message), Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            // malformed extras
        }
    }
}
