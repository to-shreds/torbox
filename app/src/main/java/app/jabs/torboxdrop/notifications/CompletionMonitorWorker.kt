package app.jabs.torboxdrop.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class CompletionMonitorWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val dependencies = CompletionMonitorServiceLocator.get(applicationContext)
            ?: return Result.retry()
        CompletionNotificationChannels.ensureCreated(applicationContext)
        val pass = CompletionMonitorRunner(dependencies) { claim ->
            CompletionNotifications.postCompletion(applicationContext, claim)
        }.runOnce()

        if (pass.authBlocked || !pass.hasArmedDownloads) {
            CompletionMonitorScheduler.cancelFallback(applicationContext)
        }
        if (pass.authBlocked) return Result.success()
        return if (pass.retryableFailures > 0) Result.retry() else Result.success()
    }
}
