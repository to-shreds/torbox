package app.jabs.torboxdrop.util

import app.jabs.torboxdrop.model.DownloadFile
import java.util.Locale

private val MIME_BY_EXTENSION = mapOf(
    "3gp" to "video/3gpp",
    "7z" to "application/x-7z-compressed",
    "aac" to "audio/aac",
    "apk" to "application/vnd.android.package-archive",
    "ass" to "text/x-ssa",
    "avi" to "video/x-msvideo",
    "bmp" to "image/bmp",
    "csv" to "text/csv",
    "doc" to "application/msword",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "epub" to "application/epub+zip",
    "flac" to "audio/flac",
    "gif" to "image/gif",
    "gz" to "application/gzip",
    "html" to "text/html",
    "htm" to "text/html",
    "jpeg" to "image/jpeg",
    "jpg" to "image/jpeg",
    "json" to "application/json",
    "m4a" to "audio/mp4",
    "m4v" to "video/mp4",
    "mkv" to "video/x-matroska",
    "mov" to "video/quicktime",
    "mp3" to "audio/mpeg",
    "mp4" to "video/mp4",
    "mpeg" to "video/mpeg",
    "mpg" to "video/mpeg",
    "ogg" to "audio/ogg",
    "ogv" to "video/ogg",
    "opus" to "audio/opus",
    "pdf" to "application/pdf",
    "png" to "image/png",
    "ppt" to "application/vnd.ms-powerpoint",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "rar" to "application/vnd.rar",
    "srt" to "application/x-subrip",
    "svg" to "image/svg+xml",
    "tar" to "application/x-tar",
    "torrent" to "application/x-bittorrent",
    "ts" to "video/mp2t",
    "txt" to "text/plain",
    "wav" to "audio/wav",
    "webm" to "video/webm",
    "webp" to "image/webp",
    "xls" to "application/vnd.ms-excel",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "xml" to "application/xml",
    "zip" to "application/zip",
)

fun DownloadFile.inferredMimeType(): String {
    val supplied = mimeType
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.takeIf { it.contains('/') && it != "application/octet-stream" }
    if (supplied != null) return supplied

    val candidate = name.ifBlank { path.orEmpty() }
    val extension = candidate
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('.', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)
    return MIME_BY_EXTENSION[extension] ?: "application/octet-stream"
}

/**
 * Ellipsizes the middle of a filename while retaining its final extension whenever it fits.
 * The returned value never exceeds [maxChars].
 */
fun middleEllipsizeFilename(filename: String, maxChars: Int, ellipsis: String = "…"): String {
    require(maxChars >= 0) { "maxChars must not be negative" }
    if (filename.length <= maxChars) return filename
    if (maxChars == 0) return ""
    if (ellipsis.length >= maxChars) return ellipsis.take(maxChars)

    val separatorIndex = maxOf(filename.lastIndexOf('/'), filename.lastIndexOf('\\'))
    val leaf = filename.substring(separatorIndex + 1)
    if (leaf.length <= maxChars) return leaf

    val extensionStart = findExtensionStart(leaf)
    val extension = if (extensionStart > 0) leaf.substring(extensionStart) else ""
    val stem = if (extensionStart > 0) leaf.substring(0, extensionStart) else leaf
    val availableForStem = maxChars - ellipsis.length - extension.length

    if (extension.isNotEmpty() && availableForStem >= 2) {
        val leading = (availableForStem + 1) / 2
        val trailing = availableForStem / 2
        return stem.safeTake(leading) + ellipsis + stem.safeTakeLast(trailing) + extension
    }

    val remaining = maxChars - ellipsis.length
    val leading = (remaining + 1) / 2
    val trailing = remaining / 2
    return leaf.safeTake(leading) + ellipsis + leaf.safeTakeLast(trailing)
}

private fun findExtensionStart(filename: String): Int {
    val lower = filename.lowercase(Locale.ROOT)
    val compound = listOf(".tar.gz", ".tar.bz2", ".tar.xz")
        .firstOrNull(lower::endsWith)
    if (compound != null) return filename.length - compound.length

    val dot = filename.lastIndexOf('.')
    // Dotfiles have no filename stem, and exceptionally long suffixes are not useful extensions.
    return dot.takeIf { it in 1 until filename.lastIndex && filename.length - it <= 16 } ?: -1
}

private fun String.safeTake(count: Int): String {
    val result = take(count)
    return if (result.lastOrNull()?.isHighSurrogate() == true) result.dropLast(1) else result
}

private fun String.safeTakeLast(count: Int): String {
    val result = takeLast(count)
    return if (result.firstOrNull()?.isLowSurrogate() == true) result.drop(1) else result
}
