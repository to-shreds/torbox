package app.jabs.torboxdrop.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.jabs.torboxdrop.TorBoxDropApplication

class CompletionMonitorWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val notificationDependencies = CompletionMonitorServiceLocator.get(applicationContext)
        CompletionNotificationChannels.ensureCreated(applicationContext)
        val notificationPass = notificationDependencies?.let { dependencies ->
            CompletionMonitorRunner(dependencies) { claim ->
                CompletionNotifications.postCompletion(applicationContext, claim)
            }.runOnce()
        }
        val app = applicationContext as? TorBoxDropApplication
        val drivePass = app?.driveAutomationRunner()?.runOnce()

        val torBoxAuthBlocked = notificationPass?.authBlocked == true ||
            drivePass?.torBoxAuthBlocked == true
        val hasWork = notificationPass?.hasArmedDownloads == true || drivePass?.hasWork == true
        if (torBoxAuthBlocked || !hasWork) {
            CompletionMonitorScheduler.cancelFallback(applicationContext)
        }
        if (torBoxAuthBlocked) return Result.success()

        val failures = (notificationPass?.retryableFailures ?: 0) +
            (drivePass?.retryableFailures ?: 0)
        return if (failures > 0 && hasWork) Result.retry() else Result.success()
    }
}
