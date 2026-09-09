package app.jabs.torboxdrop.drive

import app.jabs.torboxdrop.data.TorBoxDriveIntegrationClient
import app.jabs.torboxdrop.data.TorBoxBadTokenException
import app.jabs.torboxdrop.data.TorBoxApiException
import app.jabs.torboxdrop.data.TorBoxRateLimitException
import app.jabs.torboxdrop.data.TorBoxInvalidResponseException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DriveBoundaryTest {
    private lateinit var server: MockWebServer
    private lateinit var sink: MockWebServer
    private val torBoxToken = "torbox-private-token"
    private val googleToken = "google-private-token"
    private val hash = "a".repeat(40)
    @Before fun setUp() { server = MockWebServer(); server.start(); sink = MockWebServer(); sink.start() }
    @After fun tearDown() { server.shutdown(); sink.shutdown() }
    private fun torBox() = TorBoxDriveIntegrationClient(OkHttpClient(), { torBoxToken }, server.url("/v1/api/"))
    private fun google() = GoogleDriveApiClient(OkHttpClient(), server.url("/drive/v3/"))
    private fun folder() = MockResponse().setBody("""{"id":"dest","name":"Folder","mimeType":"application/vnd.google-apps.folder","trashed":false,"capabilities":{"canAddChildren":true}}""")

    @Test fun submissionUsesDocumentedPrivateBodyNotDownloadOrShareUrl() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":null}"""))
        torBox().queueGoogleDrive("17", 2, googleToken)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method); assertEquals("/v1/api/integration/googledrive", request.path)
        assertEquals("Bearer $torBoxToken", request.getHeader("Authorization"))
        assertEquals("""{"id":17,"file_id":2,"zip":false,"type":"torrent","google_token":"google-private-token"}""", request.body.readUtf8())
        assertFalse(request.path!!.contains(torBoxToken)); assertFalse(request.path!!.contains(googleToken))
    }
    @Test fun torBoxPostCannotRedirectEitherCredentialToAnotherHost() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", sink.url("/steal")))
        try { torBox().queueGoogleDrive("17", 2, googleToken); fail("redirect must fail") } catch (_: TorBoxApiException) { }
        assertEquals(1, server.requestCount); assertEquals(0, sink.requestCount)
    }
    @Test fun serviceUnavailableRetryAfterZeroDoesNotReplayPost() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setBody("""{"success":true}"""))
        try { torBox().queueGoogleDrive("17", 2, googleToken); fail("503 must fail") } catch (_: TorBoxApiException) { }
        assertEquals(1, server.requestCount)
    }
    @Test fun rejectionCannotLeakRawEncodedOrEscapedGoogleToken() = runBlocking {
        val echo = java.net.URLEncoder.encode(googleToken, "UTF-8")
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"success":false,"error":"$googleToken","detail":"$torBoxToken $echo"}"""))
        try { torBox().queueGoogleDrive("17", 2, googleToken); fail("expected rejection") }
        catch (error: TorBoxApiException) {
            assertFalse(error.toString().contains(googleToken)); assertFalse(error.toString().contains(torBoxToken))
            assertFalse(error.toString().contains(echo)); assertNull(error.apiCode)
        }
    }
    @Test fun rateLimitIsTypedAndDoesNotTrustServerDetail() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "123")
            .setBody("""{"success":false,"detail":"$googleToken"}"""))
        try { torBox().queueGoogleDrive("17", 2, googleToken); fail("expected rate limit") }
        catch (error: TorBoxRateLimitException) { assertEquals(123L, error.retryAfterSeconds); assertFalse(error.toString().contains(googleToken)) }
    }
    @Test fun authoritativeBadTorBoxTokenIsDistinctFromGoogleRejection() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":false,"error":"BAD_TOKEN"}"""))
        try { torBox().queueGoogleDrive("17", 2, googleToken); fail("expected bad token") }
        catch (error: TorBoxBadTokenException) { assertEquals("BAD_TOKEN", error.apiCode) }
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"success":false,"error":"GOOGLE_AUTH_REJECTED"}"""))
        try { torBox().queueGoogleDrive("17", 2, googleToken); fail("expected Drive rejection") }
        catch (error: TorBoxApiException) { assertEquals(401, error.statusCode) }
    }
    @Test fun malformedJobListCannotBecomeEmptyBaseline() = runBlocking {
        for (body in listOf("{}", "", "{\"success\":true,\"data\":null}", "{\"success\":true,\"data\":[7]}")) {
            server.enqueue(MockResponse().setBody(body))
            try { torBox().getJobsByHash(hash); fail("must reject $body") } catch (_: TorBoxInvalidResponseException) { }
        }
    }
    @Test fun pathInjectionHashIsRejectedBeforeNetwork() = runBlocking {
        try { torBox().getJobsByHash("../user/me?token=bad"); fail("must reject path") } catch (_: IllegalArgumentException) { }
        assertEquals(0, server.requestCount)
    }
    @Test fun arbitraryJobErrorPayloadIsNotPersistableDetail() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"success":true,"data":[{"id":3,"file_id":2,"detail":"$googleToken"}]}"""))
        val job = torBox().getJobsByHash(hash).single(); assertNull(job.detail)
    }
    @Test fun googleBearerCannotFollowRedirect() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", sink.url("/steal")))
        try { google().requireWritableFolder("dest", googleToken); fail("must reject redirect") } catch (_: GoogleDriveApiException) { }
        assertEquals(0, sink.requestCount)
    }
    @Test fun folderMustBeWritableUntrashedAndActuallyAFolder() = runBlocking {
        for (body in listOf(
            """{"id":"dest","mimeType":"text/plain","trashed":false,"capabilities":{"canAddChildren":true}}""",
            """{"id":"dest","mimeType":"application/vnd.google-apps.folder","trashed":true,"capabilities":{"canAddChildren":true}}""",
            """{"id":"dest","mimeType":"application/vnd.google-apps.folder","trashed":false,"capabilities":{"canAddChildren":false}}""",
            """{"id":"wrong","mimeType":"application/vnd.google-apps.folder","trashed":false,"capabilities":{"canAddChildren":true}}"""
        )) {
            server.enqueue(MockResponse().setBody(body))
            try { google().requireWritableFolder("dest", googleToken); fail("bad folder accepted") } catch (_: GoogleDriveApiException) { }
        }
    }
    @Test fun reservedIdUsedForCreationAndConflictRecoveryDoesNotCreateAnotherId() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(folder())
        assertEquals("dest", google().ensureDestinationFolder("dest", "Folder", googleToken).id)
        server.takeRequest(); val post = server.takeRequest(); server.takeRequest()
        assertTrue(post.body.readUtf8().contains("\"id\":\"dest\""))
        assertEquals(3, server.requestCount)
    }
    @Test fun anExistingReservedFolderNeedsNoNewCreate() = runBlocking {
        server.enqueue(folder()); google().ensureDestinationFolder("dest", "Folder", googleToken)
        assertEquals("GET", server.takeRequest().method); assertEquals(1, server.requestCount)
    }
    @Test fun authorizationObjectCannotPrintItsBearerToken() {
        assertFalse(GoogleDriveAuthorizationResult.Authorized(googleToken).toString().contains(googleToken))
    }
}
