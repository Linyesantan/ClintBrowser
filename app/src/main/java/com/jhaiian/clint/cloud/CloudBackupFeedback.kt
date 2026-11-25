package com.jhaiian.clint.cloud

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.jhaiian.clint.R

/** Turns every cloud backup outcome into user-visible feedback: a toast in the foreground, a notification otherwise. */
object CloudBackupFeedback {
    const val CHANNEL_ID = "clint_cloud_backup"
    private const val NOTIFICATION_ID = 0x0C10D

    /**
     * Manual runs: toast while the app is on screen, notification otherwise.
     * Scheduled (daily) runs are background runs: always a notification, plus a toast if the app is on screen.
     */
    fun deliver(context: Context, report: CloudBackupReport, scheduled: Boolean) {
        val appContext = context.applicationContext
        val message = message(appContext, report.outcome, report.sizeBytes, report.httpCode, report.detail)
        Handler(Looper.getMainLooper()).post {
            val visible = isAppVisible()
            if (visible) {
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            }
            if (scheduled || !visible) {
                notify(appContext, report.outcome, message)
            }
        }
    }

    fun isSuccess(outcome: CloudBackupOutcome) = outcome == CloudBackupOutcome.SUCCESS

    fun outcomeLabel(context: Context, outcome: CloudBackupOutcome): String = context.getString(
        when (outcome) {
            CloudBackupOutcome.SUCCESS -> R.string.cloud_backup_result_success
            CloudBackupOutcome.CLEANUP_PARTIAL -> R.string.cloud_backup_result_cleanup_partial
            CloudBackupOutcome.TOO_LARGE -> R.string.cloud_backup_result_too_large
            CloudBackupOutcome.AUTH_FAILED -> R.string.cloud_backup_result_auth_failed
            CloudBackupOutcome.NETWORK_ERROR -> R.string.cloud_backup_result_network_error
            CloudBackupOutcome.SERVER_ERROR -> R.string.cloud_backup_result_server_error
            CloudBackupOutcome.NOT_CONFIGURED -> R.string.cloud_backup_result_not_configured
            CloudBackupOutcome.BACKUP_FAILED -> R.string.cloud_backup_result_backup_failed
        }
    )

    fun networkDetail(context: Context, detail: String?): String = when (detail) {
        "UNKNOWN_HOST" -> context.getString(R.string.cloud_backup_net_unknown_host)
        "TIMEOUT" -> context.getString(R.string.cloud_backup_net_timeout)
        "CONNECT" -> context.getString(R.string.cloud_backup_net_connect)
        "TLS" -> context.getString(R.string.cloud_backup_net_tls)
        null, "" -> context.getString(R.string.cloud_backup_net_generic)
        else -> detail
    }

    /** Full, human readable message for an outcome. */
    fun message(context: Context, outcome: CloudBackupOutcome, sizeBytes: Long, httpCode: Int, detail: String?): String {
        val size = Formatter.formatShortFileSize(context, sizeBytes)
        return when (outcome) {
            CloudBackupOutcome.SUCCESS -> context.getString(R.string.cloud_backup_msg_success, size)
            CloudBackupOutcome.CLEANUP_PARTIAL -> {
                val failed = detail?.removePrefix("DELETE:")?.toIntOrNull()
                if (detail?.startsWith("DELETE:") == true && failed != null) {
                    context.getString(R.string.cloud_backup_msg_cleanup_delete_failed, size, failed)
                } else {
                    context.getString(R.string.cloud_backup_msg_cleanup_list_failed, size)
                }
            }
            CloudBackupOutcome.TOO_LARGE -> context.getString(R.string.cloud_backup_msg_too_large, size, WebDavSettings.maxSizeMb(context))
            CloudBackupOutcome.AUTH_FAILED -> context.getString(R.string.cloud_backup_msg_auth_failed, httpCode)
            CloudBackupOutcome.NETWORK_ERROR -> context.getString(R.string.cloud_backup_msg_network_error, networkDetail(context, detail))
            CloudBackupOutcome.SERVER_ERROR -> context.getString(R.string.cloud_backup_msg_server_error, httpCode)
            CloudBackupOutcome.NOT_CONFIGURED -> context.getString(R.string.cloud_backup_msg_not_configured)
            CloudBackupOutcome.BACKUP_FAILED -> context.getString(R.string.cloud_backup_msg_backup_failed, detail ?: "")
        }
    }

    /** Must be called on the main thread. Process importance is not usable here: a running job raises it even in the background. */
    private fun isAppVisible(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.cloud_backup_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = context.getString(R.string.cloud_backup_channel_desc) }
        nm.createNotificationChannel(channel)
    }

    private fun notify(context: Context, outcome: CloudBackupOutcome, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val title = context.getString(
            if (isSuccess(outcome)) R.string.cloud_backup_notification_title_success
            else R.string.cloud_backup_notification_title_failed
        )
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pending = launch?.let {
            PendingIntent.getActivity(context, NOTIFICATION_ID, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_24)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .apply { if (pending != null) setContentIntent(pending) }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }
}
