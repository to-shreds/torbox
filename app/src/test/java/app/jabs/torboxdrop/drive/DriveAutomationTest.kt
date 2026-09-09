package app.jabs.torboxdrop.drive

import android.app.Application
import app.jabs.torboxdrop.data.RepositorySnapshot
import app.jabs.torboxdrop.data.TorBoxDriveGateway
import app.jabs.torboxdrop.data.TorBoxBadTokenException
import app.jabs.torboxdrop.data.TorBoxRateLimitException
import app.jabs.torboxdrop.model.DownloadFile
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import app.jabs.torboxdrop.notifications.AccountSensitiveWorkGate
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
class DriveAutomationTest {
    private lateinit var store: DriveStore
    private lateinit var google: MockWebServer
    private lateinit var gateway: FakeGateway
    private lateinit var source: FakeSource
    private var token = "account-A"
    private var clock = Instant.now()
    private var connected = true
    private var authorize: suspend () -> GoogleDriveAuthorizationResult = { GoogleDriveAuthorizationResult.Authorized("google-test-secret") }
    private val scope get() = driveAccountScope(token)!!
    private val hash = "a".repeat(40)
    private val item get() = DownloadItem("17", DownloadType.TORRENT, "Example", hash = hash,
        downloadFinished = true, downloadPresent = true, fileCount = 1, createdAt = clock)
    private val key get() = DriveStore.key(scope, DownloadType.TORRENT, "17")
    private val file = DownloadFile(2, "17", DownloadType.TORRENT, "example.txt", size = 123)
    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("torbox_drive_automation.db"); store = DriveStore(context)
        token = "account-A"; connected = true; clock = Instant.now()
        source = FakeSource(item, listOf(file)); gateway = FakeGateway()
        google = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody(
                    """{"id":"dest","name":"Destination","mimeType":"application/vnd.google-apps.folder","trashed":false,"capabilities":{"canAddChildren":true}}""")
            }; start()
        }
    }
    @After fun tearDown() { store.close(); google.shutdown() }
    private fun runner() = DriveAutomationRunner(source, gateway, store, GoogleDriveAuthorizer { authorize() },
        GoogleDriveApiClient(OkHttpClient(), google.url("/drive/v3/")), { token }, { "dest" }, { connected },
        onGoogleAuthorizationRequired = { connected = false }, now = { clock })
    private suspend fun arm() = store.armActive(scope, item)
    private suspend fun files() = store.fileTransfers(key)

    @Test fun readyTorrentQueuesIndividualFilesAndNeverMarksQueueAcknowledgmentComplete() = runBlocking {
        source.item = item.copy(fileCount = 2); source.files = listOf(file, file.copy(id = 3))
        arm(); val result = runner().runOnce()
        assertEquals(2, result.queuedFiles); assertEquals(listOf(2L, 3L), gateway.sent)
        assertTrue(files().all { it.state == DriveFileState.SUBMITTED })
        assertEquals(listOf("dest"), gateway.folders)
    }
    @Test fun progressOrCompletedLabelIsNotAuthoritativeReadiness() = runBlocking {
        source.item = item.copy(downloadFinished = false, progress = 1.0, rawState = "completed")
        arm(); runner().runOnce(); assertTrue(gateway.sent.isEmpty()); assertEquals(0, google.requestCount)
    }
    @Test fun finishedButNotPresentWaits() = runBlocking {
        source.item = item.copy(downloadPresent = false)
        arm(); runner().runOnce(); assertTrue(gateway.sent.isEmpty())
        source.item = item; runner().runOnce(); assertEquals(1, gateway.sent.size)
    }
    @Test fun partialFileListDoesNotSilentlyFinishPartialTorrent() = runBlocking {
        source.item = item.copy(fileCount = 2)
        arm(); runner().runOnce(); assertTrue(gateway.sent.isEmpty())
    }
    @Test fun infectedAndOverLimitFilesAreNotSubmitted() = runBlocking {
        source.item = item.copy(fileCount = 2)
        source.files = listOf(file.copy(infected = true), file.copy(id = 3, size = 100_000_000_001L))
        arm(); runner().runOnce(); assertTrue(gateway.sent.isEmpty())
        assertTrue(files().all { it.state == DriveFileState.FAILED })
    }
    @Test fun repeatPassAndDuplicateAddDoNotSubmitAgain() = runBlocking {
        arm(); runner().runOnce(); store.armActive(scope, item)
        repeat(3) { runner().runOnce() }; assertEquals(1, gateway.sent.size)
    }
    @Test fun failedBaselineLookupCannotAuthorizeNewSubmission() = runBlocking {
        gateway.lookupFails = true; arm(); val result = runner().runOnce()
        assertEquals(1, result.retryableFailures); assertTrue(gateway.sent.isEmpty())
    }
    @Test fun lostResponseWithVisibleJobIsReconciledNotResent() = runBlocking {
        gateway.dropResponse = true; arm(); runner().runOnce(); runner().runOnce()
        assertEquals(1, gateway.sent.size); assertNotNull(files().single().jobId)
    }
    @Test fun lostResponseWithoutJobBecomesReviewRequiredNeverAutomaticRetry() = runBlocking {
        gateway.dropResponse = true; gateway.makeJobs = false
        arm(); runner().runOnce(); clock = clock.plusSeconds(660)
        gateway.lookupFails = true; runner().runOnce()
        assertEquals(DriveFileState.UNCERTAIN, files().single().state)
        assertEquals(DriveWatchState.NEEDS_REVIEW, store.getWatch(key, scope)!!.state)
        repeat(4) { runner().runOnce(includeReview = true) }
        assertEquals(1, gateway.sent.size)
    }
    @Test fun lateJobCanResolveUnconfirmedRequestWithoutASecondPost() = runBlocking {
        gateway.makeJobs = false; arm(); runner().runOnce()
        val attempt = files().single().lastAttemptAt!!
        clock = clock.plusSeconds(660); runner().runOnce()
        gateway.jobs += gateway.job(99, 2).copy(status = "completed", createdAt = attempt)
        runner().runOnce(includeReview = true)
        assertEquals(DriveFileState.COMPLETE, files().single().state); assertEquals(1, gateway.sent.size)
    }
    @Test fun oldJobInBaselineCannotFalselyCompleteNewSubmission() = runBlocking {
        gateway.jobs += gateway.job(11, 2).copy(status = "completed")
        gateway.makeJobs = false; arm(); runner().runOnce()
        assertNull(files().single().jobId); assertEquals(DriveFileState.SUBMITTED, files().single().state)
    }
    @Test fun multipleNewJobsAreAmbiguousRatherThanPickingTheLatest() = runBlocking {
        gateway.makeJobs = false; arm(); runner().runOnce()
        gateway.jobs += gateway.job(11, 2); gateway.jobs += gateway.job(12, 2)
        clock = clock.plusSeconds(660); runner().runOnce()
        assertEquals(DriveFileState.UNCERTAIN, files().single().state); assertNull(files().single().jobId)
    }
    @Test fun knownJobFinishesAfterSourceRemovalWithoutNewGooglePermissionOrFolderWrite() = runBlocking {
        arm(); runner().runOnce(); val folderWrites = gateway.folders.size
        source.item = null; connected = false
        authorize = { error("Google authorization must not be requested for job reconciliation") }
        gateway.jobs[0] = gateway.jobs[0].copy(status = "completed")
        runner().runOnce()
        assertEquals(DriveFileState.COMPLETE, files().single().state)
        assertEquals(folderWrites, gateway.folders.size); assertEquals(1, gateway.sent.size)
    }
    @Test fun authoritativeRateLimitRetriesAfterItsDelayOnly() = runBlocking {
        gateway.rateLimitOnce = true; arm(); runner().runOnce()
        assertEquals(DriveFileState.PENDING, files().single().state)
        clock = clock.plusSeconds(119); runner().runOnce(); assertTrue(gateway.sent.isEmpty())
        clock = clock.plusSeconds(1); runner().runOnce(); assertEquals(1, gateway.sent.size)
    }
    @Test fun torBoxCredentialRejectionDoesNotBecomeGoogleReconnectError() = runBlocking {
        gateway.badToken = true; arm(); val result = runner().runOnce()
        assertTrue(result.torBoxAuthBlocked); assertFalse(result.authRequired)
        assertTrue(connected); assertEquals(DriveFileState.PENDING, files().single().state)
    }
    @Test fun expiredGoogleAuthorizationPreservesUnsentWork() = runBlocking {
        authorize = { GoogleDriveAuthorizationResult.Failed("consent expired") }
        arm(); runner().runOnce()
        assertTrue(gateway.sent.isEmpty()); assertFalse(connected)
        assertEquals(DriveWatchState.AUTH_REQUIRED, store.getWatch(key, scope)!!.state)
        assertEquals(DriveFileState.PENDING, files().single().state)
    }
    @Test fun queueActivationUsesExactHashAndSubmitsOnTheSamePass() = runBlocking {
        store.armQueued(scope, DownloadType.TORRENT, "q1", item.name, hash, "input.torrent")
        runner().runOnce(); assertEquals(1, gateway.sent.size)
        assertNull(store.getWatch(key, scope)!!.queueId)
    }
    @Test fun filenameAloneCannotBindAnotherTorrent() = runBlocking {
        store.armQueued(scope, DownloadType.TORRENT, "q1", item.name, null, "input.torrent")
        runner().runOnce(); assertTrue(gateway.sent.isEmpty())
    }
    @Test fun reusedTorrentIdWithDifferentHashIsBlocked() = runBlocking {
        arm(); source.item = item.copy(hash = "b".repeat(40)); runner().runOnce()
        assertTrue(gateway.sent.isEmpty()); assertEquals(DriveWatchState.FAILED, store.getWatch(key, scope)!!.state)
    }
    @Test fun cancellationAtPostBoundaryPreservesClaimAndPreventsReplay() = runBlocking {
        gateway.cancelAtPost = true; arm()
        try { runner().runOnce(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(DriveFileState.SUBMITTING, files().single().state)
        gateway.cancelAtPost = false; clock = clock.plusSeconds(660); runner().runOnce()
        assertEquals(1, gateway.sent.size); assertEquals(DriveFileState.UNCERTAIN, files().single().state)
    }
    @Test fun concurrentRunnersShareOneClaimGate() = runBlocking {
        arm(); listOf(async { runner().runOnce() }, async { runner().runOnce() }).forEach { it.await() }
        assertEquals(1, gateway.sent.size)
    }
    @Test fun accountChangeWaitsUntilCredentialBearingWorkLeavesGate() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        authorize = { entered.complete(Unit); release.await(); GoogleDriveAuthorizationResult.Authorized("google-test-secret") }
        arm(); val transfer = async { runner().runOnce() }; entered.await()
        val change = launch { AccountSensitiveWorkGate.mutex.withLock { token = "account-B" } }
        delay(40); assertEquals("account-A", token); assertFalse(change.isCompleted)
        release.complete(Unit); transfer.await(); change.join()
        runner().runOnce(); assertEquals(1, gateway.sent.size)
    }

    private inner class FakeSource(var item: DownloadItem?, var files: List<DownloadFile>) : DriveAutomationSource {
        override suspend fun queuedSnapshot() = RepositorySnapshot(listOfNotNull(item), emptyList(), clock, false)
        override suspend fun download(id: String) = item
        override suspend fun files(id: String) = files
    }
    private inner class FakeGateway : TorBoxDriveGateway {
        val sent = mutableListOf<Long>(); val folders = mutableListOf<String?>(); val jobs = mutableListOf<TorBoxIntegrationJob>()
        var lookupFails = false; var dropResponse = false; var makeJobs = true
        var rateLimitOnce = false; var badToken = false; var cancelAtPost = false
        override suspend fun updateGoogleDriveFolderId(folderId: String?) { folders += folderId }
        override suspend fun queueGoogleDrive(torrentId: String, fileId: Long, googleAccessToken: String) {
            if (badToken) throw TorBoxBadTokenException("BAD_TOKEN", 401, "Rejected")
            if (rateLimitOnce) { rateLimitOnce = false; throw TorBoxRateLimitException(120, "Wait") }
            sent += fileId
            if (cancelAtPost) throw CancellationException("Simulated process interruption")
            if (makeJobs) jobs += job(100L + sent.size, fileId)
            if (dropResponse) throw IOException("Simulated lost response")
        }
        override suspend fun getJobsByHash(hash: String): List<TorBoxIntegrationJob> {
            if (lookupFails) throw IOException("Lookup unavailable")
            return jobs.toList()
        }
        override suspend fun getJob(jobId: Long) = jobs.firstOrNull { it.id == jobId }
        fun job(id: Long, fileId: Long) = TorBoxIntegrationJob(id, fileId, hash, "google_drive", 0.0,
            "pending", "torrent", null, clock, clock, false)
    }
}
