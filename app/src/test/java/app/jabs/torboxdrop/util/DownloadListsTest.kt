package app.jabs.torboxdrop.util

import app.jabs.torboxdrop.model.DownloadFile
import app.jabs.torboxdrop.model.DownloadFilter
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadSort
import app.jabs.torboxdrop.model.DownloadTab
import app.jabs.torboxdrop.model.DownloadType
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadListsTest {
    private val old = item(
        id = "old",
        name = "Alpha Linux",
        type = DownloadType.TORRENT,
        created = "2026-01-01T00:00:00Z",
        size = 10,
        cached = true,
        tags = listOf("iso"),
    )
    private val newest = item(
        id = "new",
        name = "Movie Night",
        type = DownloadType.WEB,
        created = "2026-09-01T00:00:00Z",
        size = 30,
        ready = true,
    )
    private val stalled = item(
        id = "stalled",
        name = "Beta",
        type = DownloadType.TORRENT,
        created = "2026-06-01T00:00:00Z",
        size = 20,
        rawState = "stalled (no seeds)",
    )
    private val locked = item(
        id = "locked",
        name = "Archive",
        type = DownloadType.WEB,
        created = "2026-07-01T00:00:00Z",
        size = null,
        ready = true,
        airLocked = true,
    )

    @Test
    fun stateMapper_doesNotTreatCompletedLabelAsAuthoritativeReadiness() {
        assertEquals(
            DownloadStateCategory.PROCESSING,
            TorBoxStateMapper.category("completed", downloadFinished = false, downloadPresent = false),
        )
        assertEquals("Ready", TorBoxStateMapper.friendly("completed", downloadFinished = true, downloadPresent = true))
        assertEquals("Fetching metadata", TorBoxStateMapper.friendly("metaDL"))
        assertEquals("Processing failed", TorBoxStateMapper.friendly("failed_processing"))
        assertTrue(TorBoxStateMapper.isProblem("stalled (no seeds)"))
        assertEquals(DownloadStateCategory.PAUSED, TorBoxStateMapper.category("pausedDL"))
        assertEquals(DownloadStateCategory.MISSING, TorBoxStateMapper.category("missingFiles"))
        assertEquals(DownloadStateCategory.MISSING, TorBoxStateMapper.category("not_present"))
        assertTrue(item("missing-files", "Missing", DownloadType.TORRENT, "2026-01-01T00:00:00Z", 1, rawState = "missingFiles").isProblem)
        assertTrue(item("not-present", "Absent", DownloadType.WEB, "2026-01-01T00:00:00Z", 1, rawState = "not present").isProblem)
    }

    @Test
    fun forTab_usesReadinessAndAirLockFields() {
        val all = listOf(old, newest, stalled, locked)

        assertEquals(listOf("old", "stalled"), DownloadLists.forTab(all, DownloadTab.ACTIVE).map { it.id })
        assertEquals(listOf("new"), DownloadLists.forTab(all, DownloadTab.FINISHED).map { it.id })
        assertEquals(listOf("locked"), DownloadLists.forTab(all, DownloadTab.AIRLOCK).map { it.id })
    }

    @Test
    fun filterAndSort_searchesNamesTagsAndLocallyLoadedFileNames() {
        val files = listOf(
            DownloadFile(1, "new", DownloadType.WEB, "feature-film.mp4"),
        )

        assertEquals(
            listOf("new"),
            DownloadLists.filterAndSort(listOf(old, newest), search = "film", files = files).map { it.id },
        )
        assertEquals(
            listOf("old"),
            DownloadLists.filterAndSort(listOf(old, newest), search = "alpha iso", files = files).map { it.id },
        )
        assertEquals(
            listOf("new"),
            DownloadLists.filterAndSort(
                listOf(old, newest),
                search = "cached subtitle",
                keysMatchingFiles = setOf("WEB:new"),
            ).map { it.id },
        )
    }

    @Test
    fun filterAndSort_appliesFiltersAndStableSortsWithUnknownValuesLast() {
        val all = listOf(locked, newest, stalled, old)

        assertEquals(
            listOf("stalled"),
            DownloadLists.filterAndSort(
                all,
                filter = DownloadFilter(type = DownloadType.TORRENT, problemsOnly = true),
            ).map { it.id },
        )
        assertEquals(
            listOf("new", "stalled", "old", "locked"),
            DownloadLists.filterAndSort(all, sort = DownloadSort.LARGEST).map { it.id },
        )
        assertEquals(
            listOf("old", "stalled", "locked", "new"),
            DownloadLists.filterAndSort(all, sort = DownloadSort.OLDEST).map { it.id },
        )
    }

    private fun item(
        id: String,
        name: String,
        type: DownloadType,
        created: String,
        size: Long?,
        ready: Boolean = false,
        airLocked: Boolean = false,
        cached: Boolean = false,
        tags: List<String> = emptyList(),
        rawState: String = "downloading",
    ) = DownloadItem(
        id = id,
        name = name,
        type = type,
        createdAt = Instant.parse(created),
        totalSize = size,
        downloadFinished = ready,
        downloadPresent = ready,
        airLocked = airLocked,
        cached = cached,
        tags = tags,
        rawState = rawState,
    )
}
