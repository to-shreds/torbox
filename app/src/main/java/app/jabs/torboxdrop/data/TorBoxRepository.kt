package app.jabs.torboxdrop.data

import app.jabs.torboxdrop.drive.DriveStore
import app.jabs.torboxdrop.drive.DriveTorrentIdentity
import app.jabs.torboxdrop.util.BencodeTorrentValidator
import app.jabs.torboxdrop.notifications.AccountSensitiveWorkGate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import app.jabs.torboxdrop.drive.driveAccountScope
import app.jabs.torboxdrop.model.AccountInfo
import app.jabs.torboxdrop.model.AddOptions
import app.jabs.torboxdrop.model.AddResult
import app.jabs.torboxdrop.model.DownloadFile
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import app.jabs.torboxdrop.model.QueuedDownload
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RepositorySnapshot(
    val downloads: List<DownloadItem>,
    val queue: List<QueuedDownload>,
    val lastUpdated: Instant?,
    val fromCache: Boolean,
)

/** A successful remote deletion must not be mislabeled as failed by later local cleanup. */
data class DeleteResult(val localCleanupComplete: Boolean)

data class RelayRefreshResult(val requested: Int, val succeeded: Int)

internal class RelayRequestCoalescer(
    private val suppressionWindowMillis: Long = RELAY_SUPPRESSION_WINDOW_MILLIS,
    private val maxEntries: Int = MAX_TRACKED_RELAY_REQUESTS,
    private val elapsedRealtimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class RequestKey(val userId: String, val torrentId: String)

    private val mutex = Mutex()
    private val requestedAt = linkedMapOf<RequestKey, Long>()

    init {
        require(suppressionWindowMillis > 0) { "suppressionWindowMillis must be positive" }
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    /** Atomically marks and returns IDs eligible to contact Relay in this pass. */
    suspend fun claim(
        userId: String,
        torrentIds: Collection<String>,
        maxClaims: Int,
    ): List<String> {
        if (maxClaims <= 0) return emptyList()
        val normalizedUserId = userId.trim()
        if (normalizedUserId.isEmpty()) return emptyList()

        return mutex.withLock {
            val now = elapsedRealtimeMillis()
            pruneExpired(now)
            val claimed = buildList {
                for (rawId in torrentIds) {
                    val torrentId = rawId.trim()
                    if (torrentId.isEmpty()) continue
                    val key = RequestKey(normalizedUserId, torrentId)
                    val previous = requestedAt[key]
                    if (previous != null && now - previous < suppressionWindowMillis) continue

                    // Mark before any network coroutine is launched so overlapping callers coalesce.
                    requestedAt[key] = now
                    add(torrentId)
                    if (size == maxClaims) break
                }
            }
            trimToBound()
            claimed
        }
    }

    internal suspend fun trackedEntryCount(): Int = mutex.withLock { requestedAt.size }

    private fun pruneExpired(now: Long) {
        val iterator = requestedAt.entries.iterator()
        while (iterator.hasNext()) {
            val requested = iterator.next().value
            if (now >= requested && now - requested >= suppressionWindowMillis) iterator.remove()
        }
    }

    private fun trimToBound() {
        val iterator = requestedAt.entries.iterator()
        while (requestedAt.size > maxEntries && iterator.hasNext()) {
            iterator.next()
            iterator.remove()
        }
    }

    private companion object {
        const val RELAY_SUPPRESSION_WINDOW_MILLIS = 10_000L
        const val MAX_TRACKED_RELAY_REQUESTS = 512
    }
}

/**
 * Repository coordinating native TorBox calls with the durable last-known cache.
 *
 * Network errors remain typed [TorBoxException]s. Callers can first render [loadCached], then call
 * [refresh] and keep the last-known snapshot visible if refresh fails.
 */
class TorBoxRepository(
    private val api: TorBoxApiClient,
    private val localStore: LocalStore,
    private val preferences: AppPreferences,
    private val driveStore: DriveStore? = null,
    private val tokenProvider: () -> String? = { null },
    private val onDriveWatchArmed: () -> Unit = {},
) {
    private val writeMutex = Mutex()
    private val relayRequestCoalescer = RelayRequestCoalescer()

    suspend fun loadCached(): RepositorySnapshot = RepositorySnapshot(
        downloads = localStore.loadDownloads(),
        queue = localStore.loadQueue(),
        lastUpdated = preferences.lastDownloadsRefreshEpochMillis
            .takeIf { it > 0 }
            ?.let(Instant::ofEpochMilli),
        fromCache = true,
    )

    suspend fun refresh(bypassCache: Boolean = false): RepositorySnapshot = coroutineScope {
        val torrents = async { api.getTorrentDownloads(bypassCache) }
        val webDownloads = async { api.getWebDownloads(bypassCache) }
        val queue = async { api.getQueuedDownloads(bypassCache) }
        val downloads = torrents.await() + webDownloads.await()
        val queued = queue.await()
        val refreshedAt = Instant.now()
        writeMutex.withLock {
            val visible = localStore.filterDeletedDownloads(downloads)
            val visibleQueue = localStore.filterDeletedQueue(queued)
            localStore.replaceDownloads(visible)
            localStore.replaceQueue(visibleQueue)
            preferences.lastDownloadsRefreshEpochMillis = refreshedAt.toEpochMilli()
            RepositorySnapshot(visible, visibleQueue, refreshedAt, fromCache = false)
        }
    }

    /** Uses bypass_cache for responsive, server-backed Active-screen refreshes. */
    suspend fun refreshActive(): RepositorySnapshot = refresh(bypassCache = true)

    suspend fun refreshDownloads(bypassCache: Boolean = false): List<DownloadItem> = coroutineScope {
        val torrents = async { api.getTorrentDownloads(bypassCache) }
        val webDownloads = async { api.getWebDownloads(bypassCache) }
        val downloads = torrents.await() + webDownloads.await()
        writeMutex.withLock {
            val visible = localStore.filterDeletedDownloads(downloads)
            localStore.replaceDownloads(visible)
            preferences.lastDownloadsRefreshEpochMillis = Instant.now().toEpochMilli()
            visible
        }
    }

    suspend fun refreshQueue(bypassCache: Boolean = false): List<QueuedDownload> {
        val queue = api.getQueuedDownloads(bypassCache)
        return writeMutex.withLock {
            localStore.filterDeletedQueue(queue).also { localStore.replaceQueue(it) }
        }
    }

    /** Best-effort Relay refresh. Main API polling remains authoritative if Relay is unavailable. */
    suspend fun requestLiveTorrentUpdates(
        userId: String?,
        torrentIds: Collection<String>,
    ): RelayRefreshResult {
        val normalizedUserId = userId?.trim().orEmpty()
        if (normalizedUserId.isEmpty() || torrentIds.isEmpty()) return RelayRefreshResult(0, 0)
        val ids = relayRequestCoalescer.claim(
            userId = normalizedUserId,
            torrentIds = torrentIds,
            maxClaims = MAX_RELAY_ITEMS_PER_PASS,
        )
        if (ids.isEmpty()) return RelayRefreshResult(0, 0)
        val succeeded = coroutineScope {
            ids.chunked(RELAY_CONCURRENCY).sumOf { batch ->
                batch.map { id ->
                    async {
                        try {
                            api.requestLiveTorrentUpdate(normalizedUserId, id)
                            true
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            false
                        }
                    }
                }.awaitAll().count { it }
            }
        }
        return RelayRefreshResult(ids.size, succeeded)
    }

    suspend fun getDownload(
        type: DownloadType,
        id: String,
        bypassCache: Boolean = false,
    ): DownloadItem? {
        if (localStore.isDeleted(type, id)) return null
        val remote = api.getDownload(type, id, bypassCache)
        return writeMutex.withLock {
            if (localStore.isDeleted(type, id)) null else remote?.also { updated ->
                val current = localStore.loadDownloads()
                localStore.replaceDownloads(current.filterNot { it.type == type && it.id == id } + updated)
            }
        }
    }

    suspend fun getCachedDownload(type: DownloadType, id: String): DownloadItem? =
        localStore.loadDownloads().firstOrNull { it.type == type && it.id == id }

    suspend fun getFiles(
        type: DownloadType,
        id: String,
        forceRefresh: Boolean = false,
    ): List<DownloadFile> {
        if (!forceRefresh) {
            val cached = localStore.loadFiles(type, id)
            val expectedCount = getCachedDownload(type, id)?.fileCount
            if (cached.isNotEmpty() && (expectedCount == null || expectedCount == cached.size)) return cached
        }
        val files = api.getFiles(type, id, bypassCache = forceRefresh)
        localStore.saveFiles(type, id, files)
        return files
    }

    suspend fun getCachedFiles(type: DownloadType, id: String): List<DownloadFile> =
        localStore.loadFiles(type, id)

    suspend fun validateAccount(): AccountInfo = api.getCurrentUser()

    suspend fun createMagnet(
        magnet: String,
        options: AddOptions = AddOptions(),
        source: String = "manual",
    ): AddResult = AccountSensitiveWorkGate.mutex.withLock {
        val admission = prepareDriveAdmission(options, DriveTorrentIdentity.fromMagnet(magnet), null, "New torrent")
        val result = try { api.createMagnet(magnet, options)
        } catch (error: Exception) { failDefinitiveAdmission(admission, error); throw error }
        withContext(NonCancellable) {
            localStore.restoreAdded(result, DownloadType.TORRENT)
            finishDriveAdmission(result, admission, null, "New torrent").also {
                runCatching { localStore.addRecent(magnet, DownloadType.TORRENT, source) }
            }
        }
    }

    suspend fun createTorrent(
        bytes: ByteArray,
        fileName: String,
        options: AddOptions = AddOptions(),
        source: String = "file",
    ): AddResult = AccountSensitiveWorkGate.mutex.withLock {
        val requested = options.sendToGoogleDrive ?: preferences.googleDriveByDefault
        val hash = if (requested) BencodeTorrentValidator.infoHash(bytes) else null
        val admission = prepareDriveAdmission(options, hash, fileName, fileName)
        val result = try { api.createTorrent(bytes, fileName, options)
        } catch (error: Exception) { failDefinitiveAdmission(admission, error); throw error }
        withContext(NonCancellable) {
            localStore.restoreAdded(result, DownloadType.TORRENT)
            finishDriveAdmission(result, admission, fileName, fileName).also {
                runCatching { localStore.addRecent(fileName, DownloadType.TORRENT, source) }
            }
        }
    }

    suspend fun createWebDownload(
        url: String,
        options: AddOptions = AddOptions(),
        source: String = "manual",
    ): AddResult {
        val result = api.createWebDownload(url, options)
        withContext(NonCancellable) {
            localStore.restoreAdded(result, DownloadType.WEB)
            localStore.addRecent(url, DownloadType.WEB, source)
        }
        return result
    }

    suspend fun startQueued(id: String) = api.controlQueue(id, QueueControlOperation.START)

    suspend fun deleteQueued(id: String, type: DownloadType = DownloadType.TORRENT): DeleteResult =
        deleteConfirmed(type, id, queued = true) { api.controlQueue(id, QueueControlOperation.DELETE) }

    suspend fun reannounceTorrent(id: String) = api.controlTorrent(id, TorrentControlOperation.REANNOUNCE)

    suspend fun pauseTorrent(id: String) = api.controlTorrent(id, TorrentControlOperation.PAUSE)

    suspend fun resumeTorrent(id: String) = api.controlTorrent(id, TorrentControlOperation.RESUME)

    suspend fun deleteTorrent(id: String): DeleteResult =
        deleteConfirmed(DownloadType.TORRENT, id) { api.controlTorrent(id, TorrentControlOperation.DELETE) }

    suspend fun deleteWebDownload(id: String): DeleteResult =
        deleteConfirmed(DownloadType.WEB, id) { api.deleteWebDownload(id) }

    private suspend fun deleteConfirmed(
        type: DownloadType,
        id: String,
        queued: Boolean = false,
        remoteDelete: suspend () -> Unit,
    ): DeleteResult {
        val expectedAccount = driveAccountScope(tokenProvider())
        return AccountSensitiveWorkGate.mutex.withLock {
            check(expectedAccount == driveAccountScope(tokenProvider())) {
                "The TorBox account changed. Select the download again before deleting."
            }
            // A rejected delete must not remove the item or stop an unrelated Drive instruction.
            try {
                remoteDelete()
            } catch (error: TorBoxApiException) {
                if (error.apiCode != "ITEM_NOT_FOUND" || error.statusCode !in 200..499 ||
                    error.statusCode in listOf(401, 403, 429)) throw error
            }
            withContext(NonCancellable) {
                val cacheCleaned = runCatching {
                    writeMutex.withLock { localStore.markDeleted(type, id, queued) }
                }.isSuccess
                val driveCleaned = runCatching {
                    if (type == DownloadType.TORRENT && expectedAccount != null) {
                        driveStore?.stopUnsubmitted(expectedAccount, id, queued)
                    }
                }.isSuccess
                DeleteResult(cacheCleaned && driveCleaned)
            }
        }
    }

    suspend fun editDownload(type: DownloadType, id: String, edit: DownloadEdit): DownloadItem {
        val updated = api.editDownload(type, id, edit)
        updateCachedDownload(updated)
        return updated
    }

    suspend fun rename(type: DownloadType, id: String, name: String): DownloadItem =
        editDownload(type, id, DownloadEdit(name = name))

    suspend fun setTags(type: DownloadType, id: String, tags: List<String>): DownloadItem =
        editDownload(type, id, DownloadEdit(tags = tags))

    suspend fun setAirLock(type: DownloadType, id: String, airLocked: Boolean): DownloadItem =
        editDownload(type, id, DownloadEdit(airLocked = airLocked))

    suspend fun requestTemporaryDownloadUrl(
        type: DownloadType,
        id: String,
        fileId: Long? = null,
        zip: Boolean = false,
        appendName: Boolean = false,
    ): String = api.requestTemporaryDownloadUrl(type, id, fileId, zip, appendName)

    suspend fun checkTorrentCached(
        hashes: Collection<String>,
        includeFiles: Boolean = false,
    ): Map<String, CachedDownload> = api.checkTorrentCached(hashes, includeFiles)

    suspend fun checkWebCached(
        hashes: Collection<String>,
        includeFiles: Boolean = false,
    ): Map<String, CachedDownload> = api.checkWebCached(hashes, includeFiles)

    private data class DriveAdmission(val scope: String, val hash: String, val queueId: String)

    private suspend fun prepareDriveAdmission(
        options: AddOptions, hash: String?, sourceValue: String?, name: String,
    ): DriveAdmission? {
        if (!(options.sendToGoogleDrive ?: preferences.googleDriveByDefault)) return null
        val scope = driveAccountScope(tokenProvider())
        check(preferences.driveConfiguredFor(scope) && driveStore != null) {
            "Connect Google Drive in Settings before adding with Send to Google Drive enabled."
        }
        require(!hash.isNullOrBlank()) { "This torrent has no usable identity for safe Drive automation." }
        val admission = DriveAdmission(requireNotNull(scope), hash, "ADMISSION:$hash:${java.util.UUID.randomUUID()}")
        // Persist intent BEFORE remote creation. A process death after TorBox accepts the torrent
        // cannot lose the Drive request; its exact hash will correlate when the torrent appears.
        requireNotNull(driveStore).armQueued(admission.scope, DownloadType.TORRENT, admission.queueId, name, hash, sourceValue)
        runCatching { onDriveWatchArmed() }
        return admission
    }

    private suspend fun failDefinitiveAdmission(admission: DriveAdmission?, error: Exception) {
        if (admission == null || error is CancellationException) return
        if (error is TorBoxBadTokenException || error is TorBoxRateLimitException ||
            error is TorBoxApiException && error.statusCode in 400..499) {
            withContext(NonCancellable) {
                driveStore?.stopUnsubmitted(admission.scope, admission.queueId, queued = true)
            }
        } else runCatching { onDriveWatchArmed() }
    }

    private suspend fun finishDriveAdmission(
        result: AddResult, admission: DriveAdmission?, sourceValue: String?, fallbackName: String,
    ): AddResult {
        if (admission == null) return result
        val store = requireNotNull(driveStore)
        return try {
            val resolved = result.copy(sourceHash = result.sourceHash ?: admission.hash)
            when {
                resolved.id != null -> store.armActive(admission.scope, DownloadItem(
                    id = resolved.id, type = DownloadType.TORRENT, name = resolved.name ?: fallbackName,
                    hash = resolved.sourceHash), sourceValue, hasBeenSeen = false)
                resolved.queuedId != null -> store.armQueued(admission.scope, DownloadType.TORRENT,
                    resolved.queuedId, resolved.name ?: fallbackName, resolved.sourceHash, sourceValue)
            }
            if (resolved.id != null || resolved.queuedId != null) {
                store.stopUnsubmitted(admission.scope, admission.queueId, queued = true)
            }
            val scheduled = runCatching { onDriveWatchArmed() }.isSuccess
            resolved.copy(detail = resolved.detail + if (scheduled) {
                " Drive automation is armed; transfer status is in Settings."
            } else " Drive intent was saved, but Android could not schedule it. Open Drive settings and check again.")
        } catch (_: Exception) {
            // TorBox already accepted this add. Never show it as a failed add and invite a duplicate.
            result.copy(detail = result.detail + " TorBox added the torrent, but Drive setup could not finish. Check Drive settings; do not re-add it.")
        }
    }

    private suspend fun updateCachedDownload(updated: DownloadItem) = writeMutex.withLock {
        val current = localStore.loadDownloads()
        localStore.replaceDownloads(
            (current.filterNot { it.type == updated.type && it.id == updated.id } + updated),
        )
    }

    private companion object {
        const val MAX_RELAY_ITEMS_PER_PASS = 24
        const val RELAY_CONCURRENCY = 4
    }
}
