package app.jabs.torboxdrop.notifications

import app.jabs.torboxdrop.drive.driveAccountScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Held for the whole account-sensitive operation, including credential replacement. */
internal object AccountSensitiveWorkGate {
    val mutex = Mutex()

    suspend fun awaitIdle() { mutex.withLock { Unit } }

    suspend fun <T> withAccount(expected: String, tokenProvider: () -> String?, block: suspend () -> T): T =
        mutex.withLock {
            check(expected == driveAccountScope(tokenProvider())) {
                "The TorBox account changed. Start Google Drive setup again."
            }
            block()
        }
}
