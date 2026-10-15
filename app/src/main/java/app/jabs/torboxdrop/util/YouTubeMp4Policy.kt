package app.jabs.torboxdrop.util

import app.jabs.torboxdrop.model.DownloadFile
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import java.util.Locale

/**
 * Opt-in MP4 conversion is strictly for single-video YouTube web downloads.
 * It is not a new default for torrents, arbitrary web links, or normal Download.
 */
object YouTubeMp4Policy {
    fun canonicalSource(value: String?): String? = value?.let {
        runCatching { YouTubeLinks.canonicalVideoUrl(it) }.getOrNull()
    }

    fun isYouTube(item: DownloadItem): Boolean =
        item.type == DownloadType.WEB && canonicalSource(item.originalSource) != null

    fun isSupportedVideo(file: DownloadFile): Boolean {
        val filename = file.name.lowercase(Locale.ROOT)
        return filename.endsWith(".webm") || filename.endsWith(".mp4")
    }

    fun eligible(item: DownloadItem, file: DownloadFile): Boolean =
        item.isReady &&
            isYouTube(item) &&
            !file.infected &&
            file.downloadType == DownloadType.WEB &&
            file.downloadId == item.id &&
            file.id >= 0 &&
            isSupportedVideo(file)

    fun outputFilename(sourceName: String): String {
        val leaf = sourceName.substringAfterLast('/').substringAfterLast('\\')
        val stem = leaf.substringBeforeLast('.', leaf)
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cc}]"), "_")
            .trim().trim('.').take(140).ifBlank { "YouTube video" }
        return stem + ".mp4"
    }
}
