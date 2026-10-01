package app.jabs.torboxdrop.util

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.util.UUID

/** Only Android's download service receives storage links, never the embedded browser. */
object DeviceDownloads {
    fun safeName(value: String, suffix: String = UUID.randomUUID().toString().take(8)): String {
        val name = value.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[:*?\"<>|\\p{Cc}]"), "_").trim().trim('.').ifBlank { "torbox-download" }
        val dot = name.lastIndexOf('.')
        val extension = if (dot in 1 until name.lastIndex && name.length - dot <= 20) name.substring(dot) else ""
        val base = if (extension.isEmpty()) name else name.substring(0, dot)
        // UTF-8 filenames are limited by bytes, not Kotlin characters.
        var shortBase = base.take(120)
        while (shortBase.toByteArray(Charsets.UTF_8).size > 160) shortBase = shortBase.dropLast(1)
        return "$shortBase-$suffix$extension"
    }

    fun enqueue(context: Context, url: String, name: String, mime: String?, headers: Map<String, String>): Long {
        val uri = Uri.parse(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank())
        val destination = safeName(name)
        val request = DownloadManager.Request(uri)
            .setTitle(name.substringAfterLast('/').take(180))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        mime?.takeIf { it.contains('/') }?.let(request::setMimeType)
        headers.forEach { (key, value) ->
            require(key.matches(Regex("[A-Za-z0-9-]{1,64}")))
            require(value.length <= 8_192 && '\r' !in value && '\n' !in value)
            request.addRequestHeader(key, value)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, destination)
        } else {
            request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, destination)
        }
        return context.getSystemService(DownloadManager::class.java).enqueue(request).also { require(it >= 0) }
    }

    data class Status(val message: String, val running: Boolean, val failed: Boolean = false)

    fun status(context: Context, id: Long): Status =
        context.getSystemService(DownloadManager::class.java).query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return@use Status("Download removed from Android Downloads.", false)
            val state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val bytes = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            when (state) {
                DownloadManager.STATUS_SUCCESSFUL -> Status("Saved to your device. Open Android Downloads to find it.", false)
                DownloadManager.STATUS_FAILED -> Status(failureMessage(reason), false, true)
                DownloadManager.STATUS_PAUSED -> Status("Download waiting for network or Android retry. Open Android Downloads for details.", true)
                DownloadManager.STATUS_RUNNING -> Status(if (total > 0) "Downloading to device: ${(bytes.toDouble() / total * 100).toInt().coerceIn(0, 100)}%" else "Downloading to device…", true)
                else -> Status("Queued in Android Downloads. Waiting to start…", true)
            }
        }

    fun failureMessage(reason: Int): String = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Download failed: your device is out of storage. Free some space and retry."
        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Download failed: device storage is unavailable."
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "Download failed: that filename already exists. Tap Download to try a new name."
        401, 403, 404, 410 -> "Download link was rejected or expired. Tap Download to request a fresh link."
        else -> "Android download failed (code $reason). Check your connection and tap Download to retry."
    }
}
