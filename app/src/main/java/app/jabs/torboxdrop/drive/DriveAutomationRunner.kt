package app.jabs.torboxdrop.drive

import app.jabs.torboxdrop.data.RepositorySnapshot
import app.jabs.torboxdrop.data.TorBoxApiException
import app.jabs.torboxdrop.data.TorBoxBadTokenException
import app.jabs.torboxdrop.data.TorBoxDriveIntegrationClient
import app.jabs.torboxdrop.data.TorBoxOfflineException
import app.jabs.torboxdrop.data.TorBoxRateLimitException
import app.jabs.torboxdrop.data.TorBoxRepository
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DriveAutomationRunner(
    private val repository: TorBoxRepository,
    private val integrationClient: TorBoxDriveIntegrationClient,
    private val store: DriveStore,
    private val googleAuthorization: GoogleDriveAuthorizationManager,
    private val tokenProvider: () -> String?,
    private val relayUserId: () -> String?,
    private val folderId: () -> String?,
    private val onGoogleAuthorizationRequired: () -> Unit = {},
    private val now: () -> Instant = Instant::now,
) {
    suspend fun runOnce(): DriveAutomationPassResult = processMutex.withLock {
        val accountScope = driveAccountScope(tokenProvider())
            ?: return@withLock DriveAutomationPassResult(watchedAtStart = 0)
        val watches = store.runnableWatches(accountScope)
        if (watches.isEmpty()) {
            return@withLock DriveAutomationPassResult(
                watchedAtStart = 0,
                authRequired = store.authRequiredWatches(accountScope).isNotEmpty(),
                hasWork = false,
            )
        }
        var queuedFiles = 0
        var completedFiles = 0
        var failedFiles = 0
        var retryableFailures = 0
        var authorizationRequired = false
        var torBoxAuthBlocked = false
        val snapshot = try {
            loadSnapshot(watches)
        } catch (error: CancellationException) {
            throw error
        } catch (_: TorBoxBadTokenException) {
            return@withLock DriveAutomationPassResult(watches.size, torBoxAuthBlocked = true, hasWork = true)
        } catch (_: Exception) {
            return@withLock DriveAutomationPassResult(watches.size, retryableFailures = 1, hasWork = true)
        }
        for (watch in watches.distinctBy(DriveWatch::watchKey)) {
            if (torBoxAuthBlocked) break
            try {
                val resolved = resolveReadyItem(watch, snapshot) ?: continue
                val outcome = processReady(watch, resolved)
                queuedFiles += outcome.queuedFiles
                completedFiles += outcome.completedFiles
                failedFiles += outcome.failedFiles
                retryableFailures += outcome.retryableFailures
                if (outcome.authRequired) {
                    authorizationRequired = true
                    onGoogleAuthorizationRequired()
                }
                if (outcome.torBoxAuthBlocked) torBoxAuthBlocked = true
            } catch (error: CancellationException) {
                throw error
            } catch (_: TorBoxBadTokenException) {
                torBoxAuthBlocked = true
            } catch (_: Exception) {
                retryableFailures++
            }
        }
        val hasWork = if (torBoxAuthBlocked) true else runCatching {
            store.hasRunnableWork(accountScope)
        }.getOrDefault(true)
        if (!authorizationRequired) {
            authorizationRequired = runCatching { store.authRequiredWatches(accountScope).isNotEmpty() }.getOrDefault(false)
        }
        DriveAutomationPassResult(
            watchedAtStart = watches.size,
            queuedFiles = queuedFiles,
            completedFiles = completedFiles,
            failedFiles = failedFiles,
            retryableFailures = retryableFailures,
            authRequired = authorizationRequired,
            torBoxAuthBlocked = torBoxAuthBlocked,
            hasWork = hasWork,
        )
    }

    private suspend fun loadSnapshot(watches: List<DriveWatch>): RepositorySnapshot {
        val cached = repository.loadCached()
        if (cached.lastUpdated != null && Duration.between(cached.lastUpdated, now()).abs() <= RECENT_SNAPSHOT_WINDOW) {
            return cached
        }
        repository.requestLiveTorrentUpdates(
            relayUserId(),
            watches.asSequence()
                .filter { it.type == DownloadType.TORRENT && it.queueId == null }
                .map(DriveWatch::downloadId)
                .toList(),
        )
        return repository.refresh(bypassCache = true)
    }

    private suspend fun resolveReadyItem(watch: DriveWatch, snapshot: RepositorySnapshot): DownloadItem? {
        if (watch.queueId != null) {
            val activated = DriveQueuedMatcher.findActivated(watch, snapshot.downloads)
            if (activated != null && store.rebindQueued(watch, activated)) return null
            if (snapshot.queue.any { it.type == watch.type && it.id == watch.queueId }) return null
            val misses = store.recordMiss(watch.watchKey)
            val grace = Duration.between(watch.armedAt, now()) < ADMISSION_GRACE
            if (!grace && misses >= CONFIRMED_MISSES_BEFORE_REMOVAL) {
                store.markWatchFailed(watch.watchKey, "That queued torrent disappeared before it became active.")
            }
            return null
        }
        val item = snapshot.downloads.firstOrNull { it.type == watch.type && it.id == watch.downloadId }
        if (item == null) {
            val misses = store.recordMiss(watch.watchKey)
            val grace = Duration.between(watch.armedAt, now()) < ADMISSION_GRACE
            if (!grace && misses >= CONFIRMED_MISSES_BEFORE_REMOVAL) {
                store.markWatchFailed(watch.watchKey, "That torrent is no longer available in TorBox.")
            }
            return null
        }
        store.markSeen(watch.watchKey, item)
        return item.takeIf { it.downloadFinished && it.downloadPresent }
    }

    private suspend fun processReady(watch: DriveWatch, item: DownloadItem): FilePassOutcome {
        if (item.type != DownloadType.TORRENT) {
            store.markWatchFailed(watch.watchKey, "Google Drive automation currently supports torrents only.")
            return FilePassOutcome(failedFiles = 1)
        }
        when (ensureTorBoxDestination(watch)) {
            DestinationResult.READY -> Unit
            DestinationResult.AUTH_REQUIRED -> return FilePassOutcome(authRequired = true)
            DestinationResult.RETRY -> return FilePassOutcome(retryableFailures = 1)
            DestinationResult.TORBOX_AUTH_BLOCKED -> return FilePassOutcome(torBoxAuthBlocked = true)
        }
        val files = try {
            repository.getFiles(item.type, item.id, forceRefresh = true)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return FilePassOutcome(retryableFailures = 1)
        }
        if (files.isEmpty()) return FilePassOutcome(retryableFailures = 1)
        store.markWatchTransferring(watch.watchKey, item.hash, item.name)
        store.ensureFileTransfers(watch.watchKey, files)
        var transfers = store.fileTransfers(watch.watchKey)
        val hash = item.hash?.trim()?.takeIf(String::isNotEmpty)
        var jobs = if (hash == null) emptyList() else try {
            integrationClient.getJobsByHash(hash)
        } catch (error: CancellationException) {
            throw error
        } catch (_: TorBoxBadTokenException) {
            return FilePassOutcome(torBoxAuthBlocked = true)
        } catch (_: Exception) {
            emptyList()
        }
        syncKnownJobs(transfers, jobs)
        transfers = store.fileTransfers(watch.watchKey)
        val staleBefore = now().minus(AMBIGUOUS_SUBMISSION_GRACE)
        transfers.filter {
            it.state in setOf(DriveFileState.SUBMITTING, DriveFileState.SUBMITTED) && it.jobId == null
        }.forEach { transfer ->
            store.retryAmbiguousIfStale(transfer.transferKey, staleBefore, MAX_SUBMISSION_ATTEMPTS)
        }
        transfers = store.fileTransfers(watch.watchKey)
        val pending = transfers.filter { it.state == DriveFileState.PENDING }
        var queuedFiles = 0
        var retryableFailures = 0
        if (pending.isNotEmpty()) {
            when (val authorization = googleAuthorization.authorize()) {
                is GoogleDriveAuthorizationResult.NeedsResolution -> {
                    store.markAuthorizationRequired(
                        watch.watchKey,
                        "Google Drive authorization needs your attention in Settings.",
                    )
                    return FilePassOutcome(authRequired = true)
                }
                is GoogleDriveAuthorizationResult.Failed -> return FilePassOutcome(retryableFailures = 1)
                is GoogleDriveAuthorizationResult.Authorized -> {
                    val accessToken = authorization.accessToken
                    for (transfer in pending) {
                        if (!store.claimFileForSubmission(transfer.transferKey, now())) continue
                        try {
                            integrationClient.queueGoogleDrive(item.id, transfer.fileId, accessToken)
                            store.markFileSubmitted(transfer.transferKey)
                            queuedFiles++
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: TorBoxBadTokenException) {
                            store.markFileSubmitted(transfer.transferKey)
                            return FilePassOutcome(queuedFiles = queuedFiles, torBoxAuthBlocked = true)
                        } catch (error: TorBoxApiException) {
                            if (error.statusCode in 400..499) {
                                store.markFileFailed(
                                    transfer.transferKey,
                                    error.userDetail.ifBlank { "TorBox rejected the Drive transfer." },
                                )
                            } else {
                                store.markFileSubmitted(transfer.transferKey)
                                retryableFailures++
                            }
                        } catch (_: TorBoxRateLimitException) {
                            store.markFileSubmitted(transfer.transferKey)
                            retryableFailures++
                        } catch (_: TorBoxOfflineException) {
                            store.markFileSubmitted(transfer.transferKey)
                            retryableFailures++
                        } catch (_: Exception) {
                            store.markFileSubmitted(transfer.transferKey)
                            retryableFailures++
                        }
                    }
                }
            }
        }
        if (hash != null) {
            jobs = try {
                integrationClient.getJobsByHash(hash)
            } catch (error: CancellationException) {
                throw error
            } catch (_: TorBoxBadTokenException) {
                return FilePassOutcome(queuedFiles = queuedFiles, torBoxAuthBlocked = true)
            } catch (_: Exception) {
                jobs
            }
            syncKnownJobs(store.fileTransfers(watch.watchKey), jobs)
        }
        val finalTransfers = store.fileTransfers(watch.watchKey)
        store.finishWatchIfTerminal(watch.watchKey)
        return FilePassOutcome(
            queuedFiles = queuedFiles,
            completedFiles = finalTransfers.count { it.state == DriveFileState.COMPLETE },
            failedFiles = finalTransfers.count { it.state == DriveFileState.FAILED },
            retryableFailures = retryableFailures,
        )
    }

    private suspend fun ensureTorBoxDestination(watch: DriveWatch): DestinationResult {
        val destination = folderId()?.trim()?.takeIf(String::isNotEmpty)
        if (destination == null) {
            store.markAuthorizationRequired(
                watch.watchKey,
                "Choose a Google Drive destination in Settings before this transfer can continue.",
            )
            return DestinationResult.AUTH_REQUIRED
        }
        return try {
            integrationClient.updateGoogleDriveFolderId(destination)
            DestinationResult.READY
        } catch (error: CancellationException) {
            throw error
        } catch (_: TorBoxBadTokenException) {
            DestinationResult.TORBOX_AUTH_BLOCKED
        } catch (error: TorBoxApiException) {
            if (error.statusCode in 400..499) {
                store.markAuthorizationRequired(
                    watch.watchKey,
                    "TorBox rejected the saved Google Drive destination. Reconnect Drive in Settings.",
                )
                DestinationResult.AUTH_REQUIRED
            } else DestinationResult.RETRY
        } catch (_: TorBoxRateLimitException) {
            DestinationResult.RETRY
        } catch (_: TorBoxOfflineException) {
            DestinationResult.RETRY
        } catch (_: Exception) {
            DestinationResult.RETRY
        }
    }

    /**
     * A job without a persisted job ID is only eligible to bind to a transfer that we actually
     * attempted, and only if the job was created near that attempt. This prevents an older manual
     * Google Drive upload of the same torrent file from being mistaken for this automation run.
     */
    private suspend fun syncKnownJobs(
        transfers: List<DriveFileTransfer>,
        jobs: List<TorBoxIntegrationJob>,
    ) {
        transfers.forEach { transfer ->
            val candidate = jobs.asSequence()
                .filter { it.fileId == transfer.fileId && !it.zip }
                .filter { it.type.isNullOrBlank() || it.type.equals("torrent", ignoreCase = true) }
                .filter { it.integration?.contains("google", ignoreCase = true) == true }
                .filter { job ->
                    when {
                        transfer.jobId != null -> job.id == transfer.jobId
                        transfer.state !in setOf(DriveFileState.SUBMITTING, DriveFileState.SUBMITTED) -> false
                        transfer.lastAttemptAt == null || job.createdAt == null -> false
                        else -> !job.createdAt.isBefore(transfer.lastAttemptAt.minus(JOB_CLOCK_TOLERANCE))
                    }
                }
                .sortedWith(compareBy<TorBoxIntegrationJob> { it.createdAt }.thenBy { it.id })
                .lastOrNull() ?: return@forEach
            when (candidate.status?.trim()?.lowercase(Locale.ROOT)) {
                "completed" -> store.markFileComplete(transfer.transferKey, candidate.id)
                "failed" -> store.markFileFailed(
                    transfer.transferKey,
                    candidate.detail?.takeIf(String::isNotBlank)
                        ?: "TorBox reported that the Drive transfer failed.",
                    candidate.id,
                )
                "pending", "uploading" -> store.markFileSubmitted(transfer.transferKey, candidate.id)
            }
        }
    }

    private enum class DestinationResult { READY, AUTH_REQUIRED, RETRY, TORBOX_AUTH_BLOCKED }
    private data class FilePassOutcome(
        val queuedFiles: Int = 0,
        val completedFiles: Int = 0,
        val failedFiles: Int = 0,
        val retryableFailures: Int = 0,
        val authRequired: Boolean = false,
        val torBoxAuthBlocked: Boolean = false,
    )

    companion object {
        private val processMutex = Mutex()
        private val RECENT_SNAPSHOT_WINDOW = Duration.ofSeconds(8)
        private val ADMISSION_GRACE = Duration.ofHours(6)
        private val AMBIGUOUS_SUBMISSION_GRACE = Duration.ofMinutes(10)
        private val JOB_CLOCK_TOLERANCE = Duration.ofMinutes(2)
        private const val CONFIRMED_MISSES_BEFORE_REMOVAL = 3
        private const val MAX_SUBMISSION_ATTEMPTS = 2
        suspend fun awaitIdle() { processMutex.withLock { Unit } }
    }
}
