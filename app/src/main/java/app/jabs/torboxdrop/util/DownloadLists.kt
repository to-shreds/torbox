package app.jabs.torboxdrop.util

import app.jabs.torboxdrop.model.DownloadFile
import app.jabs.torboxdrop.model.DownloadFilter
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadSort
import app.jabs.torboxdrop.model.DownloadTab
import app.jabs.torboxdrop.model.QueuedDownload
import java.util.Locale

enum class DownloadStateCategory {
    DOWNLOADING,
    PROCESSING,
    STALLED,
    SEEDING,
    READY,
    QUEUED,
    PAUSED,
    FAILED,
    MISSING,
    EXPIRED,
    CACHED,
    UNKNOWN,
}

object TorBoxStateMapper {
    fun category(
        rawState: String?,
        downloadFinished: Boolean = false,
        downloadPresent: Boolean = false,
    ): DownloadStateCategory {
        if (downloadFinished && downloadPresent) return DownloadStateCategory.READY
        return when (normalize(rawState)) {
            "downloading", "download", "active", "forceddl" -> DownloadStateCategory.DOWNLOADING
            "metadata", "metadl", "checking", "checkingdl", "checkingresumedata", "processing",
            "queued_for_processing", "allocating", "moving",
            "completed", "finished" -> DownloadStateCategory.PROCESSING
            "stalled", "stalleddl", "stalledup", "no_seeds", "noseeds", "stalled_no_seeds" ->
                DownloadStateCategory.STALLED
            "uploading", "seeding", "seed", "forcedup", "checkingup" -> DownloadStateCategory.SEEDING
            "queued", "queueddl", "queuedup", "waiting", "pending" -> DownloadStateCategory.QUEUED
            "paused", "pauseddl", "pausedup", "stopped" -> DownloadStateCategory.PAUSED
            "failed", "error", "torrent_error", "failed_processing" -> DownloadStateCategory.FAILED
            "missing", "missingfiles", "not_present" -> DownloadStateCategory.MISSING
            "expired" -> DownloadStateCategory.EXPIRED
            "cached", "cached_ready" -> DownloadStateCategory.CACHED
            else -> DownloadStateCategory.UNKNOWN
        }
    }

    fun friendly(
        rawState: String?,
        downloadFinished: Boolean = false,
        downloadPresent: Boolean = false,
    ): String = when (category(rawState, downloadFinished, downloadPresent)) {
        DownloadStateCategory.DOWNLOADING -> "Downloading"
        DownloadStateCategory.PROCESSING -> when (normalize(rawState)) {
            "metadata", "metadl" -> "Fetching metadata"
            "checking", "checkingresumedata" -> "Checking files"
            else -> "Processing"
        }
        DownloadStateCategory.STALLED -> "Stalled · no seeds"
        DownloadStateCategory.SEEDING -> "Seeding"
        DownloadStateCategory.READY -> "Ready"
        DownloadStateCategory.QUEUED -> "Queued"
        DownloadStateCategory.PAUSED -> "Paused"
        DownloadStateCategory.FAILED ->
            if (normalize(rawState) == "failed_processing") "Processing failed" else "Failed"
        DownloadStateCategory.MISSING -> "Missing"
        DownloadStateCategory.EXPIRED -> "Expired"
        DownloadStateCategory.CACHED -> "Cached"
        DownloadStateCategory.UNKNOWN -> rawState
            ?.trim()
            ?.replace('_', ' ')
            ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            ?.takeIf(String::isNotBlank)
            ?: "Unknown"
    }

    fun isProblem(rawState: String?): Boolean = category(rawState) in setOf(
        DownloadStateCategory.STALLED,
        DownloadStateCategory.FAILED,
        DownloadStateCategory.MISSING,
        DownloadStateCategory.EXPIRED,
    )

    private fun normalize(value: String?): String = value
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.replace(Regex("[^a-z0-9]+"), "_")
        ?.trim('_')
        .orEmpty()
}

object DownloadLists {
    fun forTab(items: List<DownloadItem>, tab: DownloadTab): List<DownloadItem> = when (tab) {
        DownloadTab.ACTIVE -> items.filter { !it.isReady && !it.airLocked }
        DownloadTab.FINISHED -> items.filter { it.isReady && !it.airLocked }
        DownloadTab.AIRLOCK -> items.filter { it.airLocked }
        DownloadTab.QUEUE -> emptyList()
    }

    fun filterAndSort(
        items: List<DownloadItem>,
        search: String = "",
        filter: DownloadFilter = DownloadFilter(),
        sort: DownloadSort = DownloadSort.NEWEST,
        files: Collection<DownloadFile> = emptyList(),
        keysMatchingFiles: Set<String> = emptySet(),
    ): List<DownloadItem> {
        val terms = search.trim().lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)
        val fileNames = files.groupBy { it.downloadType to it.downloadId }
            .mapValues { (_, values) -> values.joinToString(" ") { "${it.name} ${it.path.orEmpty()}" }.lowercase(Locale.ROOT) }

        return items.asSequence()
            .filter { filter.type == null || it.type == filter.type }
            .filter { !filter.problemsOnly || it.isProblem || TorBoxStateMapper.isProblem(it.rawState) }
            .filter { !filter.cachedOnly || it.cached }
            .filter { !filter.taggedOnly || it.tags.isNotEmpty() }
            .filter { item ->
                if (terms.isEmpty()) return@filter true
                val haystack = buildString {
                    append(item.name.lowercase(Locale.ROOT))
                    append(' ')
                    append(item.tags.joinToString(" ").lowercase(Locale.ROOT))
                    append(' ')
                    append(fileNames[item.type to item.id].orEmpty())
                }
                terms.all(haystack::contains) || "${item.type.name}:${item.id}" in keysMatchingFiles
            }
            .toList()
            .sortedWith(comparator(sort))
    }

    fun filterQueue(items: List<QueuedDownload>, search: String = ""): List<QueuedDownload> {
        val query = search.trim()
        if (query.isEmpty()) return items
        return items.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.source?.contains(query, ignoreCase = true) == true
        }
    }

    private fun comparator(sort: DownloadSort): Comparator<DownloadItem> {
        val byName = compareBy<DownloadItem>({ it.name.lowercase(Locale.ROOT) }, DownloadItem::id)
        return when (sort) {
            DownloadSort.NAME -> byName
            DownloadSort.LARGEST -> compareByDescending<DownloadItem> { it.totalSize ?: Long.MIN_VALUE }
                .then(byName)
            DownloadSort.NEWEST -> instantComparator(descending = true).then(byName)
            DownloadSort.OLDEST -> instantComparator(descending = false).then(byName)
        }
    }

    private fun instantComparator(descending: Boolean): Comparator<DownloadItem> = Comparator { left, right ->
        val leftTime = left.createdAt ?: left.updatedAt
        val rightTime = right.createdAt ?: right.updatedAt
        when {
            leftTime == null && rightTime == null -> 0
            leftTime == null -> 1
            rightTime == null -> -1
            descending -> rightTime.compareTo(leftTime)
            else -> leftTime.compareTo(rightTime)
        }
    }
}
