package app.jabs.torboxdrop.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** The notification-backed operation whose Android capability is being evaluated. */
enum class NotificationCapabilityUse {
    COMPLETION_ALERTS,
    LIVE_MONITORING,
}

/** A user-actionable reason that a notification-backed operation is unavailable. */
enum class NotificationCapabilityIssue {
    RUNTIME_PERMISSION_REQUIRED,
    APP_NOTIFICATIONS_DISABLED,
    COMPLETION_CHANNEL_DISABLED,
    MONITORING_CHANNEL_DISABLED,
}

/**
 * One consistent view of Android's notification controls.
 *
 * Runtime permission, the app-wide switch, and channel switches are independent controls. Checking
 * only one of them can make NotificationManager accept a post that will never be shown.
 */
data class NotificationCapabilitySnapshot(
    val runtimePermissionGranted: Boolean,
    val appNotificationsEnabled: Boolean,
    val completionChannelEnabled: Boolean,
    val monitoringChannelEnabled: Boolean,
) {
    fun blockingIssue(use: NotificationCapabilityUse): NotificationCapabilityIssue? = when {
        !runtimePermissionGranted -> NotificationCapabilityIssue.RUNTIME_PERMISSION_REQUIRED
        !appNotificationsEnabled -> NotificationCapabilityIssue.APP_NOTIFICATIONS_DISABLED
        !completionChannelEnabled -> NotificationCapabilityIssue.COMPLETION_CHANNEL_DISABLED
        use == NotificationCapabilityUse.LIVE_MONITORING && !monitoringChannelEnabled ->
            NotificationCapabilityIssue.MONITORING_CHANNEL_DISABLED
        else -> null
    }

    val canPostCompletion: Boolean
        get() = blockingIssue(NotificationCapabilityUse.COMPLETION_ALERTS) == null

    /** Live monitoring also requires a usable completion channel, because delivery is its purpose. */
    val canRunLiveMonitoring: Boolean
        get() = blockingIssue(NotificationCapabilityUse.LIVE_MONITORING) == null
}

object NotificationCapabilities {
    /** Call [CompletionNotificationChannels.ensureCreated] before this when starting a new flow. */
    fun current(context: Context): NotificationCapabilitySnapshot {
        val appContext = context.applicationContext
        val managerCompat = NotificationManagerCompat.from(appContext)
        val runtimePermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val channelStates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = appContext.getSystemService(NotificationManager::class.java)
            val completionEnabled = manager
                .getNotificationChannel(CompletionNotificationChannels.COMPLETIONS)
                ?.importance
                ?.let { it != NotificationManager.IMPORTANCE_NONE }
                ?: false
            val monitoringEnabled = manager
                .getNotificationChannel(CompletionNotificationChannels.MONITORING)
                ?.importance
                ?.let { it != NotificationManager.IMPORTANCE_NONE }
                ?: false
            completionEnabled to monitoringEnabled
        } else {
            true to true
        }
        return NotificationCapabilitySnapshot(
            runtimePermissionGranted = runtimePermissionGranted,
            appNotificationsEnabled = managerCompat.areNotificationsEnabled(),
            completionChannelEnabled = channelStates.first,
            monitoringChannelEnabled = channelStates.second,
        )
    }

    /**
     * Returns the settings page for a settings-resolvable blocker.
     *
     * A `null` result with [NotificationCapabilityIssue.RUNTIME_PERMISSION_REQUIRED] means the
     * Activity should request `POST_NOTIFICATIONS` instead of opening Settings.
     */
    fun settingsIntent(
        context: Context,
        use: NotificationCapabilityUse,
    ): Intent? {
        CompletionNotificationChannels.ensureCreated(context)
        return when (current(context).blockingIssue(use)) {
            null,
            NotificationCapabilityIssue.RUNTIME_PERMISSION_REQUIRED,
            -> null
            NotificationCapabilityIssue.APP_NOTIFICATIONS_DISABLED -> appNotificationSettings(context)
            NotificationCapabilityIssue.COMPLETION_CHANNEL_DISABLED ->
                channelSettings(context, CompletionNotificationChannels.COMPLETIONS)
            NotificationCapabilityIssue.MONITORING_CHANNEL_DISABLED ->
                channelSettings(context, CompletionNotificationChannels.MONITORING)
        }
    }

    private fun appNotificationSettings(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null),
            )
        }

    private fun channelSettings(context: Context, channelId: String): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        } else {
            appNotificationSettings(context)
        }
}
