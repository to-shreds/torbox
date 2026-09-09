package app.jabs.torboxdrop.drive

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import app.jabs.torboxdrop.model.DownloadFile
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class DriveJournalTest {
    private lateinit var store: DriveStore
    private val context get() = RuntimeEnvironment.getApplication()
    private val item = DownloadItem("17", DownloadType.TORRENT, "Example", hash = "a".repeat(40))
    private val file = DownloadFile(2, "17", DownloadType.TORRENT, "example.txt")
    private val key get() = DriveStore.key("A", item.type, item.id)
    @Before fun setUp() { context.deleteDatabase("torbox_drive_automation.db"); store = DriveStore(context) }
    @After fun tearDown() { store.close() }
    private suspend fun arm() { store.armActive("A", item); store.ensureFileTransfers(key, listOf(file)) }

    @Test fun duplicateArmRetainsCompletedFilesAndWatch() = runBlocking {
        arm(); val transfer = store.fileTransfers(key).single()
        assertTrue(store.claimFileForSubmission(transfer.transferKey))
        store.markFileComplete(transfer.transferKey, 50)
        store.finishWatchIfTerminal(key)
        store.armActive("A", item)
        assertEquals(DriveFileState.COMPLETE, store.fileTransfers(key).single().state)
        assertEquals(DriveWatchState.COMPLETE, store.getWatch(key, "A")!!.state)
        assertFalse(store.hasRunnableWork("A"))
    }
    @Test fun sameTorrentIdInDifferentAccountsDoesNotOverwriteEitherJournal() = runBlocking {
        arm(); store.armActive("B", item)
        assertTrue(store.hasRunnableWork("A")); assertTrue(store.hasRunnableWork("B"))
        assertEquals(1, store.fileTransfers(key).size)
        assertEquals(1, store.recentWatches("A").size); assertEquals(1, store.recentWatches("B").size)
    }
    @Test fun overlappingClaimsProduceOneSubmissionClaim() = runBlocking {
        arm(); val transfer = store.fileTransfers(key).single()
        val winners = (1..20).map { async { store.claimFileForSubmission(transfer.transferKey) } }.awaitAll()
        assertEquals(1, winners.count { it }); assertEquals(1, store.fileTransfers(key).single().attempts)
    }
    @Test fun ambiguousTimeoutCannotBecomePending() = runBlocking {
        arm(); val id = store.fileTransfers(key).single().transferKey
        store.claimFileForSubmission(id, Instant.parse("2026-09-01T00:00:00Z"))
        store.markAmbiguousIfStale(id, Instant.parse("2026-09-02T00:00:00Z"))
        assertEquals(DriveFileState.UNCERTAIN, store.fileTransfers(key).single().state)
        assertFalse(store.claimFileForSubmission(id))
        assertEquals(DriveWatchState.NEEDS_REVIEW, store.finishWatchIfTerminal(key))
        assertFalse(store.hasRunnableWork("A"))
        assertEquals(1, store.runnableWatches("A", includeReview = true).size)
    }
    @Test fun completeCannotBeRolledBackByPendingOrFailedJob() = runBlocking {
        arm(); val id = store.fileTransfers(key).single().transferKey
        store.claimFileForSubmission(id); store.markFileComplete(id, 44)
        store.markFileSubmitted(id); store.markFileFailed(id, "late error", 44)
        val result = store.fileTransfers(key).single()
        assertEquals(DriveFileState.COMPLETE, result.state); assertEquals(44L, result.jobId)
    }
    @Test fun submittedJobIdSurvivesLaterNoIdAcknowledgment() = runBlocking {
        arm(); val id = store.fileTransfers(key).single().transferKey
        store.claimFileForSubmission(id); store.markFileSubmitted(id, 31); store.markFileSubmitted(id)
        assertEquals(31L, store.fileTransfers(key).single().jobId)
    }
    @Test fun restartPreservesAttemptAndBaselineIds() = runBlocking {
        arm(); val id = store.fileTransfers(key).single().transferKey
        store.claimFileForSubmission(id, Instant.now(), setOf(2L, 4L))
        store.close(); store = DriveStore(context)
        val restored = store.fileTransfers(key).single()
        assertEquals(DriveFileState.SUBMITTING, restored.state)
        assertEquals(setOf(2L, 4L), restored.priorJobIds); assertFalse(store.claimFileForSubmission(id))
    }
    @Test fun newlyInfectedMetadataBlocksAnExistingPendingFile() = runBlocking {
        arm(); store.ensureFileTransfers(key, listOf(file.copy(infected = true)))
        val transfer = store.fileTransfers(key).single()
        assertEquals(DriveFileState.FAILED, transfer.state)
        assertFalse(store.claimFileForSubmission(transfer.transferKey))
    }
    @Test fun definitiveRejectionHonorsRetryTime() = runBlocking {
        arm(); val id = store.fileTransfers(key).single().transferKey
        val now = Instant.now(); store.claimFileForSubmission(id, now)
        store.releaseRejected(id, now.plusSeconds(120))
        assertFalse(store.claimFileForSubmission(id, now.plusSeconds(119)))
        assertTrue(store.claimFileForSubmission(id, now.plusSeconds(120)))
    }
    @Test fun queueCollisionKeepsTheAlreadyCompletedActiveWatch() = runBlocking {
        arm(); val id = store.fileTransfers(key).single().transferKey
        store.claimFileForSubmission(id); store.markFileComplete(id, 12); store.finishWatchIfTerminal(key)
        store.armQueued("A", item.type, "q1", item.name, item.hash, null)
        val queued = store.getWatch(DriveStore.queuedKey("A", item.type, "q1"), "A")!!
        assertTrue(store.rebindQueued(queued, item))
        assertEquals(1, store.recentWatches("A").size)
        assertEquals(DriveWatchState.COMPLETE, store.getWatch(key, "A")!!.state)
    }
    @Test fun stopOnlyUnsentFilesPreservesSubmittedJobs() = runBlocking {
        arm(); store.ensureFileTransfers(key, listOf(file.copy(id = 3)))
        val first = store.fileTransfers(key).first(); store.claimFileForSubmission(first.transferKey)
        store.markFileSubmitted(first.transferKey, 4)
        store.stopUnsubmitted("A")
        assertEquals(listOf(DriveFileState.SUBMITTED, DriveFileState.FAILED), store.fileTransfers(key).map { it.state })
    }
    @Test fun v1MigrationPreservesOldAmbiguousJournalWithoutReplay() = runBlocking {
        store.close(); context.deleteDatabase("torbox_drive_automation.db")
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("torbox_drive_automation.db"), null)
        db.execSQL("CREATE TABLE drive_watches(watch_key TEXT PRIMARY KEY NOT NULL, account_scope TEXT NOT NULL, item_id TEXT NOT NULL, item_type TEXT NOT NULL, name TEXT NOT NULL, armed_at INTEGER NOT NULL, has_been_seen INTEGER NOT NULL, consecutive_misses INTEGER NOT NULL, queue_id TEXT, source_hash TEXT, source_value TEXT, state TEXT NOT NULL, last_error TEXT)")
        db.execSQL("CREATE TABLE drive_files(transfer_key TEXT PRIMARY KEY NOT NULL, watch_key TEXT NOT NULL, file_id INTEGER NOT NULL, file_name TEXT NOT NULL, state TEXT NOT NULL, job_id INTEGER, attempts INTEGER NOT NULL, last_attempt_at INTEGER, last_error TEXT)")
        db.execSQL("INSERT INTO drive_watches VALUES('TORRENT:17','A','17','TORRENT','Example',0,1,0,NULL,NULL,NULL,'TRANSFERRING',NULL)")
        db.execSQL("INSERT INTO drive_files VALUES('TORRENT:17:2','TORRENT:17',2,'example.txt','SUBMITTING',NULL,1,1,NULL)")
        db.version = 1; db.close(); store = DriveStore(context)
        val restored = store.fileTransfers(key).single()
        assertEquals("$key:2", restored.transferKey); assertEquals(DriveFileState.SUBMITTING, restored.state)
        assertFalse(store.claimFileForSubmission(restored.transferKey))
    }
}
