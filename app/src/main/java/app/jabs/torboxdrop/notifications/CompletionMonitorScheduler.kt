package app.jabs.torboxdrop.notifications

import android.content.Context
import android.content.Intent
import app.jabs.torboxdrop.TorBoxDropApplication
import app.jabs.torboxdrop.drive.driveAccountScope
import kotlinx.coroutines.sync.withLock
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
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<CompletionMonitorWorker>().setConstraints(constraints).build(),
        )
    }

    fun cancelFallback(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
    fun cancelAll(context: Context) {
        cancelFallback(context)
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_IMMEDIATE_WORK_NAME)
    }

    /** An old failed pass must not cancel the replacement account's newly armed work. */
    suspend fun cancelRejectedAccount(context: Context, account: String?): Boolean =
        AccountSensitiveWorkGate.mutex.withLock {
            val app = context.applicationContext as? TorBoxDropApplication ?: return@withLock false
            if (!rejectionAppliesToCurrentAccount(account, driveAccountScope(app.container.tokenStore.read()))) {
                return@withLock false
            }
            cancelAll(app)
            true
        }

    internal fun rejectionAppliesToCurrentAccount(rejected: String?, current: String?): Boolean =
        rejected != null && rejected == current

    suspend fun hasDriveWork(context: Context): Boolean {
        val app = context.applicationContext as? TorBoxDropApplication ?: return false
        val account = driveAccountScope(app.container.tokenStore.read()) ?: return false
        return app.container.driveStore.hasRunnableWork(account)
    }

    /** Check and stop under the same gate as arming; turning off a bell cannot stop a Drive watch. */
    suspend fun cancelIfIdle(context: Context): Boolean = AccountSensitiveWorkGate.mutex.withLock {
        val app = context.applicationContext as? TorBoxDropApplication ?: return@withLock false
        if (app.container.localStore.monitoredSubscriptions().isNotEmpty() || hasDriveWork(app)) return@withLock false
        cancelFallback(app)
        app.stopService(Intent(app, CompletionMonitorService::class.java))
        true
    }

}
