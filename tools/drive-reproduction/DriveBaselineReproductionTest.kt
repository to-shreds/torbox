package app.jabs.torboxdrop.drive

import android.app.Application
import app.jabs.torboxdrop.model.DownloadFile
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Historical reproducer ONLY for unmodified source ref 5a4d46b, not a release acceptance suite. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class DriveBaselineReproductionTest {
    private lateinit var store: DriveStore
    private val item = DownloadItem("17", DownloadType.TORRENT, "same")
    private val file = DownloadFile(2, "17", DownloadType.TORRENT, "same.txt")
    private val key get() = DriveStore.key(item.type, item.id)
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("torbox_drive_automation.db"); store = DriveStore(context)
    }
    @After fun cleanup() { store.close() }
    private suspend fun arm() { store.armActive("A", item); store.ensureFileTransfers(key, listOf(file)) }
    @Test fun reproducesDuplicateArmDestroyingCompletedJournal() = runBlocking {
        arm(); val id = store.fileTransfers(key).single().transferKey
        store.claimFileForSubmission(id); store.markFileComplete(id, 3); store.finishWatchIfTerminal(key)
        store.armActive("A", item)
        assertTrue(store.fileTransfers(key).isEmpty()); assertTrue(store.hasRunnableWork("A"))
    }
    @Test fun reproducesDifferentAccountOverwritingSameTorrentId() = runBlocking {
        arm(); store.armActive("B", item)
        assertFalse(store.hasRunnableWork("A")); assertTrue(store.hasRunnableWork("B"))
    }
    @Test fun reproducesAutomaticReplayAfterAmbiguousTimeout() = runBlocking {
        arm(); val id = store.fileTransfers(key).single().transferKey
        store.claimFileForSubmission(id, Instant.EPOCH); store.markFileSubmitted(id)
        assertTrue(store.retryAmbiguousIfStale(id, Instant.now(), 2))
        assertEquals(DriveFileState.PENDING, store.fileTransfers(key).single().state)
    }
    @Test fun reproducesTerminalCompletionRegressingToSubmitted() = runBlocking {
        arm(); val id = store.fileTransfers(key).single().transferKey
        store.claimFileForSubmission(id); store.markFileComplete(id, 3); store.markFileSubmitted(id)
        assertEquals(DriveFileState.SUBMITTED, store.fileTransfers(key).single().state)
    }
    @Test fun reproducesFilenameOnlyCorrelationToUnrelatedTorrent() {
        val now = Instant.now()
        val watch = DriveWatch("q", "A", "q", DownloadType.TORRENT, "same", now,
            queueId = "q", sourceValue = "upload.torrent")
        assertNotNull(DriveQueuedMatcher.findActivated(watch, listOf(item.copy(createdAt = now, hash = "b".repeat(40)))))
    }
}
