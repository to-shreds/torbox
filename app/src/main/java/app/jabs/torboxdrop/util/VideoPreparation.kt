package app.jabs.torboxdrop.util

import app.jabs.torboxdrop.model.DownloadItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

object VideoPreparation {
    /** Poll only the identity returned by create; never retry a create or guess a file ID. */
    suspend fun awaitReady(
        fetch: suspend () -> DownloadItem?,
        onStatus: (DownloadItem?) -> Unit = {},
        intervalMillis: Long = 5_000,
        timeoutMillis: Long = 600_000,
    ): DownloadItem {
        val ready = withTimeoutOrNull(timeoutMillis) {
            var item: DownloadItem?
            do {
                item = fetch()
                onStatus(item)
                if (item?.isReady == true) return@withTimeoutOrNull item
                if (item != null && (item.isProblem || item.rawState.lowercase() in setOf("inactive", "incomplete"))) {
                    error("TorBox could not prepare this video: ${item.error ?: item.friendlyState}. Check the existing item before retrying; it was not added again.")
                }
                delay(intervalMillis)
            } while (true)
            @Suppress("UNREACHABLE_CODE")
            null
        }
        return ready ?: error("TorBox has not made the video ready after 10 minutes. Check its status in Downloads or the TorBox dashboard. The existing item was not deleted or added again.")
    }
}
