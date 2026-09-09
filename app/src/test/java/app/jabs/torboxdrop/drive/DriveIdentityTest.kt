package app.jabs.torboxdrop.drive

import app.jabs.torboxdrop.util.BencodeTorrentValidator
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import java.security.MessageDigest
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class DriveIdentityTest {
    @Test fun staleRejectionCannotStopReplacementAccount() {
        val scheduler = app.jabs.torboxdrop.notifications.CompletionMonitorScheduler
        assertTrue(scheduler.rejectionAppliesToCurrentAccount("same", "same"))
        assertFalse(scheduler.rejectionAppliesToCurrentAccount("old", "new"))
        assertFalse(scheduler.rejectionAppliesToCurrentAccount(null, null))
        assertFalse(scheduler.rejectionAppliesToCurrentAccount("old", null))
    }

    private val hash = "a".repeat(40)
    @Test fun magnetHexAndBase32AreCanonical() {
        assertEquals(hash, DriveTorrentIdentity.fromMagnet("magnet:?xt=urn:btih:${hash.uppercase()}"))
        assertEquals("0".repeat(40), DriveTorrentIdentity.fromMagnet("magnet:?xt=urn:btih:${"A".repeat(32)}"))
    }
    @Test fun magnetV2AndHybridUseCorrectDigestForms() {
        assertEquals("b".repeat(64), DriveTorrentIdentity.fromMagnet("magnet:?xt=urn:btmh:1220${"b".repeat(64)}"))
        assertEquals(hash, DriveTorrentIdentity.fromMagnet("magnet:?xt=urn:btmh:1220${"b".repeat(64)}&xt=urn:btih:$hash"))
        assertNull(DriveTorrentIdentity.fromMagnet("https://example.com/?xt=urn:btih:$hash"))
        assertNull(DriveTorrentIdentity.fromMagnet("magnet:?xt=urn:btih:$hash&xt=urn:btih:${"c".repeat(40)}"))
    }
    @Test fun torrentInfoHashUsesOriginalBytesNotTopLevelMetadata() {
        val info = "d4:name1:x6:pieces0:e"
        val expected = MessageDigest.getInstance("SHA-1").digest(info.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expected, BencodeTorrentValidator.infoHash("d4:info${info}e".toByteArray()))
        assertEquals(expected, BencodeTorrentValidator.infoHash("d7:comment4:test4:info${info}e".toByteArray()))
    }
    @Test fun pureV2UsesSha256AndHybridUsesSha1() {
        val v2 = "d12:meta versioni2e4:name1:xe"
        assertEquals(64, BencodeTorrentValidator.infoHash("d4:info${v2}e".toByteArray()).length)
        val hybrid = "d12:meta versioni2e4:name1:x6:pieces0:e"
        assertEquals(40, BencodeTorrentValidator.infoHash("d4:info${hybrid}e".toByteArray()).length)
    }
    @Test fun queueNeverFallsBackToSimilarNameOrAmbiguousHash() {
        val now = Instant.now()
        val watch = DriveWatch("q", "scope", "q", DownloadType.TORRENT, "same", now, queueId = "q",
            sourceHash = hash, sourceValue = "same.torrent")
        val wrong = DownloadItem("1", DownloadType.TORRENT, "same", hash = "b".repeat(40), createdAt = now)
        assertNull(DriveQueuedMatcher.findActivated(watch, listOf(wrong)))
        val right = wrong.copy(hash = hash)
        assertEquals(right, DriveQueuedMatcher.findActivated(watch, listOf(right)))
        assertNull(DriveQueuedMatcher.findActivated(watch, listOf(right, right.copy(id = "2"))))
        assertNull(DriveQueuedMatcher.findActivated(watch.copy(sourceHash = null), listOf(wrong)))
    }
    @Test fun jobMatchRejectsWrongFileHashKindServiceZipAndOldAttempt() {
        val now = Instant.now()
        val transfer = DriveFileTransfer("t", "w", 2, "file", DriveFileState.SUBMITTED, attempts = 1, baselineCaptured = true,
            lastAttemptAt = now, priorJobIds = setOf(7))
        val good = TorBoxIntegrationJob(8, 2, hash, "google_drive", 1.0, "completed", "torrent", null, now, now)
        assertEquals(good, DriveJobMatcher.find(transfer, hash, listOf(good)))
        assertNull(DriveJobMatcher.find(transfer.copy(baselineCaptured = false), hash, listOf(good)))
        for (bad in listOf(good.copy(fileId = 3), good.copy(hash = "b".repeat(40)), good.copy(type = "webdownload"),
            good.copy(integration = "googledrive-evil"), good.copy(zip = true), good.copy(id = 7),
            good.copy(createdAt = now.minusSeconds(121)), good.copy(createdAt = null))) {
            assertNull(DriveJobMatcher.find(transfer, hash, listOf(bad)))
        }
        assertNull(DriveJobMatcher.find(transfer, hash, listOf(good, good.copy(id = 9))))
    }
}
