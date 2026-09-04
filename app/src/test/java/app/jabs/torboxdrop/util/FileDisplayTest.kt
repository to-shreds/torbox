package app.jabs.torboxdrop.util

import app.jabs.torboxdrop.model.DownloadFile
import app.jabs.torboxdrop.model.DownloadType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileDisplayTest {
    @Test
    fun inferredMimeType_prefersSpecificServerMime() {
        val file = file("movie.unknown", "Video/MP4; charset=binary")

        assertEquals("video/mp4", file.inferredMimeType())
    }

    @Test
    fun inferredMimeType_usesExtensionForMissingOrGenericMime() {
        assertEquals("video/x-matroska", file("movie.MKV").inferredMimeType())
        assertEquals("application/x-subrip", file("captions.srt", "application/octet-stream").inferredMimeType())
        assertEquals("application/octet-stream", file("README").inferredMimeType())
    }

    @Test
    fun middleEllipsis_preservesExtensionAndExactLimit() {
        val result = middleEllipsizeFilename("An exceptionally long movie filename.mkv", 20)

        assertEquals("An excep…ilename.mkv", result)
        assertEquals(20, result.length)
        assertTrue(result.endsWith(".mkv"))
    }

    @Test
    fun middleEllipsis_preservesCompoundArchiveExtension() {
        val result = middleEllipsizeFilename("some-very-long-backup-name.tar.gz", 18)

        assertTrue(result.endsWith(".tar.gz"))
        assertEquals(18, result.length)
    }

    @Test
    fun middleEllipsis_handlesTinyLimitsAndPaths() {
        assertTrue(middleEllipsizeFilename("abcdef.txt", 0).isEmpty())
        assertEquals("…", middleEllipsizeFilename("abcdef.txt", 1))
        assertEquals("file.txt", middleEllipsizeFilename("folder/sub/file.txt", 12))
    }

    @Test
    fun middleEllipsis_neverSplitsEmojiSurrogatePair() {
        val result = middleEllipsizeFilename("abcdef😀ghijklmnop.mkv", 14)

        assertFalse(result.any { it.isHighSurrogate() } xor result.any { it.isLowSurrogate() })
        assertTrue(result.length <= 14)
        assertTrue(result.endsWith(".mkv"))
    }

    private fun file(name: String, mime: String? = null) = DownloadFile(
        id = 1,
        downloadId = "download",
        downloadType = DownloadType.TORRENT,
        name = name,
        mimeType = mime,
    )
}
