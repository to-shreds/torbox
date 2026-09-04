package app.jabs.torboxdrop.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.jabs.torboxdrop.TorBoxDropApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Restores only the deferrable fallback. It never starts a foreground service from boot. */
class RestoreMonitoringReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                val pendingResult = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val app = context.applicationContext as? TorBoxDropApplication
                        val hasArmed = app?.container?.localStore?.monitoredSubscriptions()?.isNotEmpty() == true
                        if (hasArmed) CompletionMonitorScheduler.scheduleFallback(context)
                        else CompletionMonitorScheduler.cancelFallback(context)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
