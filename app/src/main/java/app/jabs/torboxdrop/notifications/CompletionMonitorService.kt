package app.jabs.torboxdrop.notifications

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.jabs.torboxdrop.TorBoxDropApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * User-started, visible live monitoring. Persisted notification and Drive work remains durable;
 * WorkManager provides the slower fallback whenever Android cannot legally keep this service live.
 */
class CompletionMonitorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        CompletionNotificationChannels.ensureCreated(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_LIVE) {
            stopLiveMonitoring()
            return START_NOT_STICKY
        }

        CompletionMonitorScheduler.scheduleFallback(this)
        if (!NotificationCapabilities.current(this).canRunLiveMonitoring || !promoteToForeground()) {
            stopLiveMonitoring()
            return START_NOT_STICKY
        }

        if (monitorJob?.isActive != true) {
            monitorJob = scope.launch { monitorUntilDone() }
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground(): Boolean = try {
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            CompletionNotifications.FOREGROUND_NOTIFICATION_ID,
            CompletionNotifications.monitoring(this),
            foregroundServiceType,
        )
        true
    } catch (_: SecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    }

    private suspend fun monitorUntilDone() {
        try {
            while (scope.isActive) {
                if (!NotificationCapabilities.current(this).canRunLiveMonitoring) break
                val dependencies = CompletionMonitorServiceLocator.get(this)
                val notificationPass = dependencies?.let {
                    CompletionMonitorRunner(it) { claim ->
                        CompletionNotifications.postCompletion(this, claim)
                    }.runOnce()
                }
                val drivePass = if (notificationPass?.authBlocked == true) null else
                    (application as? TorBoxDropApplication)?.driveAutomationRunner()?.runOnce()

                val authBlocked = notificationPass?.authBlocked == true ||
                    drivePass?.torBoxAuthBlocked == true
                if (authBlocked) {
                    val stopped = CompletionMonitorScheduler.cancelRejectedAccount(this,
                        if (notificationPass?.authBlocked == true) notificationPass.accountScope else drivePass?.accountScope)
                    if (stopped) break else continue
                }
                val hasWork = notificationPass?.hasArmedDownloads == true || drivePass?.hasWork == true
                if (!hasWork && CompletionMonitorScheduler.cancelIfIdle(this)) break

                val notificationRemaining = notificationPass?.let {
                    (it.armedAtStart - it.delivered - it.removed).coerceAtLeast(0)
                } ?: 0
                val driveRemaining = drivePass?.watchedAtStart ?: 0
                val remaining = (notificationRemaining + driveRemaining).coerceAtLeast(1)
                val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                if (permissionGranted) {
                    try {
                        NotificationManagerCompat.from(this).notify(
                            CompletionNotifications.FOREGROUND_NOTIFICATION_ID,
                            CompletionNotifications.monitoring(this, remaining),
                        )
                    } catch (_: SecurityException) {
                        break
                    }
                }
                delay(POLL_INTERVAL_MILLIS)
            }
        } finally {
            stopLiveMonitoring()
        }
    }

    /** API 35+ calls this when the data-sync foreground-service budget is exhausted. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        CompletionMonitorScheduler.scheduleFallback(this)
        stopLiveMonitoring()
    }

    private fun stopLiveMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START_LIVE = "app.jabs.torboxdrop.action.START_COMPLETION_MONITOR"
        const val ACTION_STOP_LIVE = "app.jabs.torboxdrop.action.STOP_COMPLETION_MONITOR"
        private const val POLL_INTERVAL_MILLIS = 15_000L

        /** Call only from a visible user action after durable work has been armed. */
        fun startFromUserAction(context: Context): StartResult {
            CompletionNotificationChannels.ensureCreated(context)
            CompletionMonitorScheduler.scheduleFallback(context)
            if (!NotificationCapabilities.current(context).canRunLiveMonitoring) {
                return StartResult.FALLBACK_SCHEDULED
            }
            val intent = Intent(context, CompletionMonitorService::class.java)
                .setAction(ACTION_START_LIVE)
            return try {
                ContextCompat.startForegroundService(context, intent)
                StartResult.STARTED
            } catch (_: IllegalStateException) {
                StartResult.FALLBACK_SCHEDULED
            } catch (_: SecurityException) {
                StartResult.FALLBACK_SCHEDULED
            } catch (_: RuntimeException) {
                StartResult.FALLBACK_SCHEDULED
            }
        }
    }
}

enum class StartResult {
    STARTED,
    FALLBACK_SCHEDULED,
}
