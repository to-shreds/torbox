package app.jabs.torboxdrop.model

import java.time.Instant
import java.util.Locale

enum class DownloadType { TORRENT, WEB }

enum class DownloadTab { ACTIVE, FINISHED, QUEUE, AIRLOCK }

enum class DownloadDensity { COMPACT, COZY, DETAILED }

enum class DownloadSort { NEWEST, OLDEST, LARGEST, NAME }

data class DownloadFilter(
    val type: DownloadType? = null,
    val problemsOnly: Boolean = false,
    val cachedOnly: Boolean = false,
    val taggedOnly: Boolean = false,
) {
    val isActive: Boolean
        get() = type != null || problemsOnly || cachedOnly || taggedOnly
}

data class DownloadItem(
    val id: String,
    val type: DownloadType,
    val name: String,
    val rawState: String = "",
    val friendlyState: String = "Unknown",
    /** Percentage in TorBox's documented 0..100 scale, or null when the API omitted it. */
    val progress: Double? = null,
    val totalSize: Long? = null,
    val downloadedBytes: Long? = null,
    val downloadSpeed: Long? = null,
    val uploadSpeed: Long? = null,
    val etaSeconds: Long? = null,
    val seeds: Int? = null,
    val peers: Int? = null,
    val ratio: Double? = null,
    val availability: Double? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val cachedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val tags: List<String> = emptyList(),
    val airLocked: Boolean = false,
    val downloadFinished: Boolean = false,
    val downloadPresent: Boolean = false,
    val cached: Boolean = false,
    val privateTorrent: Boolean? = null,
    val hash: String? = null,
    val trackerMessage: String? = null,
    val originalSource: String? = null,
    val fileCount: Int? = null,
    val allowZip: Boolean? = null,
    val error: String? = null,
    val sourceJson: String? = null,
) {
    val isReady: Boolean get() = downloadFinished && downloadPresent

    val isProblem: Boolean
        get() = error != null || normalizedState(rawState) in PROBLEM_STATES ||
            friendlyState.contains("stall", ignoreCase = true) ||
            friendlyState.contains("fail", ignoreCase = true) ||
            friendlyState.contains("missing", ignoreCase = true)

    companion object {
        private val PROBLEM_STATES = setOf(
            "stalled",
            "error",
            "failed",
            "failed_processing",
            "missing",
            "missingfiles",
            "not_present",
            "expired",
            "no_seeds",
        )

        private fun normalizedState(value: String): String = value
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
    }
}

data class QueuedDownload(
    val id: String,
    val type: DownloadType,
    val name: String,
    val queuedAt: Instant? = null,
    val source: String? = null,
    val sourceJson: String? = null,
)

data class DownloadFile(
    val id: Long,
    val downloadId: String,
    val downloadType: DownloadType,
    val name: String,
    val path: String? = null,
    val size: Long? = null,
    val mimeType: String? = null,
    val infected: Boolean = false,
)

data class AccountInfo(
    /** TorBox account identity used by its credential-free Relay refresh endpoint. */
    val userId: String? = null,
    val email: String? = null,
    val plan: String? = null,
    val subscriptionExpiresAt: Instant? = null,
    val premiumExpiresAt: Instant? = null,
    val totalDownloaded: Long? = null,
    val activeTorrentSlots: Int? = null,
    val activeWebSlots: Int? = null,
    val sourceJson: String? = null,
)

data class AddOptions(
    val queued: Boolean = false,
    val cachedOnly: Boolean = false,
    val notifyWhenComplete: Boolean = false,
    val customName: String? = null,
    val seed: Int = 1,
    val allowZip: Boolean = true,
)

data class AddResult(
    /** ID of an item already admitted to the active download list. */
    val id: String? = null,
    /** Queue identity returned by TorBox. This is not valid for mylist or requestdl calls. */
    val queuedId: String? = null,
    /** Server-reported source hash, useful for reconciling a queued item after activation. */
    val sourceHash: String? = null,
    val name: String? = null,
    val detail: String,
    val queued: Boolean = false,
)

data class RecentSend(
    val id: Long = 0,
    val value: String,
    val type: DownloadType,
    val sentAt: Instant,
    val source: String,
)

data class Bookmark(
    val id: Long = 0,
    val title: String,
    val url: String,
    val createdAt: Instant = Instant.now(),
)

data class NotificationSubscription(
    val subscriptionKey: String,
    val downloadId: String,
    val type: DownloadType,
    val name: String,
    val armedAt: Instant,
    val notifiedAt: Instant? = null,
    val hasBeenSeen: Boolean = true,
    val consecutiveMisses: Int = 0,
    val queueId: String? = null,
    val sourceHash: String? = null,
    val sourceValue: String? = null,
)

sealed interface IncomingAdd {
    data class Text(val value: String, val source: String) : IncomingAdd
    data class TorrentFile(val uri: String, val displayName: String?) : IncomingAdd
}

data class DownloadsUiState(
    val downloads: List<DownloadItem> = emptyList(),
    val queue: List<QueuedDownload> = emptyList(),
    val selectedTab: DownloadTab = DownloadTab.ACTIVE,
    val density: DownloadDensity = DownloadDensity.COMPACT,
    val sort: DownloadSort = DownloadSort.NEWEST,
    val filter: DownloadFilter = DownloadFilter(),
    val search: String = "",
    val refreshing: Boolean = false,
    val initialLoading: Boolean = true,
    val offline: Boolean = false,
    val stale: Boolean = false,
    val lastUpdated: Instant? = null,
    val error: String? = null,
) {
    val active: List<DownloadItem> get() = downloads.filter { !it.isReady && !it.airLocked }
    val finished: List<DownloadItem> get() = downloads.filter { it.isReady && !it.airLocked }
    val airLocked: List<DownloadItem> get() = downloads.filter { it.airLocked }
}
