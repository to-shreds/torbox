package app.jabs.torboxdrop.drive

import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import java.time.Duration

/** Mirrors the notification queue-to-active correlation rules for a durable Drive watch. */
internal object DriveQueuedMatcher {
    fun findActivated(watch: DriveWatch, candidates: List<DownloadItem>): DownloadItem? {
        if (watch.queueId == null) return null
        val activationFloor = watch.armedAt.minus(ACTIVATION_CLOCK_TOLERANCE)
        val eligible = candidates
            .asSequence()
            .filter { it.type == watch.type }
            .filter { it.createdAt?.isBefore(activationFloor) != true }
            .toList()
        val sourceMatches = eligible.filter { item ->
            (!watch.sourceHash.isNullOrBlank() && item.hash.equals(watch.sourceHash, ignoreCase = true)) ||
                (watch.sourceValue?.startsWith("magnet:", ignoreCase = true) == true &&
                    item.originalSource == watch.sourceValue)
        }
        return sourceMatches.singleOrNull()
    }

    private val ACTIVATION_CLOCK_TOLERANCE: Duration = Duration.ofMinutes(5)
}
