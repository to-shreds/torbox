package app.jabs.torboxdrop.notifications

import app.jabs.torboxdrop.data.TorBoxBadTokenException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

fun interface CompletionNotificationPublisher {
    /** Returns true only when Android accepted the completion notification for posting. */
    fun post(claim: CompletionClaim): Boolean
}

data class MonitorPassResult(
    val armedAtStart: Int,
    val delivered: Int,
    val removed: Int,
    val retryableFailures: Int,
    val hasArmedDownloads: Boolean,
    /** The configured credential was authoritatively rejected; do not keep retrying in background. */
    val authBlocked: Boolean = false,
)

class CompletionMonitorRunner(
    private val dependencies: CompletionMonitorDependencies,
    private val publisher: CompletionNotificationPublisher,
) {
    suspend fun runOnce(): MonitorPassResult = AccountSensitiveWorkGate.mutex.withLock {
        val armed = try {
            dependencies.armedDownloads()
        } catch (error: CancellationException) {
            throw error
        } catch (_: TorBoxBadTokenException) {
            return@withLock MonitorPassResult(
                armedAtStart = 0,
                delivered = 0,
                removed = 0,
                retryableFailures = 0,
                hasArmedDownloads = true,
                authBlocked = true,
            )
        } catch (_: Exception) {
            return@withLock MonitorPassResult(
                armedAtStart = 0,
                delivered = 0,
                removed = 0,
                retryableFailures = 1,
                hasArmedDownloads = true,
            )
        }

        var delivered = 0
        var removed = 0
        var failures = 0
        var authBlocked = false
        for (download in armed.distinctBy(ArmedDownload::subscriptionKey)) {
            try {
                when (dependencies.checkCompletion(download)) {
                    CompletionState.NotReady -> Unit
                    CompletionState.Removed -> {
                        dependencies.disarm(download)
                        removed++
                    }
                    CompletionState.Ready -> {
                        val claim = dependencies.claimCompletion(download) ?: continue
                        if (!publisher.post(claim)) {
                            failures++
                            continue
                        }
                        dependencies.markNotificationPosted(claim)
                        dependencies.disarm(download)
                        delivered++
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: TorBoxBadTokenException) {
                authBlocked = true
                break
            } catch (_: Exception) {
                // The repository owns sanitized diagnostics. Never risk logging API-bearing errors.
                failures++
            }
        }

        val stillArmed = if (authBlocked) {
            true
        } else {
            try {
                dependencies.armedDownloads().isNotEmpty() || AdditionalMonitoredWork.hasWork()
            } catch (error: CancellationException) {
                throw error
            } catch (_: TorBoxBadTokenException) {
                authBlocked = true
                true
            } catch (_: Exception) {
                failures++
                true
            }
        }
        MonitorPassResult(
            armedAtStart = armed.size,
            delivered = delivered,
            removed = removed,
            retryableFailures = failures,
            hasArmedDownloads = stillArmed,
            authBlocked = authBlocked,
        )
    }

    companion object {
        /** Waits for notification and Drive work to leave their shared account-sensitive gate. */
        suspend fun awaitIdle() {
            AccountSensitiveWorkGate.awaitIdle()
        }
    }
}
