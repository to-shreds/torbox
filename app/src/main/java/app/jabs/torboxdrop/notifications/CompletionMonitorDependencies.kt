package app.jabs.torboxdrop.notifications

import android.content.Context
import java.time.Instant

enum class MonitoredDownloadType(val routeValue: String) {
    TORRENT("torrent"),
    WEB("web"),
}

data class ArmedDownload(
    /** Stable local subscription key, for example `torrent:1234`. */
    val subscriptionKey: String,
    val downloadId: String,
    val type: MonitoredDownloadType,
    val displayName: String,
    val armedAt: Instant = Instant.EPOCH,
    val hasBeenSeen: Boolean = true,
    val consecutiveMisses: Int = 0,
    val queueId: String? = null,
    val sourceHash: String? = null,
    val sourceValue: String? = null,
)

sealed interface CompletionState {
    /** The authoritative TorBox readiness fields say that the content is present and ready. */
    data object Ready : CompletionState

    data object NotReady : CompletionState

    /** TorBox authoritatively says the item no longer exists. */
    data object Removed : CompletionState
}

data class CompletionClaim(
    /** Durable unique ID for this one completion delivery. */
    val claimId: String,
    val download: ArmedDownload,
)

/**
 * Repository boundary used by both the foreground service and WorkManager.
 *
 * `claimCompletion` must atomically acquire a durable delivery claim. Concurrent callers must not
 * both receive a claim for the same completion. An implementation may make an abandoned claim
 * eligible again after a lease expires. `markNotificationPosted` permanently completes the claim.
 * This protocol, combined with a stable Android notification tag/id, prevents duplicate visible
 * completion notifications when the service and worker overlap.
 */
interface CompletionMonitorDependencies {
    suspend fun armedDownloads(): List<ArmedDownload>

    suspend fun checkCompletion(download: ArmedDownload): CompletionState

    suspend fun claimCompletion(download: ArmedDownload): CompletionClaim?

    suspend fun markNotificationPosted(claim: CompletionClaim)

    suspend fun disarm(download: ArmedDownload)
}

fun interface CompletionMonitorDependenciesFactory {
    fun create(context: Context): CompletionMonitorDependencies
}

/** Installed by the application layer during process startup. */
object CompletionMonitorServiceLocator {
    @Volatile
    private var factory: CompletionMonitorDependenciesFactory? = null

    fun install(factory: CompletionMonitorDependenciesFactory) {
        this.factory = factory
    }

    fun clear() {
        factory = null
    }

    fun get(context: Context): CompletionMonitorDependencies? =
        factory?.create(context.applicationContext)
}
