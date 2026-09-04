package app.jabs.torboxdrop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.jabs.torboxdrop.model.DownloadFilter
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadSort
import app.jabs.torboxdrop.model.DownloadTab
import app.jabs.torboxdrop.model.DownloadType
import app.jabs.torboxdrop.model.DownloadsUiState
import app.jabs.torboxdrop.model.QueuedDownload
import app.jabs.torboxdrop.ui.DownloadsScreen
import app.jabs.torboxdrop.ui.theme.TorBoxDropTheme
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Debug-build-only visual QA harness for the real download list. It deliberately
 * contains no repository, API, account, or token integration.
 *
 * Launch with:
 * adb shell am start -n app.jabs.torboxdrop/.PreviewActivity
 */
class PreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        var uiState by mutableStateOf(sampleDownloadsState())
        var watchedKeys by mutableStateOf(
            setOf(
                DownloadType.TORRENT to "active-1",
                DownloadType.WEB to "active-4",
            ),
        )

        setContent {
            TorBoxDropTheme {
                DownloadsScreen(
                    state = uiState,
                    watchedDownloadKeys = watchedKeys,
                    onRefresh = {
                        uiState = uiState.copy(
                            refreshing = false,
                            offline = false,
                            stale = false,
                            error = null,
                            lastUpdated = Instant.now(),
                        )
                    },
                    onTabSelected = { uiState = uiState.copy(selectedTab = it) },
                    onDensityChanged = { uiState = uiState.copy(density = it) },
                    onSearchChanged = { uiState = uiState.copy(search = it) },
                    onSortChanged = { uiState = uiState.copy(sort = it) },
                    onFilterChanged = { uiState = uiState.copy(filter = it) },
                    onDownloadClick = {},
                    onToggleNotification = { item ->
                        val key = item.type to item.id
                        watchedKeys = if (key in watchedKeys) watchedKeys - key else watchedKeys + key
                    },
                    onShare = {},
                    onFiles = {},
                    onToggleAirLock = { item ->
                        uiState = uiState.copy(
                            downloads = uiState.downloads.map { existing ->
                                if (existing.type == item.type && existing.id == item.id) {
                                    existing.copy(airLocked = !existing.airLocked)
                                } else {
                                    existing
                                }
                            },
                        )
                    },
                    onDownloadMenu = {},
                    onStartQueued = { queued ->
                        uiState = uiState.copy(queue = uiState.queue.filterNot { it.sameIdentityAs(queued) })
                    },
                    onDeleteQueued = { queued ->
                        uiState = uiState.copy(queue = uiState.queue.filterNot { it.sameIdentityAs(queued) })
                    },
                    modifier = Modifier.safeDrawingPadding(),
                )
            }
        }
    }
}

