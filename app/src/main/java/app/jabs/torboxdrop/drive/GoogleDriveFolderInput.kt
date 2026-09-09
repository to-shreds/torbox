package app.jabs.torboxdrop.drive

import java.net.URI

sealed interface GoogleDriveFolderSelection {
    data object Root : GoogleDriveFolderSelection
    data class Folder(val id: String) : GoogleDriveFolderSelection
}

object GoogleDriveFolderInput {
    fun parse(raw: String): GoogleDriveFolderSelection? {
        val value = raw.trim()
        if (value.isEmpty()) return GoogleDriveFolderSelection.Root

        val candidate = if (value.startsWith("http://") || value.startsWith("https://")) {
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true) ||
                !uri.host.equals("drive.google.com", ignoreCase = true)
            ) return null
            val segments = uri.path.orEmpty().split('/').filter(String::isNotBlank)
            val folderIndex = segments.indexOf("folders")
            segments.getOrNull(folderIndex + 1) ?: return null
        } else {
            value
        }

        return candidate.takeIf(::isPlausibleFolderId)?.let(GoogleDriveFolderSelection::Folder)
    }

    private fun isPlausibleFolderId(value: String): Boolean =
        value.length in 10..256 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
