package app.jabs.torboxdrop.data

import android.app.Application
import app.jabs.torboxdrop.drive.*
import app.jabs.torboxdrop.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Tests for the OLD implementation only: passing means the historical defects were observed. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], manifest = Config.NONE)
class DeletionBaselineTest {
    @Test fun successfulRemoteDeletionLeavesCachedItemBehind() = runBlocking {
        val c = RuntimeEnvironment.getApplication(); c.databaseList().forEach { c.deleteDatabase(it) }
        val local = LocalStore(c); val prefs = AppPreferences(c); val drive = DriveStore(c)
        val server = MockWebServer(); server.start()
        try {
            val repo = TorBoxRepository(TorBoxApiClient(OkHttpClient(), { "test-only-token" }, server.url("/v1/api/")),
                local, prefs, drive, { "test-only-token" })
            val item = DownloadItem("17", DownloadType.TORRENT, "Example")
            local.replaceDownloads(listOf(item))
            server.enqueue(MockResponse().setBody("""{"success":true,"data":null}"""))
            repo.deleteTorrent("17")
            assertEquals("/v1/api/torrents/controltorrent", server.takeRequest().path)
            assertEquals("Historical bug: confirmed delete did not evict cached item", listOf(item), local.loadDownloads())
        } finally { local.close(); drive.close(); server.shutdown() }
    }
    @Test fun rejectedRemoteDeletionStillStopsDriveInstruction() = runBlocking {
        val c = RuntimeEnvironment.getApplication(); c.databaseList().forEach { c.deleteDatabase(it) }
        val local = LocalStore(c); val prefs = AppPreferences(c); val drive = DriveStore(c)
        val server = MockWebServer(); server.start()
        try {
            val token = "test-only-token"; val scope = driveAccountScope(token)!!
            val repo = TorBoxRepository(TorBoxApiClient(OkHttpClient(), { token }, server.url("/v1/api/")),
                local, prefs, drive, { token })
            drive.armActive(scope, DownloadItem("17", DownloadType.TORRENT, "Example"))
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"success":false,"error":"DELETE_FAILED"}"""))
            try { repo.deleteTorrent("17"); fail("expected rejection") } catch (_: TorBoxApiException) { }
            assertEquals("Historical bug: rejected delete stopped Drive", DriveWatchState.FAILED, drive.recentWatches(scope).single().state)
        } finally { local.close(); drive.close(); server.shutdown() }
    }
}