private fun sampleDownloadsState(): DownloadsUiState {
    val now = Instant.parse("2026-09-04T16:00:00Z")
    val active = listOf(
        activeItem(1, "The.Great.Northern.Adventure.S03E08.1080p.WEB-DL.mkv", 68.0, 11_400_000, 248, 147, 12, "Downloading"),
        activeItem(2, "Ubuntu 26.04 Desktop amd64.iso", 13.0, 5_800_000, 1_940, 83, 7, "Downloading"),
        activeItem(3, "A very long documentary filename designed to verify that ordinary compact rows never grow into enormous cards 2026 2160p HDR.mkv", 42.0, 0, null, 0, 18, "Stalled · no seeds", problem = true),
        activeItem(4, "conference-recording-part-04.mp4", 91.0, 22_700_000, 42, null, null, "Downloading", type = DownloadType.WEB),
        activeItem(5, "Open source archive collection.tar.zst", 0.0, null, null, null, null, "Waiting for metadata"),
        activeItem(6, "Family vacation videos.zip", 99.5, 720_000, 18, 4, 2, "Processing files"),
        activeItem(7, "sample-dataset-2026-09.csv.gz", 27.0, 3_200_000, 862, null, null, "Downloading", type = DownloadType.WEB),
        activeItem(8, "Retro game manuals preservation pack.pdf", 76.0, 8_900_000, 196, 61, 5, "Downloading"),
        activeItem(9, "Indie Film Festival Shorts Collection", 34.0, 1_600_000, 3_612, 2, 19, "Slow · low availability"),
        activeItem(10, "linux-kernel-mirror.bundle", 58.0, 14_100_000, 314, null, null, "Downloading", type = DownloadType.WEB),
        activeItem(11, "Public domain audiobooks volume 17", 84.0, 6_600_000, 521, 96, 8, "Downloading"),
        activeItem(12, "Failed processing example.zip", 100.0, null, null, null, null, "Failed processing", problem = true, type = DownloadType.WEB),
    ).mapIndexed { index, item ->
        item.copy(
            createdAt = now.minus((index + 1).toLong(), ChronoUnit.HOURS),
            updatedAt = now.minus((index * 4L).coerceAtLeast(1), ChronoUnit.SECONDS),
        )
    }

    val finished = (1..12).map { index ->
        val extension = when (index % 4) {
            0 -> "mkv"
            1 -> "zip"
            2 -> "mp4"
            else -> "pdf"
        }
        DownloadItem(
            id = "finished-$index",
            type = if (index % 3 == 0) DownloadType.WEB else DownloadType.TORRENT,
            name = when (index) {
                3 -> "A completed item with an intentionally very long but still ordinary media filename whose .$extension extension should remain visible.$extension"
                8 -> "Short name.$extension"
                else -> "Finished download ${index.toString().padStart(2, '0')} - sample library item.$extension"
            },
            rawState = "completed",
            friendlyState = "Ready",
            progress = 100.0,
            totalSize = 380_000_000L + index * 910_000_000L,
            createdAt = now.minus((index + 2).toLong(), ChronoUnit.DAYS),
            updatedAt = now.minus(index.toLong(), ChronoUnit.HOURS),
            expiresAt = now.plus((index + 2).toLong(), ChronoUnit.DAYS),
            tags = if (index % 4 == 0) listOf("favorite", "video") else emptyList(),
            downloadFinished = true,
            downloadPresent = true,
            cached = index % 2 == 0,
            fileCount = if (index % 5 == 0) 1 else index + 2,
            allowZip = index % 5 != 0,
        )
    }

    val airLocked = (1..3).map { index ->
        DownloadItem(
            id = "airlock-$index",
            type = if (index == 2) DownloadType.WEB else DownloadType.TORRENT,
            name = "Protected archive $index.zip",
            rawState = "completed",
            friendlyState = "Ready",
            progress = 100.0,
            totalSize = index * 2_400_000_000L,
            createdAt = now.minus((index * 12).toLong(), ChronoUnit.DAYS),
            updatedAt = now.minus(index.toLong(), ChronoUnit.DAYS),
            tags = listOf("keep"),
            airLocked = true,
            downloadFinished = true,
            downloadPresent = true,
            cached = true,
            fileCount = index * 4,
            allowZip = true,
        )
    }

    val queue = (1..5).map { index ->
        QueuedDownload(
            id = "queue-$index",
            type = if (index % 2 == 0) DownloadType.WEB else DownloadType.TORRENT,
            name = if (index == 4) {
                "An unusually long queued web download name used to verify ellipsis and row stability.zip"
            } else {
                "Queued sample $index"
            },
            queuedAt = now.minus((index * 7).toLong(), ChronoUnit.MINUTES),
            source = if (index % 2 == 0) "https://example.test/files/sample-$index.zip" else "magnet:?xt=urn:btih:debug$index",
        )
    }

    return DownloadsUiState(
        downloads = active + finished + airLocked,
        queue = queue,
        selectedTab = DownloadTab.ACTIVE,
        sort = DownloadSort.NEWEST,
        filter = DownloadFilter(),
        initialLoading = false,
        lastUpdated = now,
    )
}

private fun activeItem(
    index: Int,
    name: String,
    progress: Double,
    speed: Long?,
    eta: Long?,
    seeds: Int?,
    peers: Int?,
    state: String,
    problem: Boolean = false,
    type: DownloadType = DownloadType.TORRENT,
): DownloadItem = DownloadItem(
    id = "active-$index",
    type = type,
    name = name,
    rawState = if (problem) "stalled" else "downloading",
    friendlyState = state,
    progress = progress,
    totalSize = 680_000_000L + index * 1_170_000_000L,
    downloadedBytes = ((680_000_000L + index * 1_170_000_000L) * (progress / 100.0)).toLong(),
    downloadSpeed = speed,
    uploadSpeed = if (type == DownloadType.TORRENT && speed != null) speed / 18 else null,
    etaSeconds = eta,
    seeds = seeds,
    peers = peers,
    ratio = if (type == DownloadType.TORRENT) index / 10.0 else null,
    availability = if (problem) .42 else 1.0,
    cached = index % 4 == 0,
    fileCount = if (type == DownloadType.TORRENT) index + 1 else 1,
    error = if (problem && index == 12) "Archive processing failed" else null,
)

private fun QueuedDownload.sameIdentityAs(other: QueuedDownload): Boolean =
    id == other.id && type == other.type
