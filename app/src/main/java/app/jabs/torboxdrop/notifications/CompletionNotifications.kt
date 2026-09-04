package app.jabs.torboxdrop.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.jabs.torboxdrop.R

object CompletionNotificationChannels {
    const val MONITORING = "completion_monitoring"
    const val COMPLETIONS = "download_completions"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    MONITORING,
                    context.getString(R.string.monitor_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.monitor_channel_description)
                    setShowBadge(false)
                },
                NotificationChannel(
                    COMPLETIONS,
                    context.getString(R.string.ready_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.ready_channel_description)
                },
            ),
        )
    }
}

object CompletionNotifications {
    const val FOREGROUND_NOTIFICATION_ID = 7_401
    const val ACTION_OPEN_FILES = "app.jabs.torboxdrop.action.OPEN_FILES"

    fun monitoring(context: Context, armedCount: Int? = null): Notification {
        val launchIntent = appLaunchIntent(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            FOREGROUND_NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(context, CompletionMonitorService::class.java)
            .setAction(CompletionMonitorService.ACTION_STOP_LIVE)
        val stopPendingIntent = PendingIntent.getService(
            context,
            FOREGROUND_NOTIFICATION_ID + 1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val detail = when (armedCount) {
            null -> "Checking downloads you asked to watch"
            1 -> "Watching 1 unfinished download"
            else -> "Watching $armedCount unfinished downloads"
        }
        return NotificationCompat.Builder(context, CompletionNotificationChannels.MONITORING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("TorBox completion monitoring")
            .setContentText(detail)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, "Stop live monitoring", stopPendingIntent)
            .build()
    }

    fun postCompletion(context: Context, claim: CompletionClaim): Boolean {
        CompletionNotificationChannels.ensureCreated(context)
        if (!NotificationCapabilities.current(context).canPostCompletion) return false
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) return false
        val key = claim.download.subscriptionKey
        val pendingIntent = openFilesPendingIntent(context, claim.download)
        val notification = NotificationCompat.Builder(context, CompletionNotificationChannels.COMPLETIONS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${claim.download.displayName} is ready")
            .setContentText("Tap to open files")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .addAction(0, "Open files", pendingIntent)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(
                CompletionNotificationIdentity.tag(key),
                CompletionNotificationIdentity.id(key),
                notification,
            )
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** Prevents a notification from one TorBox account opening an item in another account. */
    fun cancelAccountNotifications(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    private fun openFilesPendingIntent(
        context: Context,
        download: ArmedDownload,
    ): PendingIntent {
        val deepLink = Uri.Builder()
            .scheme("torboxdrop")
            .authority("files")
            .appendPath(download.type.routeValue)
            .appendPath(download.downloadId)
            .build()
        val intent = appLaunchIntent(context)
            .setAction(ACTION_OPEN_FILES)
            .setData(deepLink)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            CompletionNotificationIdentity.id(download.subscriptionKey),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun appLaunchIntent(context: Context): Intent =
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent().setClassName(context.packageName, "${context.packageName}.MainActivity")

}
