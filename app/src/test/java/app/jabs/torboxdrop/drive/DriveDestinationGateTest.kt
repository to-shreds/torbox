package app.jabs.torboxdrop.drive

import android.app.Application
import app.jabs.torboxdrop.data.TorBoxDriveGateway
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadFile
import app.jabs.torboxdrop.model.DownloadType
import java.io.IOException
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

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class DriveDestinationGateTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var store: DriveStore
    private lateinit var api: Gateway
    private val scope = "A"
    private val item = DownloadItem("17", DownloadType.TORRENT, "Example", hash = "a".repeat(40))
    private val key = DriveStore.key(scope, item.type, item.id)
    private val file = DownloadFile(2, "17", DownloadType.TORRENT, "file.txt")
    @Before fun setUp() { context.deleteDatabase("torbox_drive_automation.db"); store = DriveStore(context); api = Gateway() }
    @After fun tearDown() { store.close() }
    private fun gate() = DriveDestinationGate(store, api)
    private suspend fun claim() { store.armActive(scope,item); store.ensureFileTransfers(key,listOf(file)); store.claimFileForSubmission("$key:2") }
    @Test fun folderLeaseRestoresOriginalAndNeverChangesPreferences() = runBlocking {
        assertTrue(gate().acquire(scope,key,"movies")); assertEquals("movies",api.folder)
        assertEquals("default",store.destinationLease(scope)!!.restoreFolderId)
        assertTrue(gate().releaseIfIdle(scope)); assertEquals("default",api.folder); assertNull(store.destinationLease(scope))
    }
    @Test fun activeAndUncertainSubmissionsPreventDifferentFolderUntilTerminal() = runBlocking {
        gate().acquire(scope,key,"movies"); claim()
        assertFalse(gate().acquire(scope,"other","family")); assertFalse(gate().releaseIfIdle(scope))
        store.markAmbiguousIfStale("$key:2",Instant.now().plusSeconds(60))
        assertFalse(gate().acquire(scope,"other","family")); assertEquals("movies",api.folder)
        store.markFileComplete("$key:2",23)
        assertTrue(gate().acquire(scope,"other","family")); assertEquals("family",api.folder)
    }
    @Test fun sameFolderDifferentWatchIsAlsoSerializedToAvoidJobCrossAdoption() = runBlocking {
        gate().acquire(scope,key,"movies"); claim()
        assertTrue(gate().acquire(scope,key,"movies")); assertFalse(gate().acquire(scope,"other","movies"))
    }
    @Test fun preUpgradeUncertainWorkHasNoLeaseButStillPreventsFolderMutation() = runBlocking {
        claim(); assertFalse(gate().acquire(scope,"other","movies")); assertTrue(api.writes.isEmpty())
    }
    @Test fun unknownAndExternalActiveJobsPreventFolderChanges() = runBlocking {
        for (status in listOf(null,"pending","uploading","unrecognized")) {
            api.jobs = listOf(api.job(status)); assertFalse(gate().acquire(scope,key,"movies"))
        }
        assertTrue(api.writes.isEmpty()); assertNull(store.destinationLease(scope))
    }
    @Test fun failedActiveJobReadCannotBeTreatedAsEmpty() = runBlocking {
        api.failList = true
        try { gate().acquire(scope,key,"movies"); fail("must fail") } catch (_: IOException) { }
        assertTrue(api.writes.isEmpty())
    }
    @Test fun lostFolderPutPreservesOriginalForRecoveryAndDoesNotSubmit() = runBlocking {
        api.losePut = true
        try { gate().acquire(scope,key,"movies"); fail("must fail") } catch (_: IOException) { }
        assertEquals("movies",api.folder); assertEquals("default",store.destinationLease(scope)!!.restoreFolderId)
        store.close(); store = DriveStore(context); api.losePut = false
        assertTrue(gate().acquire(scope,key,"movies")); assertEquals(1,api.writes.size)
        assertTrue(gate().releaseIfIdle(scope)); assertEquals("default",api.folder)
    }
    @Test fun unrelatedAccountCannotReleaseOrTakeAnotherLease() = runBlocking {
        gate().acquire(scope,key,"movies"); claim()
        assertNull(store.destinationLease("B")); assertTrue(store.inFlightWatchKeys("B").isEmpty())
        assertTrue(gate().releaseIfIdle("B")); assertEquals("movies",api.folder)
    }
    @Test fun remoteSettingChangedByUserIsNotOverwrittenWhileJobsAreRunning() = runBlocking {
        gate().acquire(scope,key,"movies"); claim(); api.folder = "website-choice"
        val writes = api.writes.size
        assertFalse(gate().acquire(scope,key,"movies")); assertEquals(writes,api.writes.size)
        store.markFileComplete("$key:2",2); assertTrue(gate().releaseIfIdle(scope))
        assertEquals("website-choice",api.folder)
    }
    @Test fun restorationSurvivesNetworkFailureAndRemainsRunnable() = runBlocking {
        gate().acquire(scope,key,"movies"); api.losePut = true
        try { gate().releaseIfIdle(scope); fail("must fail") } catch (_: IOException) { }
        assertTrue(store.hasRunnableWork(scope)); api.losePut = false
        assertTrue(gate().releaseIfIdle(scope)); assertFalse(store.hasRunnableWork(scope))
    }
    private class Gateway : TorBoxDriveGateway {
        var folder: String? = "default"; val writes = mutableListOf<String?>()
        var jobs = emptyList<TorBoxIntegrationJob>(); var failList = false; var losePut = false
        override suspend fun getGoogleDriveFolderId() = folder
        override suspend fun getAllJobs(): List<TorBoxIntegrationJob> { if (failList) throw IOException(); return jobs }
        override suspend fun updateGoogleDriveFolderId(folderId: String?) { folder = folderId; writes += folderId; if (losePut) throw IOException() }
        override suspend fun queueGoogleDrive(torrentId: String, fileId: Long, googleAccessToken: String) = error("Not part of folder selection")
        override suspend fun getJobsByHash(hash: String) = jobs
        override suspend fun getJob(jobId: Long) = jobs.firstOrNull { it.id == jobId }
        fun job(status: String?) = TorBoxIntegrationJob(1,2,"a".repeat(40),"google_drive",0.0,status,"torrent",null,Instant.now(),null)
    }
}
