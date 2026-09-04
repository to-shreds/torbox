package app.jabs.torboxdrop.notifications

import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import java.time.Duration

/** Correlates a durable queue watch with the new active identity assigned by TorBox. */
internal object QueuedSubscriptionMatcher {
    fun findActivated(download: ArmedDownload, candidates: List<DownloadItem>): DownloadItem? {
        if (download.queueId == null) return null
        val expectedType = when (download.type) {
            MonitoredDownloadType.TORRENT -> DownloadType.TORRENT
            MonitoredDownloadType.WEB -> DownloadType.WEB
        }
        val activationFloor = download.armedAt.minus(ACTIVATION_CLOCK_TOLERANCE)
        val savedTorrentFileName = download.sourceValue?.takeIf {
            download.type == MonitoredDownloadType.TORRENT &&
                download.sourceHash.isNullOrBlank() &&
                it.isTorrentFileNameOnly()
        }
        val eligible = candidates
            .asSequence()
            .filter { it.type == expectedType }
            .filter { it.createdAt?.isBefore(activationFloor) != true }
            .toList()
        val sourceMatches = eligible
            .asSequence()
            .filter { item ->
                (!download.sourceHash.isNullOrBlank() &&
                        item.hash.equals(download.sourceHash, ignoreCase = true)) ||
                    (savedTorrentFileName == null &&
                        !download.sourceValue.isNullOrBlank() &&
                        item.originalSource == download.sourceValue)
            }
            .toList()
        if (sourceMatches.isNotEmpty()) return sourceMatches.singleOrNull()

        if (savedTorrentFileName == null || download.displayName.isBlank()) return null

        val nameMatches = eligible
            .asSequence()
            // Filename correlation is weaker than a hash or original URL, so require a known
            // server creation time and an exact saved display-name match. Ambiguity fails closed.
            .filter { it.createdAt != null }
            .filter { it.name == download.displayName }
            .toList()
        // Fail closed when TorBox exposes multiple indistinguishable active candidates. A later
        // refresh can reconcile once the server data is unambiguous; binding the wrong ID could
        // notify about or open another download.
        return nameMatches.singleOrNull()
    }

    private fun String.isTorrentFileNameOnly(): Boolean =
        endsWith(".torrent", ignoreCase = true) &&
            '/' !in this &&
            '\\' !in this &&
            !startsWith("magnet:", ignoreCase = true) &&
            "://" !in this

    private val ACTIVATION_CLOCK_TOLERANCE: Duration = Duration.ofMinutes(5)
}
