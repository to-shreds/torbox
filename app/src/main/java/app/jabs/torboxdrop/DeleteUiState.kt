package app.jabs.torboxdrop

import app.jabs.torboxdrop.model.DownloadItem

/** Remove only the server-confirmed target, not another item selected while deletion was running. */
internal fun MainUiState.withDeletedDownload(item: DownloadItem): MainUiState {
    val key = "${item.type.name}:${item.id}"
    fun DownloadItem.matches() = type == item.type && id == item.id
    return copy(
        selectedDownload = selectedDownload?.takeUnless { it.matches() },
        fileSheet = fileSheet?.takeUnless { it.download.matches() },
        downloads = downloads.copy(downloads = downloads.downloads.filterNot { it.matches() }),
        loadedFiles = loadedFiles - key,
        cachedFileMatchKeys = cachedFileMatchKeys - key,
        watchedKeys = watchedKeys - key,
        deleteError = null,
    )
}
