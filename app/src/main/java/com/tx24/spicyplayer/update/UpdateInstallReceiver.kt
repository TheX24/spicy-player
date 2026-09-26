package com.tx24.spicyplayer.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Where Android's installer reports on an update. It first asks for the user's confirmation,
 * which this opens; a success replaces the app, so only a failure or a cancel comes back here.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> mutableResults.tryEmit(null)
            else -> {
                val reason = if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
                    "Android refused the update: it's signed differently from the installed app. " +
                        "Uninstall this build, then install the new one from GitHub."
                } else {
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The update wasn't installed."
                }
                mutableResults.tryEmit(reason)
            }
        }
    }

    companion object {
        private val mutableResults = MutableSharedFlow<String?>(extraBufferCapacity = 1)
        /** A cancelled install (null) or why one failed. */
        val results: SharedFlow<String?> get() = mutableResults
    }
}
