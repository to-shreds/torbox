package app.jabs.torboxdrop.ui

import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadSort
import app.jabs.torboxdrop.model.DownloadType
import app.jabs.torboxdrop.util.DownloadLists
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import org.junit.Test

class DownloadDatesTest {
    @Test fun addedShowsAbsoluteDateAndActualAge() {
        val text = dateAndAge(Instant.parse("2026-09-01T12:30:00Z"), Instant.parse("2026-09-20T13:00:00Z"), ZoneId.of("UTC"))
        assertThat(text).contains("2026")
        assertThat(text).contains("19d ago")
    }
    @Test fun unknownDateIsExplicitAndCacheDateIsNotInventedCompletion() {
        assertThat(dateAndAge(null)).isEqualTo("Not reported")
        val item = DownloadItem("1", DownloadType.TORRENT, "A", cachedAt = Instant.parse("2026-09-01T12:00:00Z"))
        assertThat(downloadDates(item)).contains("Added: Not reported")
        assertThat(downloadDates(item)).contains("Cached:")
        assertThat(downloadDates(item)).doesNotContain("Completed")
    }
    @Test fun dateSortDoesNotPretendUpdateTimeIsAddedTime() {
        val known = DownloadItem("1", DownloadType.TORRENT, "Known", createdAt = Instant.parse("2026-09-01T12:00:00Z"))
        val missing = DownloadItem("2", DownloadType.TORRENT, "Missing", updatedAt = Instant.parse("2030-09-01T12:00:00Z"))
        for (sort in listOf(DownloadSort.NEWEST, DownloadSort.OLDEST))
            assertThat(DownloadLists.filterAndSort(listOf(missing, known), sort = sort).last().id).isEqualTo("2")
    }
    @Test fun futureDatesDoNotBecomeNegativeAges() {
        assertThat(dateAndAge(Instant.parse("2027-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"))).contains("in the future")
    }
}
