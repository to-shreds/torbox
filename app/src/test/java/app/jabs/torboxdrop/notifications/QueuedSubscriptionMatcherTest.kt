package app.jabs.torboxdrop.notifications

import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

class QueuedSubscriptionMatcherTest {
    private val armedAt = Instant.parse("2026-09-04T12:00:00Z")

    @Test
    fun matchesActivatedTorrentByServerHashIgnoringCase() {
        val watch = watch(sourceHash = "ABC123")
        val activated = item("active-9", hash = "abc123", createdAt = armedAt.plusSeconds(10))

        assertThat(QueuedSubscriptionMatcher.findActivated(watch, listOf(activated)))
            .isEqualTo(activated)
    }

    @Test
    fun matchesActivatedWebDownloadByOriginalSource() {
        val source = "https://example.test/video.mp4"
        val watch = watch(type = MonitoredDownloadType.WEB, sourceValue = source)
        val activated = item(
            id = "web-4",
            type = DownloadType.WEB,
            source = source,
            createdAt = armedAt.plusSeconds(20),
        )

        assertThat(QueuedSubscriptionMatcher.findActivated(watch, listOf(activated)))
            .isEqualTo(activated)
    }

    @Test
    fun ignoresOldHistoryAndWrongDownloadType() {
        val watch = watch(sourceHash = "same-hash")
        val old = item("old", hash = "same-hash", createdAt = armedAt.minusSeconds(600))
        val wrongType = item(
            id = "wrong",
            type = DownloadType.WEB,
            hash = "same-hash",
            createdAt = armedAt.plusSeconds(5),
        )

        assertThat(QueuedSubscriptionMatcher.findActivated(watch, listOf(old, wrongType))).isNull()
    }

    @Test
    fun neverTreatsQueueIdAsAnActiveDownloadId() {
        val watch = watch()
        val unrelated = item("17", createdAt = armedAt.plusSeconds(10))

        assertThat(QueuedSubscriptionMatcher.findActivated(watch, listOf(unrelated))).isNull()
    }

    @Test
    fun failsClosedWhenTwoActiveItemsMatchTheSameSource() {
        val source = "magnet:?xt=urn:btih:duplicate"
        val watch = watch(sourceValue = source)
        val first = item("active-a", source = source, createdAt = armedAt.plusSeconds(10))
        val second = item("active-b", source = source, createdAt = armedAt.plusSeconds(20))

        assertThat(QueuedSubscriptionMatcher.findActivated(watch, listOf(first, second))).isNull()
    }

    @Test
    fun matchesQueuedTorrentFileByExactDisplayNameWhenUnique() {
        val watch = watch(
            displayName = "Linux release.torrent",
            sourceValue = "Linux release.torrent",
        )
        val activated = item(
            id = "active-file",
            name = "Linux release.torrent",
            createdAt = armedAt.plusSeconds(10),
        )

        assertThat(QueuedSubscriptionMatcher.findActivated(watch, listOf(activated)))
            .isEqualTo(activated)
    }

    @Test
    fun queuedTorrentFileNameFallbackFailsClosedWhenAmbiguous() {
        val watch = watch(
            displayName = "Same release.torrent",
            sourceValue = "Same release.torrent",
        )
        val first = item(
            id = "active-file-a",
            name = "Same release.torrent",
            createdAt = armedAt.plusSeconds(10),
        )
        val second = item(
            id = "active-file-b",
            name = "Same release.torrent",
            createdAt = armedAt.plusSeconds(20),
        )

        assertThat(QueuedSubscriptionMatcher.findActivated(watch, listOf(first, second))).isNull()
    }

    @Test
    fun queuedTorrentFileNameFallbackRejectsMismatchedOrOldItems() {
        val watch = watch(
            displayName = "Expected.torrent",
            sourceValue = "Expected.torrent",
        )
        val mismatched = item(
            id = "wrong-name",
            name = "Different.torrent",
            source = "Expected.torrent",
            createdAt = armedAt.plusSeconds(10),
        )
        val tooOld = item(
            id = "too-old",
            name = "Expected.torrent",
            createdAt = armedAt.minusSeconds(301),
        )

        assertThat(QueuedSubscriptionMatcher.findActivated(watch, listOf(mismatched, tooOld)))
            .isNull()
    }

    private fun watch(
        type: MonitoredDownloadType = MonitoredDownloadType.TORRENT,
        sourceHash: String? = null,
        sourceValue: String? = null,
        displayName: String = "Queued item",
    ) = ArmedDownload(
        subscriptionKey = "queued:T:17",
        downloadId = "17",
        type = type,
        displayName = displayName,
        armedAt = armedAt,
        hasBeenSeen = false,
        queueId = "17",
        sourceHash = sourceHash,
        sourceValue = sourceValue,
    )

    private fun item(
        id: String,
        type: DownloadType = DownloadType.TORRENT,
        hash: String? = null,
        source: String? = null,
        name: String = "Activated",
        createdAt: Instant,
    ) = DownloadItem(
        id = id,
        type = type,
        name = name,
        hash = hash,
        originalSource = source,
        createdAt = createdAt,
    )
}
