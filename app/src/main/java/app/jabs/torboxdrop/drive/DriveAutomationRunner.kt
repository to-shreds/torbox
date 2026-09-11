package app.jabs.torboxdrop.drive

import app.jabs.torboxdrop.data.RepositorySnapshot
import app.jabs.torboxdrop.data.TorBoxApiException
import app.jabs.torboxdrop.data.TorBoxBadTokenException
import app.jabs.torboxdrop.data.TorBoxDriveGateway
import app.jabs.torboxdrop.data.TorBoxRateLimitException
import app.jabs.torboxdrop.model.DownloadFile
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import app.jabs.torboxdrop.notifications.AccountSensitiveWorkGate
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

/** Small live-data seam; production uses the existing native API, tests use controlled responses. */
interface DriveAutomationSource {
    suspend fun queuedSnapshot(): RepositorySnapshot
    suspend fun download(id: String): DownloadItem?
    suspend fun files(id: String): List<DownloadFile>
}

/** Durable per-file journal around TorBox's server-side cloud transfer, never an Android reupload. */
class DriveAutomationRunner(
    private val source: DriveAutomationSource,
    private val integrationClient: TorBoxDriveGateway,
    private val store: DriveStore,
    private val googleAuthorization: GoogleDriveAuthorizer,
    private val googleDriveApi: GoogleDriveApiClient,
    private val tokenProvider: () -> String?,
    private val folderId: () -> String?,
    private val connected: () -> Boolean,
    private val onGoogleAuthorizationRequired: () -> Unit = {},
    private val now: () -> Instant = Instant::now,
) {
    suspend fun runOnce(includeReview: Boolean = false): DriveAutomationPassResult =
        AccountSensitiveWorkGate.mutex.withLock {
            val scope = driveAccountScope(tokenProvider())
                ?: return@withLock DriveAutomationPassResult(watchedAtStart = 0)
            val destinationGate = DriveDestinationGate(store, integrationClient)
            val watches = store.runnableWatches(scope, includeReview || store.destinationLease(scope) != null)
            var submitted = 0
            var retries = 0
            var blocked = false
            for (original in watches) {
                try {
                    // Refresh the row after acquiring the gate. A stop/delete action may have changed it.
                    val watch = store.getWatch(original.watchKey, scope) ?: continue
                    submitted += process(watch)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: TorBoxBadTokenException) {
                    blocked = true
                    break
                } catch (_: Exception) {
                    retries++
                    store.note(original.watchKey, "Could not check Drive transfer status. A later check will retry safely.")
                }
            }
            if (!blocked) try {
                destinationGate.releaseIfIdle(scope)
            } catch (error: CancellationException) { throw error
            } catch (_: TorBoxBadTokenException) { blocked = true
            } catch (_: Exception) { retries++ }
            DriveAutomationPassResult(
                watchedAtStart = watches.size,
                accountScope = scope,
                queuedFiles = submitted,
                retryableFailures = retries,
                authRequired = store.authRequiredWatches(scope).isNotEmpty(),
                torBoxAuthBlocked = blocked,
                hasWork = blocked || store.hasRunnableWork(scope),
            )
        }

    private suspend fun process(watch: DriveWatch): Int {
        if (watch.type != DownloadType.TORRENT) {
            store.markWatchFailed(watch.watchKey, "Drive automation supports torrents only.")
            return 0
        }
        var transfers = store.fileTransfers(watch.watchKey)
        if (transfers.any { it.state in IN_FLIGHT }) {
            reconcile(watch, transfers)
            transfers = store.fileTransfers(watch.watchKey)
        }
        // Once files were submitted, their jobs remain monitorable even after the torrent disappears
        // or Google consent expires. Never require a new folder operation merely to read job status.
        if (transfers.isNotEmpty() && transfers.none { it.state == DriveFileState.PENDING }) {
            store.finishWatchIfTerminal(watch.watchKey)
            return 0
        }
        val (bound, item) = resolve(watch) ?: return 0
        val hash = item.hash?.trim()
        if (hash == null || !HASH.matches(hash)) {
            store.note(bound.watchKey, "Waiting for TorBox to return a valid torrent hash before sending files.")
            return 0
        }
        val files = source.files(item.id)
        check(files.all { it.downloadId == item.id && it.downloadType == DownloadType.TORRENT && it.id >= 0 })
        check(files.map { it.id }.distinct().size == files.size)
        if (files.isEmpty() || (item.fileCount != null && files.size != item.fileCount)) {
            store.note(bound.watchKey, "Waiting for TorBox's complete file list.")
            return 0
        }
        store.markWatchTransferring(bound.watchKey, hash, item.name)
        store.ensureFileTransfers(bound.watchKey, files)
        transfers = store.fileTransfers(bound.watchKey)
        val liveById = files.associateBy { it.id }
        for (transfer in transfers.filter { it.state == DriveFileState.PENDING }) {
            val file = liveById[transfer.fileId]
            when {
                file == null -> store.markFileFailed(transfer.transferKey, "This file is no longer in the torrent.")
                file.infected -> store.markFileFailed(transfer.transferKey, "TorBox marked this file as infected.")
                file.size != null && file.size > MAX_GOOGLE_FILE_BYTES ->
                    store.markFileFailed(transfer.transferKey, "This file exceeds TorBox's documented 100 GB Drive limit.")
            }
        }
        val pending = store.fileTransfers(bound.watchKey).filter {
            it.state == DriveFileState.PENDING && (it.retryAt == null || !it.retryAt.isAfter(now()))
        }
        if (pending.isEmpty()) {
            store.finishWatchIfTerminal(bound.watchKey)
            return 0
        }
        val destination = (bound.destinationFolderId ?: folderId())?.trim()?.takeIf(String::isNotEmpty)
        if (!connected() || destination == null) {
            requireAuthorization(bound.watchKey, "Reconnect Google Drive in Settings to continue these unsent files.")
            return 0
        }
        val accessToken = when (val auth = googleAuthorization.authorize()) {
            is GoogleDriveAuthorizationResult.Authorized -> auth.accessToken
            else -> {
                requireAuthorization(bound.watchKey, "Google Drive needs authorization in Settings. Unsent files are preserved.")
                return 0
            }
        }
        try {
            googleDriveApi.requireWritableFolder(destination, accessToken)
        } catch (error: GoogleDriveApiException) {
            if (error.statusCode in listOf(401, 403, 404)) {
                requireAuthorization(bound.watchKey, "This Google account cannot access the saved Drive folder. Reconnect in Settings.")
                return 0
            }
            throw error
        }
        // The baseline is saved in the same transaction as each claim, before any external POST.
        // Failed or malformed lookups do NOT count as an empty baseline and cannot authorize a send.
        val before = integrationClient.getJobsByHash(hash)
        val priorIds = before.mapNotNull { it.id }.toSet()
        if (!DriveDestinationGate(store, integrationClient).acquire(bound.accountScope, bound.watchKey, destination)) {
            store.note(bound.watchKey, "Waiting for other Drive transfers to finish before selecting this folder. Do not change the Drive folder or start uploads in another TorBox client meanwhile.")
            return 0
        }
        store.snapshotDestination(bound.watchKey, destination)
        var submitted = 0
        for (transfer in pending) {
            if (!store.claimFileForSubmission(transfer.transferKey, now(), priorIds)) continue
            try {
                integrationClient.queueGoogleDrive(item.id, transfer.fileId, accessToken)
                store.markFileSubmitted(transfer.transferKey)
                submitted++
            } catch (error: CancellationException) {
                // Keep SUBMITTING. It is ambiguous whether TorBox received the request.
                throw error
            } catch (error: TorBoxBadTokenException) {
                store.releaseRejected(transfer.transferKey, now())
                throw error
            } catch (error: TorBoxRateLimitException) {
                store.releaseRejected(transfer.transferKey,
                    now().plusSeconds((error.retryAfterSeconds ?: 30).coerceIn(1, 86_400)))
                break
            } catch (error: TorBoxApiException) {
                if (error.statusCode == 401) {
                    store.releaseRejected(transfer.transferKey, now())
                    requireAuthorization(bound.watchKey, "TorBox rejected Drive authorization. Reconnect Google Drive in Settings.")
                    break
                } else if (error.statusCode in 400..499) {
                    store.markFileFailed(transfer.transferKey, "TorBox rejected this Drive file transfer (HTTP ${error.statusCode}).")
                } else {
                    store.markFileSubmitted(transfer.transferKey)
                }
            } catch (_: Exception) {
                // Server errors and lost responses remain submitted, not eligible for replay.
                store.markFileSubmitted(transfer.transferKey)
            }
        }
        reconcile(bound.copy(sourceHash = hash), store.fileTransfers(bound.watchKey))
        store.finishWatchIfTerminal(bound.watchKey)
        return submitted
    }

    private suspend fun resolve(watch: DriveWatch): Pair<DriveWatch, DownloadItem>? {
        var bound = watch
        if (watch.queueId != null) {
            val snapshot = source.queuedSnapshot()
            val activated = DriveQueuedMatcher.findActivated(watch, snapshot.downloads)
            if (activated != null && store.rebindQueued(watch, activated)) {
                bound = store.getWatch(DriveStore.key(watch.accountScope, activated.type, activated.id), watch.accountScope)
                    ?: return null
                // A duplicate Add can collide with an already-completed watch. Do not reopen it.
                if (bound.state !in setOf(DriveWatchState.WAITING, DriveWatchState.TRANSFERRING)) return null
            } else {
                if (snapshot.queue.none { it.type == watch.type && it.id == watch.queueId }) recordMissing(watch)
                return null
            }
        }
        val item = source.download(bound.downloadId)
        if (item == null) {
            recordMissing(bound)
            return null
        }
        if (item.type != DownloadType.TORRENT || item.id != bound.downloadId ||
            (!bound.sourceHash.isNullOrBlank() && !item.hash.isNullOrBlank() &&
                !bound.sourceHash.equals(item.hash, ignoreCase = true))
        ) {
            store.markWatchFailed(bound.watchKey, "The torrent identity changed. Nothing was sent to Drive.")
            return null
        }
        store.markSeen(bound.watchKey, item)
        return if (item.downloadFinished && item.downloadPresent) bound to item else null
    }

    private suspend fun recordMissing(watch: DriveWatch) {
        val misses = store.recordMiss(watch.watchKey)
        if (Duration.between(watch.armedAt, now()) >= Duration.ofHours(6) && misses >= 3) {
            store.markWatchFailed(watch.watchKey, "TorBox no longer lists this torrent. No new files will be sent.")
        }
    }

    private suspend fun reconcile(watch: DriveWatch, transfers: List<DriveFileTransfer>) {
        val inflight = transfers.filter { it.state in IN_FLIGHT }
        if (inflight.isEmpty()) return
        var lookupFailed = false
        val byHash = if (watch.sourceHash?.let(HASH::matches) == true) {
            try {
                integrationClient.getJobsByHash(watch.sourceHash)
            } catch (error: CancellationException) { throw error
            } catch (error: TorBoxBadTokenException) { throw error
            } catch (_: Exception) { lookupFailed = true; emptyList() }
        } else emptyList()
        for (transfer in inflight) {
            val jobs = if (transfer.jobId != null) {
                try { listOfNotNull(integrationClient.getJob(transfer.jobId))
                } catch (error: CancellationException) { throw error
                } catch (error: TorBoxBadTokenException) { throw error
                } catch (_: Exception) { lookupFailed = true; emptyList() }
            } else byHash
            val candidate = DriveJobMatcher.find(transfer, watch.sourceHash, jobs)
            when (candidate?.status?.trim()?.lowercase(Locale.ROOT)) {
                "completed" -> store.markFileComplete(transfer.transferKey, candidate.id)
                "failed", "cancelled", "canceled" -> store.markFileFailed(transfer.transferKey, "TorBox reported that the Drive upload failed or was cancelled.", candidate.id)
                "pending", "uploading" -> store.markFileSubmitted(transfer.transferKey, candidate.id)
            }
            store.markAmbiguousIfStale(transfer.transferKey, now().minus(Duration.ofMinutes(10)))
        }
        if (lookupFailed) store.note(watch.watchKey, "The job lookup failed. No uncertain transfer will be resent.")
        store.finishWatchIfTerminal(watch.watchKey)
    }

    private suspend fun requireAuthorization(key: String, message: String) {
        store.markAuthorizationRequired(key, message)
        onGoogleAuthorizationRequired()
    }

    companion object {
        private val HASH = Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")
        private const val MAX_GOOGLE_FILE_BYTES = 100_000_000_000L
        private val IN_FLIGHT = setOf(DriveFileState.SUBMITTING, DriveFileState.SUBMITTED, DriveFileState.UNCERTAIN)
        suspend fun awaitIdle() = AccountSensitiveWorkGate.awaitIdle()
    }
}

/** Never adopt an old, wrong-file, wrong-service, ZIP, or ambiguous pair of jobs. */
internal object DriveJobMatcher {
    fun find(transfer: DriveFileTransfer, hash: String?, jobs: List<TorBoxIntegrationJob>): TorBoxIntegrationJob? {
        if (transfer.state !in setOf(DriveFileState.SUBMITTING, DriveFileState.SUBMITTED, DriveFileState.UNCERTAIN)) return null
        return jobs.filter { job ->
            job.id != null && job.fileId == transfer.fileId && !job.zip &&
                job.type.equals("torrent", ignoreCase = true) &&
                job.integration?.lowercase(Locale.ROOT)?.replace("_", "")?.replace(" ", "") == "googledrive" &&
                (job.hash.isNullOrBlank() || job.hash.equals(hash, ignoreCase = true)) &&
                if (transfer.jobId != null) job.id == transfer.jobId else {
                    transfer.baselineCaptured && job.id !in transfer.priorJobIds &&
                        transfer.lastAttemptAt != null && job.createdAt != null &&
                        !job.createdAt.isBefore(transfer.lastAttemptAt.minusSeconds(120))
                }
        }.distinctBy { it.id }.singleOrNull()
    }
}
