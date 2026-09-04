package app.jabs.torboxdrop.ui

import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UiFormattersTest {
    @Test
    fun torBoxProgressIsAlwaysInterpretedAsZeroToOneHundredPercent() {
        assertThat(normalizedProgress(0.5)).isWithin(0.0001f).of(0.005f)
        assertThat(normalizedProgress(1.0)).isWithin(0.0001f).of(0.01f)
        assertThat(normalizedProgress(68.5)).isWithin(0.0001f).of(0.685f)
        assertThat(normalizedProgress(100.0)).isEqualTo(1f)
        assertThat(percentLabel(0.5)).isEqualTo("1%")
        assertThat(percentLabel(1.0)).isEqualTo("1%")
    }

    @Test
    fun progressClampsInvalidAndOutOfRangeServerValues() {
        assertThat(normalizedProgress(null)).isEqualTo(0f)
        assertThat(percentLabel(null)).isEqualTo("—")
        assertThat(normalizedProgress(Double.NaN)).isEqualTo(0f)
        assertThat(percentLabel(Double.NaN)).isEqualTo("—")
        assertThat(normalizedProgress(-2.0)).isEqualTo(0f)
        assertThat(normalizedProgress(140.0)).isEqualTo(1f)
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
