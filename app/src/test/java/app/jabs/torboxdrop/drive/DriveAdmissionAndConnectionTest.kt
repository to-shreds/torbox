package app.jabs.torboxdrop.drive

import android.app.Application
import app.jabs.torboxdrop.data.AppPreferences
import app.jabs.torboxdrop.data.LocalStore
import app.jabs.torboxdrop.data.TorBoxApiClient
import app.jabs.torboxdrop.data.TorBoxDriveGateway
import app.jabs.torboxdrop.data.TorBoxRepository
import app.jabs.torboxdrop.model.AddOptions
import java.io.IOException
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
class DriveAdmissionAndConnectionTest {
    private lateinit var store: DriveStore
    private lateinit var local: LocalStore
    private lateinit var preferences: AppPreferences
    private lateinit var server: MockWebServer
    private var token = "account-A"
    private val scope get() = driveAccountScope(token)!!
    private val hash = "a".repeat(40)
    private val magnet get() = "magnet:?xt=urn:btih:$hash"
    private var scheduled = 0
    @Before fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.databaseList().forEach { context.deleteDatabase(it) }
        store = DriveStore(context); local = LocalStore(context); preferences = AppPreferences(context)
        preferences.resetAll(); server = MockWebServer(); server.start()
        preferences.reserveDriveFolder("dest", "Folder"); preferences.bindDriveAccount(scope)
    }
    @After fun tearDown() { store.close(); local.close(); server.shutdown() }
    private fun repo(callback: () -> Unit = { scheduled++ }) = TorBoxRepository(
        TorBoxApiClient(OkHttpClient(), { token }, server.url("/v1/api/")), local, preferences, store, { token }, callback)
    private fun added(data: String = """{"torrent_id":17,"hash":"$hash"}""") =
        MockResponse().setBody("""{"success":true,"detail":"Added","data":$data}""")
    @Test fun driveIntentExistsDurablyBeforeTorBoxReceivesCreate() = runBlocking {
        var witnessed = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                witnessed = runBlocking { store.runnableWatches(scope).single().let { it.sourceHash == hash && it.sourceValue == null } }
                return added()
            }
        }
        val result = repo().createMagnet(magnet, AddOptions(sendToGoogleDrive = true))
        assertTrue(witnessed); assertEquals("17", result.id); assertTrue(scheduled >= 1)
        assertEquals(1, store.runnableWatches(scope).size)
        assertEquals("17", store.runnableWatches(scope).single().downloadId)
    }
    @Test fun requestingDriveWithoutConnectionIsNotSilentlyDropped() = runBlocking {
        preferences.clearDriveConnection()
        try { repo().createMagnet(magnet, AddOptions(sendToGoogleDrive = true)); fail("preflight must reject") }
        catch (_: IllegalStateException) { }
        assertEquals(0, server.requestCount)
    }
    @Test fun explicitFalseOverridesRememberedTrueWithoutChangingOrdinaryAdd() = runBlocking {
        preferences.googleDriveByDefault = true; server.enqueue(added())
        assertEquals("17", repo().createMagnet(magnet, AddOptions(sendToGoogleDrive = false)).id)
        assertTrue(store.recentWatches(scope).isEmpty()); assertEquals(0, scheduled)
    }
    @Test fun missingIdsRetainAnExactHashAdmissionInsteadOfLosingDriveIntent() = runBlocking {
        server.enqueue(added("{}")); repo().createMagnet(magnet, AddOptions(sendToGoogleDrive = true))
        val pending = store.runnableWatches(scope).single()
        assertEquals(hash, pending.sourceHash); assertTrue(pending.queueId!!.startsWith("ADMISSION:"))
    }
    @Test fun schedulerFailureAfterRemoteSuccessCannotTurnAddIntoFailure() = runBlocking {
        var calls = 0; server.enqueue(added())
        val result = repo { calls++; if (calls > 1) throw IllegalStateException("scheduler unavailable") }
            .createMagnet(magnet, AddOptions(sendToGoogleDrive = true))
        assertEquals("17", result.id); assertEquals(1, server.requestCount)
        assertTrue(result.detail.contains("could not schedule"))
    }
    @Test fun schedulerFailureBeforeRemoteCreateCannotAbortOrDuplicateAdd() = runBlocking {
        var calls = 0; server.enqueue(added())
        val result = repo { calls++; if (calls == 1) throw IllegalStateException("scheduler unavailable") }
            .createMagnet(magnet, AddOptions(sendToGoogleDrive = true))
        assertEquals("17", result.id); assertEquals(1, server.requestCount); assertTrue(calls >= 2)
        assertEquals("17", store.runnableWatches(scope).single().downloadId)
    }
    @Test fun folderReservationSurvivesTorBoxSettingsFailureAndRetryReusesIt() = runBlocking {
        preferences.clearDriveConnection()
        var generated = 0; var created = 0; var exists = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.contains("generateIds") -> { generated++; MockResponse().setBody("""{"ids":["dest"]}""") }
                request.method == "POST" -> { created++; exists = true; MockResponse().setBody("""{"id":"dest","name":"Folder"}""") }
                exists -> MockResponse().setBody("""{"id":"dest","name":"Folder","mimeType":"application/vnd.google-apps.folder","trashed":false,"capabilities":{"canAddChildren":true}}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val gateway = SettingsGateway(); gateway.fail = true
        val coordinator = DriveConnectionCoordinator(preferences, { token },
            GoogleDriveApiClient(OkHttpClient(), server.url("/drive/v3/")), gateway, store)
        try { coordinator.connect(scope, "Folder", "google-test-secret"); fail("settings must fail") } catch (_: IOException) { }
        assertEquals("dest", preferences.googleDriveFolderId); assertFalse(preferences.driveConfiguredFor(scope))
        gateway.fail = false; coordinator.connect(scope, "Folder", "google-test-secret")
        assertTrue(preferences.driveConfiguredFor(scope)); assertEquals(1, generated); assertEquals(1, created)
    }
    @Test fun staleGoogleConsentResultCannotConfigureReplacementTorBoxAccount() = runBlocking {
        val oldScope = scope; token = "account-B"
        val gateway = SettingsGateway()
        val coordinator = DriveConnectionCoordinator(preferences, { token },
            GoogleDriveApiClient(OkHttpClient(), server.url("/drive/v3/")), gateway, store)
        try { coordinator.connect(oldScope, "Folder", "google-test-secret"); fail("stale consent must be rejected") }
        catch (_: IllegalStateException) { }
        assertEquals(0, server.requestCount); assertEquals(0, gateway.calls)
        assertFalse(preferences.driveConfiguredFor(scope))
    }
    @Test fun reconnectingDefaultDoesNotRedirectAnExistingManualDestinationLease() = runBlocking {
        store.saveDestinationLease(scope, DriveDestinationLease("custom", "dest"))
        server.enqueue(MockResponse().setBody("""{"id":"dest","name":"Folder","mimeType":"application/vnd.google-apps.folder","trashed":false,"capabilities":{"canAddChildren":true}}"""))
        val gateway = SettingsGateway()
        DriveConnectionCoordinator(preferences, { token }, GoogleDriveApiClient(OkHttpClient(), server.url("/drive/v3/")), gateway, store)
            .connect(scope, "Folder", "google-test-secret")
        assertTrue(preferences.driveConfiguredFor(scope)); assertEquals("dest", preferences.googleDriveFolderId)
        assertEquals(0, gateway.calls); assertEquals("custom", store.destinationLease(scope)!!.folderId)
    }
    @Test fun changingDefaultWhileManualLeaseExistsDoesNotTouchRemoteOrPreferences() = runBlocking {
        store.saveDestinationLease(scope, DriveDestinationLease("custom", "dest"))
        val gateway = SettingsGateway()
        try {
            DriveConnectionCoordinator(preferences, { token }, GoogleDriveApiClient(OkHttpClient(), server.url("/drive/v3/")), gateway, store)
                .connect(scope, "Changed", "google-test-secret")
            fail("Must wait for uploads")
        } catch (_: GoogleDriveApiException) { }
        assertEquals(0, gateway.calls); assertEquals(0, server.requestCount)
        assertEquals("Folder", preferences.googleDriveFolderName); assertTrue(preferences.driveConfiguredFor(scope))
    }

    private class SettingsGateway : TorBoxDriveGateway {
        var fail = false; var calls = 0
        override suspend fun getGoogleDriveFolderId(): String? = "old-destination"
        override suspend fun getAllJobs() = emptyList<TorBoxIntegrationJob>()
        override suspend fun updateGoogleDriveFolderId(folderId: String?) { calls++; if (fail) throw IOException("not saved") }
        override suspend fun queueGoogleDrive(torrentId: String, fileId: Long, googleAccessToken: String) = Unit
        override suspend fun getJobsByHash(hash: String) = emptyList<TorBoxIntegrationJob>()
        override suspend fun getJob(jobId: Long): TorBoxIntegrationJob? = null
    }
}
