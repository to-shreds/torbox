package app.jabs.torboxdrop.drive

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import app.jabs.torboxdrop.data.AppPreferences
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadFile
import app.jabs.torboxdrop.model.DownloadType
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
class ManualDriveTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var store: DriveStore
    private lateinit var prefs: AppPreferences
    private lateinit var google: MockWebServer
    private var token = "test-account-A"
    private val scope get() = driveAccountScope(token)!!
    private val item = DownloadItem("17", DownloadType.TORRENT, "Example", hash = "a".repeat(40),
        downloadFinished = true, downloadPresent = true)
    private var live: DownloadItem? = item
    private var generated = 0
    private var scheduleCalls = 0
    private var failSchedule = false
    private var failCreate = false
    private val folders = mutableMapOf("default" to "Default")
    @Before fun setUp() {
        context.deleteDatabase("torbox_drive_automation.db"); store = DriveStore(context)
        prefs = AppPreferences(context); prefs.resetAll(); prefs.clearDriveConnection()
        prefs.reserveDriveFolder("default", "Default"); prefs.bindDriveAccount(scope); prefs.googleDriveByDefault = true
        google = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(r: RecordedRequest): MockResponse {
                    if (r.path!!.contains("generateIds")) {
                        generated++; return MockResponse().setBody("""{"ids":["folder$generated"]}""")
                    }
                    if (r.method == "POST") {
                        if (failCreate) return MockResponse().setResponseCode(503)
                        val json = app.jabs.torboxdrop.data.Json.parse(r.body.readUtf8()) as app.jabs.torboxdrop.data.JsonValue.Object
                        val id = json.string("id")!!; folders[id] = json.string("name")!!
                        return MockResponse().setBody("""{"id":"$id"}""")
                    }
                    val id = r.requestUrl!!.pathSegments.last()
                    val name = folders[id] ?: return MockResponse().setResponseCode(404)
                    return MockResponse().setBody("""{"id":"$id","name":"$name","mimeType":"application/vnd.google-apps.folder","trashed":false,"capabilities":{"canAddChildren":true}}""")
                }
            }; start()
        }
    }
    @After fun tearDown() { store.close(); google.shutdown() }
    private fun coordinator() = ManualDriveCoordinator(prefs, { token }, GoogleDriveApiClient(OkHttpClient(), google.url("/drive/v3/")),
        store, { live }, { scheduleCalls++; if (failSchedule) error("Scheduler unavailable") })
    private fun target() = ManualDriveTarget(item, scope)

    @Test fun confirmedRequestSnapshotsChosenFolderWithoutChangingDefaultOrReaddingTorrent() = runBlocking {
        val result = coordinator().enqueue(target(), "Movies", "test-google-token")
        assertTrue(result.added); assertTrue(result.monitoringScheduled)
        val watch = store.recentWatches(scope).single()
        assertEquals("Movies", watch.destinationFolderName); assertEquals("folder1", watch.destinationFolderId)
        assertEquals("17", watch.downloadId); assertEquals(item.hash, watch.sourceHash)
        assertNull(watch.sourceValue); assertEquals(DriveWatchState.WAITING, watch.state)
        assertEquals("Default", prefs.googleDriveFolderName); assertEquals("default", prefs.googleDriveFolderId)
        assertTrue(prefs.googleDriveByDefault); assertTrue(prefs.driveConfiguredFor(scope))
        assertEquals(1, scheduleCalls)
    }
    @Test fun cancelOrMerelyConstructingRequestDoesNothing() = runBlocking {
        coordinator(); target()
        assertEquals(0, google.requestCount); assertEquals(0, scheduleCalls); assertTrue(store.recentWatches(scope).isEmpty())
    }
    @Test fun sameNameReusesReservedFolderAndDoubleConfirmDoesNotDuplicate() = runBlocking {
        val first = async { coordinator().enqueue(target(), "Movies", "g") }
        val second = async { coordinator().enqueue(target(), " Movies ", "g") }
        assertEquals(1, listOf(first.await(), second.await()).count { it.added })
        assertEquals(1, generated); assertEquals(1, store.recentWatches(scope).size)
    }
    @Test fun twoDifferentFolderChoicesHaveSeparateDurableRequests() = runBlocking {
        coordinator().enqueue(target(), "Movies", "g"); coordinator().enqueue(target(), "Family", "g")
        assertEquals(setOf("Movies", "Family"), store.recentWatches(scope).map { it.destinationFolderName }.toSet())
        assertEquals("Default", prefs.googleDriveFolderName)
    }
    @Test fun choosingDefaultReusesExistingFolderAndAutomaticPendingRequest() = runBlocking {
        store.armActive(scope, item)
        assertFalse(coordinator().enqueue(target(), "Default", "g").added)
        assertEquals(0, generated); assertEquals(1, store.recentWatches(scope).size)
    }
    @Test fun schedulerFailureLeavesDurableRequestWithoutFalseSuccess() = runBlocking {
        failSchedule = true; val result = coordinator().enqueue(target(), "Movies", "g")
        assertTrue(result.added); assertFalse(result.monitoringScheduled)
        assertEquals(1, store.runnableWatches(scope).size)
    }
    @Test fun failedFolderCreationReusesItsIdAfterRestart() = runBlocking {
        failCreate = true
        try { coordinator().enqueue(target(), "Movies", "g"); fail("must fail") } catch (_: GoogleDriveApiException) { }
        assertTrue(store.recentWatches(scope).isEmpty()); assertEquals(0, scheduleCalls)
        store.close(); store = DriveStore(context); failCreate = false
        coordinator().enqueue(target(), "Movies", "g")
        assertEquals(1, generated); assertEquals("folder1", store.recentWatches(scope).single().destinationFolderId)
    }
    @Test fun unreadyDeletedWrongTypeAndReusedIdsAreRejectedBeforeAnyDriveWrite() = runBlocking {
        for (bad in listOf(null, item.copy(downloadPresent = false), item.copy(downloadFinished = false),
            item.copy(type = DownloadType.WEB), item.copy(hash = "b".repeat(40)), item.copy(id = "18"))) {
            live = bad
            try { coordinator().enqueue(target(), "Movies", "g"); fail("must reject") } catch (_: ManualDriveException) { }
        }
        assertEquals(0, google.requestCount); assertEquals(0, scheduleCalls)
    }
    @Test fun staleAccountCannotUseConsentOrMutateFolders() = runBlocking {
        val old = target(); token = "account-B"
        try { coordinator().enqueue(old, "Movies", "g"); fail("must reject") } catch (_: IllegalStateException) { }
        assertEquals(0, google.requestCount); assertTrue(store.recentWatches(scope).isEmpty())
    }
    @Test fun blankOversizedAndControlCharacterNamesFailLocally() = runBlocking {
        for (name in listOf(" ", ".", "..", "x".repeat(201), "Bad\nName")) {
            try { coordinator().enqueue(target(), name, "g"); fail("must reject") } catch (_: ManualDriveException) { }
        }
        assertEquals(0, google.requestCount)
    }
    @Test fun newColumnsAndManualDestinationSurviveRestart() = runBlocking {
        coordinator().enqueue(target(), "Movies", "g")
        store.close(); store = DriveStore(context)
        assertEquals("Movies", store.recentWatches(scope).single().destinationFolderName)
        assertEquals("folder1", store.reservedManualFolder(scope, "Movies"))
        assertNull(store.reservedManualFolder("other-account", "Movies"))
    }
    @Test fun upgradeFromV2PreservesExistingSubmissionAndDefaultConnection() = runBlocking {
        store.close(); context.deleteDatabase("torbox_drive_automation.db")
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("torbox_drive_automation.db"), null)
        db.execSQL("CREATE TABLE drive_watches(watch_key TEXT PRIMARY KEY NOT NULL, account_scope TEXT NOT NULL, item_id TEXT NOT NULL, item_type TEXT NOT NULL, name TEXT NOT NULL, armed_at INTEGER NOT NULL, has_been_seen INTEGER NOT NULL, consecutive_misses INTEGER NOT NULL, queue_id TEXT, source_hash TEXT, source_value TEXT, state TEXT NOT NULL, last_error TEXT)")
        db.execSQL("CREATE TABLE drive_files(transfer_key TEXT PRIMARY KEY NOT NULL, watch_key TEXT NOT NULL, file_id INTEGER NOT NULL, file_name TEXT NOT NULL, state TEXT NOT NULL, job_id INTEGER, attempts INTEGER NOT NULL, last_attempt_at INTEGER, prior_job_ids TEXT NOT NULL DEFAULT '', baseline_captured INTEGER NOT NULL DEFAULT 0, retry_at INTEGER, last_error TEXT)")
        db.execSQL("INSERT INTO drive_watches VALUES('old',?,'17','TORRENT','Example',0,1,0,NULL,?,NULL,'TRANSFERRING',NULL)", arrayOf(scope,item.hash))
        db.execSQL("INSERT INTO drive_files VALUES('old:2','old',2,'file.txt','SUBMITTING',NULL,1,1,'3',1,NULL,NULL)")
        db.version = 2; db.close(); store = DriveStore(context)
        assertEquals(DriveFileState.SUBMITTING, store.fileTransfers("old").single().state)
        assertNull(store.getWatch("old", scope)!!.destinationFolderId)
        assertFalse(store.claimFileForSubmission("old:2")); assertTrue(prefs.driveConfiguredFor(scope))
        coordinator().enqueue(target(), "Movies", "g")
        assertEquals(2, store.recentWatches(scope).size)
    }
}
