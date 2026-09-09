package app.jabs.torboxdrop

import android.app.Application
import app.jabs.torboxdrop.drive.DriveAutomationRunner
import app.jabs.torboxdrop.model.DownloadType
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.QueuedDownload
import app.jabs.torboxdrop.notifications.ArmedDownload
import app.jabs.torboxdrop.notifications.CompletionClaim
import app.jabs.torboxdrop.notifications.CompletionMonitorDependencies
import app.jabs.torboxdrop.notifications.CompletionMonitorServiceLocator
import app.jabs.torboxdrop.notifications.CompletionNotificationChannels
import app.jabs.torboxdrop.notifications.CompletionState
import app.jabs.torboxdrop.notifications.MonitoredDownloadType
import app.jabs.torboxdrop.notifications.QueuedSubscriptionMatcher
import java.time.Duration
import java.time.Instant

class TorBoxDropApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        CompletionNotificationChannels.ensureCreated(this)
        CompletionMonitorServiceLocator.install { MonitoringDependencies(container) }
    }

    fun driveAutomationRunner(): DriveAutomationRunner = DriveAutomationRunner(
        repository = container.repository,
        integrationClient = container.driveIntegration,
        store = container.driveStore,
        googleAuthorization = container.googleDriveAuthorization,
        tokenProvider = container.tokenStore::read,
        relayUserId = { container.preferences.relayUserId },
        onGoogleAuthorizationRequired = {
            // This is a non-secret capability hint only. The actual authorization is always
            // re-checked with Google Identity Services before a Drive submission.
            container.preferences.googleDriveConnected = false
        },
    )

    fun monitoringDependencies(
        freshDownloads: List<DownloadItem>,
        currentQueue: List<QueuedDownload>,
    ): CompletionMonitorDependencies = MonitoringDependencies(
        container = container,
        seededDownloads = freshDownloads,
        seededQueue = currentQueue,
    )

    private class MonitoringDependencies(
        private val container: AppContainer,
        seededDownloads: List<DownloadItem>? = null,
        seededQueue: List<QueuedDownload>? = null,
    ) : CompletionMonitorDependencies {
        private var snapshotAttempt: Result<MonitorSnapshot>? = seededDownloads?.let {
            Result.success(MonitorSnapshot(it, seededQueue.orEmpty()))
        }
        private var armedForPass: List<ArmedDownload> = emptyList()

        override suspend fun armedDownloads(): List<ArmedDownload> =
            container.localStore.monitoredSubscriptions().map { subscription ->
                ArmedDownload(
                    subscriptionKey = subscription.subscriptionKey,
                    downloadId = subscription.downloadId,
                    type = subscription.type.toMonitoredType(),
                    displayName = subscription.name,
                    armedAt = subscription.armedAt,
                    hasBeenSeen = subscription.hasBeenSeen,
                    consecutiveMisses = subscription.consecutiveMisses,
                    queueId = subscription.queueId,
                    sourceHash = subscription.sourceHash,
                    sourceValue = subscription.sourceValue,
                )
            }.also { armedForPass = it }

        override suspend fun checkCompletion(download: ArmedDownload): CompletionState {
            val type = download.type.toDownloadType()
            val snapshot = loadSnapshot()
            if (download.queueId != null) {
                val activated = QueuedSubscriptionMatcher.findActivated(download, snapshot.downloads)
                if (activated != null && container.localStore.rebindQueuedSubscription(
                        type,
                        download.queueId,
                        activated,
                    )
                ) {
                    // Re-read the rebound durable identity on the next pass before claiming it.
                    return CompletionState.NotReady
                }
                val stillQueued = snapshot.queue.any { it.type == type && it.id == download.queueId }
                if (stillQueued) return CompletionState.NotReady
                val misses = container.localStore.recordSubscriptionMiss(download.subscriptionKey)
                val withinAdmissionGrace = Duration.between(download.armedAt, Instant.now()) < ADMISSION_GRACE
                return if (withinAdmissionGrace || misses < CONFIRMED_MISSES_BEFORE_REMOVAL) {
                    CompletionState.NotReady
                } else {
                    CompletionState.Removed
                }
            }
            val item = snapshot.downloads.firstOrNull { it.type == type && it.id == download.downloadId }
            if (item == null) {
                val misses = container.localStore.recordSubscriptionMiss(download.subscriptionKey)
                val withinAdmissionGrace = Duration.between(download.armedAt, Instant.now()) < ADMISSION_GRACE
                return if (withinAdmissionGrace || misses < CONFIRMED_MISSES_BEFORE_REMOVAL
                ) {
                    CompletionState.NotReady
                } else {
                    CompletionState.Removed
                }
            }
            container.localStore.markSubscriptionSeen(download.subscriptionKey, item.name)
            return if (item.downloadFinished && item.downloadPresent) {
                CompletionState.Ready
            } else {
                CompletionState.NotReady
            }
        }

        private suspend fun loadSnapshot(): MonitorSnapshot {
            val attempt = snapshotAttempt ?: runCatching {
                container.repository.requestLiveTorrentUpdates(
                    userId = container.preferences.relayUserId,
                    torrentIds = armedForPass.asSequence()
                        .filter { it.type == MonitoredDownloadType.TORRENT && it.queueId == null }
                        .map(ArmedDownload::downloadId)
                        .toList(),
                )
                val refreshed = container.repository.refresh(bypassCache = true)
                MonitorSnapshot(refreshed.downloads, refreshed.queue)
            }.also { snapshotAttempt = it }
            return attempt.getOrThrow()
        }

        override suspend fun claimCompletion(download: ArmedDownload): CompletionClaim? {
            return if (container.localStore.claimReady(download.subscriptionKey)) {
                val current = container.localStore.monitoredSubscriptions()
                    .firstOrNull { it.subscriptionKey == download.subscriptionKey }
                val claimDownload = if (current == null || current.name == download.displayName) {
                    download
                } else {
                    download.copy(displayName = current.name)
                }
                CompletionClaim(claimId = download.subscriptionKey, download = claimDownload)
            } else {
                null
            }
        }

        override suspend fun markNotificationPosted(claim: CompletionClaim) {
            container.localStore.markNotificationPosted(claim.claimId)
        }

        override suspend fun disarm(download: ArmedDownload) {
            container.localStore.disarmSubscription(download.subscriptionKey)
        }

        private fun DownloadType.toMonitoredType(): MonitoredDownloadType = when (this) {
            DownloadType.TORRENT -> MonitoredDownloadType.TORRENT
            DownloadType.WEB -> MonitoredDownloadType.WEB
        }

        private fun MonitoredDownloadType.toDownloadType(): DownloadType = when (this) {
            MonitoredDownloadType.TORRENT -> DownloadType.TORRENT
            MonitoredDownloadType.WEB -> DownloadType.WEB
        }

        private data class MonitorSnapshot(
            val downloads: List<DownloadItem>,
            val queue: List<QueuedDownload>,
        )

        private companion object {
            const val CONFIRMED_MISSES_BEFORE_REMOVAL = 3
            val ADMISSION_GRACE: Duration = Duration.ofHours(6)
        }
    }
}
