package app.jabs.torboxdrop.util

import app.jabs.torboxdrop.model.DownloadType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InputParserTest {
    @Test
    fun extractLinks_findsSupportedLinksAndTrimsSentencePunctuation() {
        val text = "Try (https://example.com/a_(film)). Then magnet:?xt=urn:btih:ABC123&dn=Demo!"

        val links = InputParser.extractLinks(text)

        assertEquals(listOf(
            "https://example.com/a_(film)",
            "magnet:?xt=urn:btih:ABC123&dn=Demo",
        ), links.map { it.value })
        assertEquals(listOf(
            DownloadType.WEB,
            DownloadType.TORRENT,
        ), links.map { it.downloadType })
    }

    @Test
    fun extractLinks_rejectsUnsafeAndUnrelatedSchemes() {
        val text = "javascript:https://evil.example file:///tmp/a content://provider/a ftp://example.com/a"

        assertTrue(InputParser.extractLinks(text).isEmpty())
        assertNull(InputParser.classify("https://user:pass@example.com/file"))
        assertNull(InputParser.classify("intent://example.com/#Intent;scheme=https;end"))
    }

    @Test
    fun extractLinks_deduplicatesAndRejectsMalformedUrls() {
        val text = "https://example.com/a https://example.com/a https:///missing-host"

        assertEquals(listOf("https://example.com/a"), InputParser.extractLinks(text).map { it.value })
    }

    @Test
    fun magnet_requiresAQuery() {
        assertTrue(InputParser.isMagnet("MAGNET:?xt=urn:btih:abcdef"))
        assertFalse(InputParser.isMagnet("magnet:"))
        assertFalse(InputParser.isMagnet("magnet:?"))
    }

    @Test
    fun torrentFile_recognizesExtensionAndKnownMimeTypesOnly() {
        assertTrue(InputParser.isTorrentFile("Linux.ISO.TORRENT", null))
        assertTrue(InputParser.isTorrentFile("download", "application/x-bittorrent; charset=binary"))
        assertFalse(InputParser.isTorrentFile("Linux.torrent.exe", "application/octet-stream"))
        assertFalse(InputParser.isTorrentFile(null, "text/plain"))
    }
}
