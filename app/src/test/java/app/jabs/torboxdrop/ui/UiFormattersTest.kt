package app.jabs.torboxdrop.ui

import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UiFormattersTest {
    @Test
    fun torBoxProgressIsInterpretedAsZeroToOneFraction() {
        assertThat(normalizedProgress(0.0)).isEqualTo(0f)
        assertThat(normalizedProgress(0.5)).isWithin(0.0001f).of(0.5f)
        assertThat(normalizedProgress(0.685)).isWithin(0.0001f).of(0.685f)
        assertThat(normalizedProgress(1.0)).isEqualTo(1f)
        assertThat(percentLabel(0.5)).isEqualTo("50%")
        assertThat(percentLabel(1.0)).isEqualTo("100%")
    }

    @Test
    fun progressClampsInvalidAndOutOfRangeServerValues() {
        assertThat(normalizedProgress(null)).isEqualTo(0f)
        assertThat(percentLabel(null)).isEqualTo("—")
        assertThat(normalizedProgress(Double.NaN)).isEqualTo(0f)
        assertThat(percentLabel(Double.NaN)).isEqualTo("—")
        assertThat(normalizedProgress(-2.0)).isEqualTo(0f)
        assertThat(normalizedProgress(1.4)).isEqualTo(1f)
    }

    @Test
    fun displayProgressUsesServerByteRatioBeforeRawProgress() {
        val item = DownloadItem(
            id = "bytes",
            type = DownloadType.TORRENT,
            name = "Byte-counted download",
            progress = 0.01,
            totalSize = 592_000_000L,
            downloadedBytes = 303_000_000L,
        )

        assertThat(displayProgressFraction(item)).isWithin(0.0001f).of(303f / 592f)
        assertThat(percentLabel(item)).isEqualTo("51%")
    }

    @Test
    fun displayProgressFallsBackToRawFractionWhenByteCountsAreUnavailableOrInvalid() {
        val missingBytes = DownloadItem(
            id = "raw",
            type = DownloadType.WEB,
            name = "Raw progress",
            progress = 0.685,
        )
        val invalidBytes = missingBytes.copy(
            id = "invalid-bytes",
            totalSize = 100L,
            downloadedBytes = 101L,
        )

        assertThat(displayProgressFraction(missingBytes)).isWithin(0.0001f).of(0.685f)
        assertThat(displayProgressFraction(invalidBytes)).isWithin(0.0001f).of(0.685f)
        assertThat(percentLabel(invalidBytes)).isEqualTo("69%")
    }

    @Test
    fun displayProgressUsesReadinessOnlyWhenNoProgressDataExists() {
        val unknown = DownloadItem(
            id = "unknown",
            type = DownloadType.TORRENT,
            name = "Unknown progress",
        )
        val ready = unknown.copy(downloadFinished = true, downloadPresent = true)

        assertThat(displayProgressFraction(unknown)).isNull()
        assertThat(percentLabel(unknown)).isEqualTo("—")
        assertThat(displayProgressFraction(ready)).isEqualTo(1f)
        assertThat(percentLabel(ready)).isEqualTo("100%")
    }

    @Test
    fun authoritativeReadinessOverridesStaleProgressCounters() {
        val ready = DownloadItem(
            id = "ready-with-stale-counters",
            type = DownloadType.TORRENT,
            name = "Ready download",
            progress = 0.01,
            totalSize = 592_000_000L,
            downloadedBytes = 303_000_000L,
            downloadFinished = true,
            downloadPresent = true,
        )

        assertThat(displayProgressFraction(ready)).isEqualTo(1f)
        assertThat(percentLabel(ready)).isEqualTo("100%")
    }

    @Test
    fun compactDisplayNamePreservesSimpleAndCompoundSuffixes() {
        val video = compactDisplayName("A very long release name that identifies the episode.mkv", 24)
        val archive = compactDisplayName("a-very-long-nightly-backup-name.tar.gz", 22)

        assertThat(video.length).isAtMost(24)
        assertThat(video).endsWith(".mkv")
        assertThat(video).contains("…")
        assertThat(archive.length).isAtMost(22)
        assertThat(archive).endsWith(".tar.gz")
    }

    @Test
    fun activeMetadataNeverInventsMissingSwarmCounts() {
        assertThat(activeMetadata(torrent(seeds = 8, peers = null)))
            .isEqualTo("Downloading · 8S")
        assertThat(activeMetadata(torrent(seeds = null, peers = 3)))
            .isEqualTo("Downloading · 3P")
        assertThat(activeMetadata(torrent(seeds = null, peers = null)))
            .isEqualTo("Downloading")
        assertThat(activeMetadata(torrent(seeds = 0, peers = null)))
            .isEqualTo("Downloading · 0S")
    }

    private fun torrent(seeds: Int?, peers: Int?) = DownloadItem(
        id = "torrent",
        type = DownloadType.TORRENT,
        name = "Torrent",
        friendlyState = "Downloading",
        seeds = seeds,
        peers = peers,
    )
}
