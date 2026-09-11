package app.jabs.torboxdrop.data

import android.app.Application
import app.jabs.torboxdrop.drive.DriveStore
import app.jabs.torboxdrop.drive.DriveWatchState
import app.jabs.torboxdrop.drive.driveAccountScope
import app.jabs.torboxdrop.model.*
import app.jabs.torboxdrop.notifications.AccountSensitiveWorkGate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class DeleteFlowTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var local: LocalStore
    private lateinit var drive: DriveStore
    private lateinit var prefs: AppPreferences
    private lateinit var server: MockWebServer
    private lateinit var repo: TorBoxRepository
    private var token = "delete-test-account-A"
    private val scope get() = driveAccountScope(token)!!
    private val item = DownloadItem("17", DownloadType.TORRENT, "Example", hash = "a".repeat(40),
        downloadFinished = true, downloadPresent = true)
    private val web = item.copy(type = DownloadType.WEB)
    private fun success(data: String = "null") = MockResponse().setBody("""{"success":true,"data":$data}""")
    private fun denied(status: Int, code: String = "DELETE_FAILED") = MockResponse().setResponseCode(status)
        .setBody("""{"success":false,"error":"$code","detail":"Cannot delete token=$token"}""")
    @Before fun setUp() {
        context.databaseList().forEach { context.deleteDatabase(it) }
        local = LocalStore(context); drive = DriveStore(context); prefs = AppPreferences(context); prefs.resetAll()
        server = MockWebServer().apply { start() }
        repo = TorBoxRepository(TorBoxApiClient(OkHttpClient(), { token }, server.url("/v1/api/")),
            local, prefs, drive, { token })
    }
    @After fun tearDown() { local.close(); drive.close(); server.shutdown() }

    @Test fun successfulDeleteEvictsOnlySelectedTorrentAndCachedFiles() = runBlocking {
        local.replaceDownloads(listOf(item, web, item.copy(id = "18")))
        val file = DownloadFile(1, item.id, item.type, "Example.mkv")
        local.saveFiles(item.type, item.id, listOf(file)); drive.armActive(scope, item)
        server.enqueue(success())
        assertTrue(repo.deleteTorrent(item.id).localCleanupComplete)
        assertEquals(setOf("WEB:17", "TORRENT:18"), local.loadDownloads().map { "${it.type}:${it.id}" }.toSet())
        assertTrue(local.loadFiles(item.type, item.id).isEmpty())
        assertEquals(DriveWatchState.FAILED, drive.recentWatches(scope).single().state)
        val request = server.takeRequest()
        assertEquals("/v1/api/torrents/controltorrent", request.path)
        assertEquals("POST", request.method)
        val body = Json.parse(request.body.readUtf8()) as JsonValue.Object
        assertEquals(17L, body.long("torrent_id")); assertEquals("delete", body.string("operation"))
        assertEquals(false, body.boolean("all")); assertEquals("Bearer $token", request.getHeader("Authorization"))
    }
    @Test fun rejectedDeletePreservesVisibleItemFilesAndDriveInstruction() = runBlocking {
        local.replaceDownloads(listOf(item)); drive.armActive(scope, item)
        server.enqueue(denied(400))
        try { repo.deleteTorrent(item.id); fail("must reject") } catch (error: TorBoxApiException) {
            assertFalse(error.message!!.contains(token))
        }
        assertEquals(listOf(item), local.loadDownloads()); assertFalse(local.isDeleted(item.type, item.id))
        assertEquals(DriveWatchState.WAITING, drive.recentWatches(scope).single().state)
    }
    @Test fun deletedTorrentCannotReturnThroughStaleListOrDirectLookup() = runBlocking {
        server.enqueue(success()); repo.deleteTorrent("17")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.path!!.startsWith("/v1/api/torrents/mylist"))
                success("""[{"id":17,"name":"Stale","download_finished":true,"download_present":true}]""") else success("[]")
        }
        assertTrue(repo.refreshDownloads(true).isEmpty()); assertTrue(local.loadDownloads().isEmpty())
        val count = server.requestCount
        assertNull(repo.getDownload(DownloadType.TORRENT, "17", true)); assertEquals(count, server.requestCount)
        local.replaceDownloads(listOf(item)); assertTrue(local.loadDownloads().isEmpty())
    }
    @Test fun deletionPersistsAcrossCacheReopenAndClearCacheButAccountResetClearsIt() = runBlocking {
        server.enqueue(success()); repo.deleteTorrent("17")
        local.close(); local = LocalStore(context)
        local.clearDownloadCache(); local.replaceDownloads(listOf(item))
        assertTrue(local.loadDownloads().isEmpty())
        local.clearAccountScopedData(); local.replaceDownloads(listOf(item))
        assertEquals(listOf(item), local.loadDownloads())
    }
    @Test fun anAcceptedExplicitReaddRestoresTheReturnedIdentity() = runBlocking {
        server.enqueue(success()); repo.deleteTorrent("17")
        server.enqueue(success("""{"torrent_id":17,"hash":"${item.hash}"}"""))
        repo.createMagnet("magnet:?xt=urn:btih:${item.hash}", AddOptions(sendToGoogleDrive = false))
        local.replaceDownloads(listOf(item)); assertEquals(listOf(item), local.loadDownloads())
    }
    @Test fun failedReaddCannotUndoConfirmedDeletion() = runBlocking {
        server.enqueue(success()); repo.deleteTorrent("17"); server.enqueue(denied(400))
        try { repo.createMagnet("magnet:?xt=urn:btih:${item.hash}", AddOptions(sendToGoogleDrive = false)); fail() }
        catch (_: TorBoxApiException) { }
        assertTrue(local.isDeleted(item.type, item.id))
    }
    @Test fun queueDeletionUsesQueueEndpointAndCannotDeleteActiveTorrentWithSameId() = runBlocking {
        val queued = QueuedDownload("17", DownloadType.TORRENT, "Queued")
        local.replaceQueue(listOf(queued)); local.replaceDownloads(listOf(item))
        drive.armActive(scope, item); drive.armQueued(scope, item.type, "17", "Queued", item.hash, null)
        server.enqueue(success()); assertTrue(repo.deleteQueued("17", item.type).localCleanupComplete)
        assertTrue(local.loadQueue().isEmpty()); assertEquals(listOf(item), local.loadDownloads())
        assertEquals(DriveWatchState.WAITING, drive.recentWatches(scope).single { it.queueId == null }.state)
        assertEquals(DriveWatchState.FAILED, drive.recentWatches(scope).single { it.queueId == "17" }.state)
        val request = server.takeRequest(); assertEquals("/v1/api/queued/controlqueued", request.path)
        val body = Json.parse(request.body.readUtf8()) as JsonValue.Object
        assertEquals(17L, body.long("queued_id")); assertEquals(false, body.boolean("all"))
        local.replaceQueue(listOf(queued)); assertTrue(local.loadQueue().isEmpty())
    }
    @Test fun deletingAWebQueueItemDoesNotStopATorrentDriveWatch() = runBlocking {
        drive.armQueued(scope, DownloadType.TORRENT, "17", "Queued", item.hash, null)
        server.enqueue(success()); repo.deleteQueued("17", DownloadType.WEB)
        assertEquals(DriveWatchState.WAITING, drive.recentWatches(scope).single().state)
    }
    @Test fun webDeletionUsesOnlyWebIdAndDoesNotTouchTorrentDriveWatch() = runBlocking {
        local.replaceDownloads(listOf(item, web)); drive.armActive(scope, item)
        server.enqueue(success()); repo.deleteWebDownload("17")
        assertEquals(listOf(item), local.loadDownloads())
        assertEquals(DriveWatchState.WAITING, drive.recentWatches(scope).single().state)
        val request = server.takeRequest(); assertEquals("/v1/api/webdl/controlwebdownload", request.path)
        val body = Json.parse(request.body.readUtf8()) as JsonValue.Object
        assertEquals(17L, body.long("webdl_id")); assertEquals(false, body.boolean("all")); assertNull(body["torrent_id"])
    }
    @Test fun explicitItemNotFoundCompletesDeleteButGeneric404DoesNot() = runBlocking {
        local.replaceDownloads(listOf(item)); server.enqueue(denied(404, "ENDPOINT_NOT_FOUND"))
        try { repo.deleteTorrent("17"); fail() } catch (_: TorBoxApiException) { }
        assertEquals(listOf(item), local.loadDownloads())
        server.enqueue(denied(400, "ITEM_NOT_FOUND")); assertTrue(repo.deleteTorrent("17").localCleanupComplete)
        assertTrue(local.loadDownloads().isEmpty())
    }
    @Test fun failedServerOrAuthOrRateLimitResponseCannotMasqueradeAsDeletion() = runBlocking {
        for (status in listOf(401, 403, 429, 503)) {
            local.replaceDownloads(listOf(item)); server.enqueue(denied(status, "ITEM_NOT_FOUND"))
            try { repo.deleteTorrent("17"); fail("status $status must reject") } catch (_: TorBoxException) { }
            assertEquals(listOf(item), local.loadDownloads()); assertFalse(local.isDeleted(item.type, item.id))
        }
    }
    @Test fun accountChangedWhileWaitingForGateCannotDeleteTheNewAccountsItem() = runBlocking {
        AccountSensitiveWorkGate.mutex.lock()
        val result = async(start = CoroutineStart.UNDISPATCHED) { runCatching { repo.deleteTorrent("17") } }
        token = "delete-test-account-B"
        AccountSensitiveWorkGate.mutex.unlock()
        assertTrue(result.await().exceptionOrNull() is IllegalStateException); assertEquals(0, server.requestCount)
    }
    @Test fun anInFlightOldListCannotReinsertAnItemDeletedDuringItsRequest() = runBlocking {
        val started = CountDownLatch(1); val resume = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.startsWith("/v1/api/torrents/mylist")) {
                    started.countDown(); check(resume.await(10, TimeUnit.SECONDS))
                    return success("""[{"id":17,"name":"Old snapshot"}]""")
                }
                return success(if (request.method == "GET") "[]" else "null")
            }
        }
        val refreshing = async(Dispatchers.Default) { repo.refreshDownloads(true) }
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS)); repo.deleteTorrent("17")
        } finally { resume.countDown() }
        assertTrue(refreshing.await().isEmpty()); assertTrue(local.loadDownloads().isEmpty())
    }
    @Test fun schema2UpgradePreservesCacheAndAddsOnlyDeletionTable() = runBlocking {
        local.replaceDownloads(listOf(item)); val db = local.writableDatabase
        db.execSQL("DROP TABLE deleted_items"); db.version = 2
        local.close(); local = LocalStore(context)
        assertEquals(listOf(item), local.loadDownloads()); assertEquals(3, local.readableDatabase.version)
        local.markDeleted(item.type, item.id); assertTrue(local.loadDownloads().isEmpty())
    }
}
