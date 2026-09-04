package app.jabs.torboxdrop.ui

import app.jabs.torboxdrop.model.DownloadFilter
import app.jabs.torboxdrop.model.DownloadSort
import app.jabs.torboxdrop.model.DownloadTab
import app.jabs.torboxdrop.model.DownloadType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DownloadsUiLogicTest {
    @Test
    fun queueOffersOnlySortsThatHaveQueueMeaning() {
        assertThat(sortOptionsFor(DownloadTab.QUEUE))
            .containsExactly(DownloadSort.NEWEST, DownloadSort.OLDEST, DownloadSort.NAME)
            .inOrder()
        assertThat(DownloadSort.LARGEST.effectiveFor(DownloadTab.QUEUE))
            .isEqualTo(DownloadSort.NEWEST)
        assertThat(sortOptionsFor(DownloadTab.ACTIVE))
            .containsExactlyElementsIn(DownloadSort.entries)
            .inOrder()
    }

    @Test
    fun queueBadgeCountsOnlyTheTypeFilterThatQueueApplies() {
        val ignoredQueueFilters = DownloadFilter(
            problemsOnly = true,
            cachedOnly = true,
            taggedOnly = true,
        )

        assertThat(ignoredQueueFilters.isActiveFor(DownloadTab.QUEUE)).isFalse()
        assertThat(ignoredQueueFilters.isActiveFor(DownloadTab.ACTIVE)).isTrue()
        val queueFilters = ignoredQueueFilters.copy(type = DownloadType.TORRENT)
        assertThat(queueFilters.isActiveFor(DownloadTab.QUEUE)).isTrue()
        assertThat(queueFilters.clearedFor(DownloadTab.QUEUE)).isEqualTo(ignoredQueueFilters)
        assertThat(queueFilters.clearedFor(DownloadTab.ACTIVE)).isEqualTo(DownloadFilter())
    }

    @Test
    fun responsiveTableLayoutStartsAtEightHundredFortyDp() {
        assertThat(WIDE_LAYOUT_MIN_WIDTH_DP).isEqualTo(840)
    }
}
