package app.jabs.torboxdrop.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object CompletionMonitorScheduler {
    const val UNIQUE_WORK_NAME = "torbox-completion-monitor-fallback"
    private const val UNIQUE_IMMEDIATE_WORK_NAME = "torbox-completion-monitor-immediate"

    fun scheduleFallback(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<CompletionMonitorWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** A best-effort prompt pass for newly armed work, including instantly-ready cached torrents. */
    fun runSoon(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<CompletionMonitorWorker>().setConstraints(constraints).build(),
        )
    }

    fun cancelFallback(context: Context) {
        // Notification-only callers must not cancel the durable fallback while Drive work remains.
        if (AdditionalMonitoredWork.hasWork()) return
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
