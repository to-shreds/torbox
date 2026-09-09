package app.jabs.torboxdrop.drive

import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import java.time.Duration

/** Mirrors the notification queue-to-active correlation rules for a durable Drive watch. */
internal object DriveQueuedMatcher {
    fun findActivated(watch: DriveWatch, candidates: List<DownloadItem>): DownloadItem? {
        if (watch.queueId == null) return null
        val activationFloor = watch.armedAt.minus(ACTIVATION_CLOCK_TOLERANCE)
        val savedTorrentFileName = watch.sourceValue?.takeIf {
            watch.type == DownloadType.TORRENT && watch.sourceHash.isNullOrBlank() && it.isTorrentFileNameOnly()
        }
        val eligible = candidates
            .asSequence()
            .filter { it.type == watch.type }
            .filter { it.createdAt?.isBefore(activationFloor) != true }
            .toList()
        val sourceMatches = eligible.filter { item ->
            (!watch.sourceHash.isNullOrBlank() && item.hash.equals(watch.sourceHash, ignoreCase = true)) ||
                (savedTorrentFileName == null &&
                    !watch.sourceValue.isNullOrBlank() &&
                    item.originalSource == watch.sourceValue)
        }
        if (sourceMatches.isNotEmpty()) return sourceMatches.singleOrNull()
        if (savedTorrentFileName == null || watch.name.isBlank()) return null
        return eligible
            .filter { it.createdAt != null }
            .filter { it.name == watch.name }
            .singleOrNull()
    }

    private fun String.isTorrentFileNameOnly(): Boolean =
        endsWith(".torrent", ignoreCase = true) &&
            '/' !in this &&
            '\\' !in this &&
            !startsWith("magnet:", ignoreCase = true) &&
            "://" !in this

    private val ACTIVATION_CLOCK_TOLERANCE: Duration = Duration.ofMinutes(5)
}
