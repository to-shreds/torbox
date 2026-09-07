package app.jabs.torboxdrop.util

import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.QueuedDownload

/** Keeps a server-accepted addition visible while TorBox's aggregate list catches up. */
object RecentAdditions {
    fun mergeDownloads(
        server: List<DownloadItem>,
        recentlyAdded: Collection<DownloadItem>,
    ): List<DownloadItem> = mergeByKey(server, recentlyAdded) { "${it.type.name}:${it.id}" }

    fun mergeQueue(
        server: List<QueuedDownload>,
        recentlyAdded: Collection<QueuedDownload>,
    ): List<QueuedDownload> = mergeByKey(server, recentlyAdded) { "${it.type.name}:${it.id}" }

    private fun <T> mergeByKey(
        server: List<T>,
        recentlyAdded: Collection<T>,
        key: (T) -> String,
    ): List<T> {
        val merged = LinkedHashMap<String, T>()
        server.forEach { merged[key(it)] = it }
        recentlyAdded.forEach { merged.putIfAbsent(key(it), it) }
        return merged.values.toList()
    }
}
