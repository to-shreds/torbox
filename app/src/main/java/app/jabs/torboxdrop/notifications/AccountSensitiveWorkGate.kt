package app.jabs.torboxdrop.notifications

import app.jabs.torboxdrop.drive.DriveAutomationRunner
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Notification gate plus the existing account-swap barrier for Drive automation. */
internal object AccountSensitiveWorkGate {
    val mutex = Mutex()

    suspend fun awaitIdle() {
        mutex.withLock { Unit }
        DriveAutomationRunner.awaitIdle()
    }
}
