package app.jabs.torboxdrop.util

import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import app.jabs.torboxdrop.model.QueuedDownload
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecentAdditionsTest {
    @Test
    fun missingRecentDownloadIsKeptUntilAggregateListCatchesUp() {
        val existing = download("1", "Existing")
        val recent = download("2", "New torrent")

        val merged = RecentAdditions.mergeDownloads(listOf(existing), listOf(recent))

        assertThat(merged.map(DownloadItem::id)).containsExactly("1", "2").inOrder()
    }

    @Test
    fun serverDownloadWinsOnceItAppears() {
        val provisional = download("2", "Waiting for TorBox")
        val server = download("2", "Actual torrent")

        val merged = RecentAdditions.mergeDownloads(listOf(server), listOf(provisional))

        assertThat(merged).containsExactly(server)
    }

    @Test
    fun recentQueueEntryIsKeptWithoutDuplicatingServerEntry() {
        val provisional = QueuedDownload("8", DownloadType.TORRENT, "Waiting")
        val server = provisional.copy(name = "Actual")

        assertThat(RecentAdditions.mergeQueue(emptyList(), listOf(provisional)))
            .containsExactly(provisional)
        assertThat(RecentAdditions.mergeQueue(listOf(server), listOf(provisional)))
            .containsExactly(server)
    }

    private fun download(id: String, name: String) = DownloadItem(
        id = id,
        type = DownloadType.TORRENT,
        name = name,
    )
}
