package app.jabs.torboxdrop.notifications

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One process-wide gate for work that uses the current TorBox credential.
 *
 * MainViewModel already calls CompletionMonitorRunner.awaitIdle() before replacing or clearing the
 * credential. Sharing this gate with Drive automation makes that existing account-swap barrier
 * cover both notification and Drive passes without duplicating credential lifecycle logic.
 */
internal object AccountSensitiveWorkGate {
    val mutex = Mutex()

    suspend fun awaitIdle() {
        mutex.withLock { Unit }
    }
}
