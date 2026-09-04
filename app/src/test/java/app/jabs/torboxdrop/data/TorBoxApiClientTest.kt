package app.jabs.torboxdrop.data

import app.jabs.torboxdrop.model.AddOptions
import app.jabs.torboxdrop.model.DownloadType
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class TorBoxApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var api: TorBoxApiClient
    private val token = "private-api-key-123"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = TorBoxApiClient(OkHttpClient(), { token }, server.url("/v1/api/"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun bearerAuth_isUsedForNormalCalls_butNeverForRequestDownloadLink() = runTest {
        server.enqueue(success("""{"id":"user-42","email":"person@example.com","plan":2}"""))
        server.enqueue(success(""""https://cdn.example.test/file.mp4?expires=1""""))

        val account = api.getCurrentUser()
        val result = api.requestTemporaryDownloadUrl(DownloadType.TORRENT, "17", fileId = 4)

        val accountRequest = server.takeRequest()
        assertThat(accountRequest.getHeader("Authorization")).isEqualTo("Bearer $token")
        assertThat(account.userId).isEqualTo("user-42")
        val linkRequest = server.takeRequest()
        assertThat(linkRequest.getHeader("Authorization")).isNull()
        assertThat(linkRequest.requestUrl?.queryParameter("token")).isEqualTo(token)
        assertThat(linkRequest.requestUrl?.queryParameter("redirect")).isEqualTo("false")
        assertThat(result).isEqualTo("https://cdn.example.test/file.mp4?expires=1")
        assertThat(result).doesNotContain(token)
    }

    @Test
    fun relayRefreshUsesEncodedPathAndNeverAttachesCredential() = runTest {
        val relayApi = TorBoxApiClient(
            OkHttpClient(),
            { token },
            server.url("/v1/api/"),
            server.url("/relay/"),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"status\":\"ok\"}"))

        relayApi.requestLiveTorrentUpdate("user/42", "torrent 9")

        val request = server.takeRequest()
        assertThat(request.requestUrl?.encodedPath).isEqualTo("/relay/user%2F42/torrent%209")
        assertThat(request.getHeader("Authorization")).isNull()
        assertThat(request.path).doesNotContain(token)
    }

    @Test
    fun completedDisplayState_isNotTreatedAsReady_withoutBothReadinessFields() = runTest {
        server.enqueue(
            success(
                """{
                  "id": 7,
                  "name": "Still processing",
                  "download_state": "completed",
                  "progress": 1,
                  "download_finished": false,
                  "download_present": false
                }""",
            ),
        )

        val item = api.getDownload(DownloadType.TORRENT, "7", bypassCache = true)

        assertThat(item).isNotNull()
        assertThat(item!!.isReady).isFalse()
        assertThat(item.friendlyState).isEqualTo("Processing")
    }

    @Test
    fun ready_requiresDownloadFinishedAndDownloadPresent() = runTest {
        server.enqueue(
            success(
                """[
                  {"id":"1","name":"Both","download_finished":true,"download_present":true},
                  {"id":"2","name":"Only finished","download_finished":true,"download_present":false},
                  {"id":"3","name":"Only present","download_finished":false,"download_present":true}
                ]""",
            ),
        )

        val items = api.getTorrentDownloads()

        assertThat(items.associate { it.id to it.isReady })
            .containsExactly("1", true, "2", false, "3", false)
    }

    @Test
    fun omittedProgressRemainsUnknownRatherThanBecomingZero() = runTest {
        server.enqueue(success("""{"id":"p","name":"Metadata","download_state":"metaDL"}"""))

        val item = api.getDownload(DownloadType.TORRENT, "p")!!

        assertThat(item.progress).isNull()
    }

    @Test
    fun editAirLock_preservesCurrentNameTagsAndAlternativeHashes() = runTest {
        server.enqueue(
            success(
                """{
                  "id":42,
                  "name":"Keep this name",
                  "tags":["alpha","beta"],
                  "alternative_hashes":["abcd","ef01"],
                  "airlocked":false,
                  "download_finished":true,
                  "download_present":true
                }""",
            ),
        )
        server.enqueue(success("null"))

        val updated = api.editDownload(
            DownloadType.TORRENT,
            "42",
            DownloadEdit(airLocked = true),
        )

        val fetch = server.takeRequest()
        assertThat(fetch.requestUrl?.queryParameter("bypass_cache")).isEqualTo("true")
        val edit = server.takeRequest()
        assertThat(edit.method).isEqualTo("PUT")
        assertThat(edit.body.readUtf8()).isEqualTo(
            """{"torrent_id":42,"name":"Keep this name","tags":["alpha","beta"],"alternative_hashes":["abcd","ef01"],"airlocked":true}""",
        )
        assertThat(updated.name).isEqualTo("Keep this name")
        assertThat(updated.tags).containsExactly("alpha", "beta").inOrder()
        assertThat(updated.airLocked).isTrue()
    }

    @Test
    fun editWebAirLock_preservesCurrentNameTagsAndAlternativeHashes() = runTest {
        server.enqueue(
            success(
                """{
                  "id":84,
                  "name":"Keep web name",
                  "tags":["saved","video"],
                  "alternative_hashes":["web-hash"],
                  "airlocked":true,
                  "download_finished":true,
                  "download_present":true
                }""",
            ),
        )
        server.enqueue(success("null"))

        val updated = api.editDownload(
            DownloadType.WEB,
            "84",
            DownloadEdit(airLocked = false),
        )

        val fetch = server.takeRequest()
        assertThat(fetch.requestUrl?.encodedPath).isEqualTo("/v1/api/webdl/mylist")
        assertThat(fetch.requestUrl?.queryParameter("bypass_cache")).isEqualTo("true")
        val edit = server.takeRequest()
        assertThat(edit.requestUrl?.encodedPath).isEqualTo("/v1/api/webdl/editwebdownload")
        assertThat(edit.method).isEqualTo("PUT")
        assertThat(edit.body.readUtf8()).isEqualTo(
            """{"webdl_id":84,"name":"Keep web name","tags":["saved","video"],"alternative_hashes":["web-hash"],"airlocked":false}""",
        )
        assertThat(updated.name).isEqualTo("Keep web name")
        assertThat(updated.tags).containsExactly("saved", "video").inOrder()
        assertThat(updated.airLocked).isFalse()
    }

    @Test
    fun queuedFetch_requestsOnlyTorrentAndWebDownloadTypes() = runTest {
        server.enqueue(success("[]"))
        server.enqueue(success("[]"))

        assertThat(api.getQueuedDownloads()).isEmpty()

        val requests = listOf(server.takeRequest(), server.takeRequest())
        assertThat(requests.map { it.requestUrl?.queryParameter("type") })
            .containsExactly("torrent", "webdl")
            .inOrder()
        assertThat(requests.joinToString { it.path.orEmpty() }.lowercase()).doesNotContain("usenet")
    }

    @Test
    fun queueItemNotFound_isNormalizedToEmpty() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(404).setBody(
                """{"success":true,"error":"ITEM_NOT_FOUND","detail":"None","data":[]}""",
            ),
        )
        server.enqueue(success("[]"))

        assertThat(api.getQueuedDownloads()).isEmpty()
    }

    @Test
    fun queuedTorrentCreate_keepsQueueIdentitySeparateFromActiveIdentity() = runTest {
        server.enqueue(success("""{"queued_id":27,"hash":"abc123"}"""))

        val result = api.createMagnet(
            "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
            AddOptions(queued = true),
        )

        assertThat(result.id).isNull()
        assertThat(result.queuedId).isEqualTo("27")
        assertThat(result.sourceHash).isEqualTo("abc123")
        assertThat(result.queued).isTrue()
    }

    @Test
    fun pauseTorrent_postsPauseToTorrentControlEndpoint() = runTest {
        server.enqueue(success("null"))

        api.controlTorrent("42", TorrentControlOperation.PAUSE)

        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.requestUrl?.encodedPath)
            .isEqualTo("/v1/api/torrents/controltorrent")
        assertThat(request.body.readUtf8()).isEqualTo(
            """{"torrent_id":42,"operation":"pause","all":false}""",
        )
    }

    @Test
    fun temporaryUrlContainingRawOrEncodedApiToken_isRejected() {
        val encoded = URLEncoder.encode(token, Charsets.UTF_8.name())
        server.enqueue(success(""""https://cdn.example.test/file?signature=$encoded""""))

        val error = assertThrows(TorBoxUnsafeDownloadUrlException::class.java) {
            runTest { api.requestTemporaryDownloadUrl(DownloadType.WEB, "9", fileId = 2) }
        }

        assertThat(error.message).doesNotContain(token)
    }

    @Test
    fun torBoxApiOrigin_isNeverAcceptedAsTemporaryCdnUrl() {
        server.enqueue(
            success(""""https://api.torbox.app/v1/api/torrents/requestdl?redirect=true""""),
        )

        assertThrows(TorBoxUnsafeDownloadUrlException::class.java) {
            runTest { api.requestTemporaryDownloadUrl(DownloadType.TORRENT, "17", fileId = 4) }
        }
    }

    @Test
    fun tolerantParsing_handlesNumericStringsDatesAndInfectedFiles() = runTest {
        val record = """{
          "id":"99",
          "name":"Bundle",
          "download_state":"downloading",
          "progress":"0.685",
          "size":"123456",
          "download_speed":"11400000",
          "eta":"240",
          "tracker_message":"Bearer $token; retry https://tracker.test/?api_key=$token",
          "error":"Source rejected token=$token",
          "created_at":"2026-09-04T12:34:56.123+00:00",
          "download_finished":"false",
          "download_present":0,
          "files":[{
            "id":"8",
            "name":"folder/video.mp4",
            "short_name":"video.mp4",
            "absolute_path":"folder/video.mp4",
            "size":"456",
            "mimetype":"video/mp4",
            "infected":"true"
          }]
        }"""
        server.enqueue(success(record))
        server.enqueue(success(record))

        val item = api.getDownload(DownloadType.WEB, "99")!!
        val files = api.getFiles(DownloadType.WEB, "99")

        assertThat(item.progress).isEqualTo(0.685)
        assertThat(item.totalSize).isEqualTo(123456L)
        assertThat(item.downloadSpeed).isEqualTo(11400000L)
        assertThat(item.etaSeconds).isEqualTo(240L)
        assertThat(item.createdAt.toString()).isEqualTo("2026-09-04T12:34:56.123Z")
        assertThat(item.isReady).isFalse()
        assertThat(item.trackerMessage).doesNotContain(token)
        assertThat(item.error).doesNotContain(token)
        assertThat(files).hasSize(1)
        assertThat(files.single().name).isEqualTo("video.mp4")
        assertThat(files.single().path).isEqualTo("folder/video.mp4")
        assertThat(files.single().infected).isTrue()
    }

    @Test
    fun creationUsesDocumentedBodyTypesAndOptions() = runTest {
        server.enqueue(success("""{"torrent_id":10,"hash":"abc"}"""))
        server.enqueue(success("""{"webdownload_id":11,"hash":"def"}"""))

        val torrentResult = api.createTorrent(
            bytes = byteArrayOf(1, 2, 3),
            fileName = "sample.torrent",
            options = AddOptions(queued = true, cachedOnly = true, customName = "Sample", seed = 3, allowZip = false),
        )
        val webResult = api.createWebDownload(
            "https://example.test/file.zip",
            AddOptions(queued = true, cachedOnly = true, customName = "Web file"),
        )

        assertThat(torrentResult.id).isEqualTo("10")
        assertThat(torrentResult.queuedId).isNull()
        assertThat(torrentResult.sourceHash).isEqualTo("abc")
        assertThat(webResult.id).isEqualTo("11")
        assertThat(webResult.queuedId).isNull()
        assertThat(webResult.sourceHash).isEqualTo("def")

        val torrent = server.takeRequest()
        assertThat(torrent.getHeader("Content-Type")).startsWith("multipart/form-data;")
        val multipart = torrent.body.readUtf8()
        assertThat(multipart).contains("name=\"file\"; filename=\"sample.torrent\"")
        assertThat(multipart).contains("application/x-bittorrent")
        assertThat(multipart).contains("name=\"as_queued\"")
        assertThat(multipart).contains("true")
        assertThat(multipart).contains("name=\"add_only_if_cached\"")
        val web = server.takeRequest()
        assertThat(web.getHeader("Content-Type")).startsWith("application/x-www-form-urlencoded")
        assertThat(web.body.readUtf8()).contains("link=https%3A%2F%2Fexample.test%2Ffile.zip")
    }

    @Test
    fun badTokenAndRateLimit_areTypedAndDetailsAreSanitized() {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody(
                """{"success":false,"error":"BAD_TOKEN","detail":"Bearer $token was rejected"}""",
            ),
        )
        val badToken = assertThrows(TorBoxBadTokenException::class.java) {
            runTest { api.getCurrentUser() }
        }
        assertThat(badToken.message).doesNotContain(token)

        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "12").setBody(
                """{"success":false,"error":"RATE_LIMIT","detail":"Slow down"}""",
            ),
        )
        val rateLimit = assertThrows(TorBoxRateLimitException::class.java) {
            runTest { api.getCurrentUser() }
        }
        assertThat(rateLimit.retryAfterSeconds).isEqualTo(12)
    }

    @Test
    fun httpErrorsAreClassifiedBeforeRequiringJsonEnvelope() {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "9").setBody("slow down"),
        )
        val rateLimit = assertThrows(TorBoxRateLimitException::class.java) {
            runTest { api.getCurrentUser() }
        }
        assertThat(rateLimit.retryAfterSeconds).isEqualTo(9)

        server.enqueue(MockResponse().setResponseCode(502).setBody("bad gateway"))
        val gateway = assertThrows(TorBoxApiException::class.java) {
            runTest { api.getCurrentUser() }
        }
        assertThat(gateway.statusCode).isEqualTo(502)

        server.enqueue(
            MockResponse().setResponseCode(503).setBody(
                """{"success":false,"error":"SERVER_ERROR","detail":"Maintenance for $token"}""",
            ),
        )
        val maintenance = assertThrows(TorBoxApiException::class.java) {
            runTest { api.getCurrentUser() }
        }
        assertThat(maintenance.userDetail).contains("Maintenance")
        assertThat(maintenance.userDetail).doesNotContain(token)
    }

    @Test
    fun transportFailure_isTypedWithoutRetainingCredentialBearingCause() {
        val failingClient = OkHttpClient.Builder().addInterceptor {
            throw IOException("failed URL https://api.example.test/?token=$token")
        }.build()
        val failingApi = TorBoxApiClient(failingClient, { token }, server.url("/v1/api/"))

        val offline = assertThrows(TorBoxOfflineException::class.java) {
            runTest { failingApi.getCurrentUser() }
        }

        assertThat(offline.message).doesNotContain(token)
        assertThat(offline.cause).isNull()
        assertThat(offline.reason).isEqualTo("IOException")
    }

    @Test
    fun missingToken_failsBeforeAnyNetworkRequest() {
        val noTokenApi = TorBoxApiClient(OkHttpClient(), { "  " }, server.url("/v1/api/"))

        assertThrows(TorBoxMissingTokenException::class.java) {
            runTest { noTokenApi.getCurrentUser() }
        }

        assertThat(server.requestCount).isEqualTo(0)
    }

    private fun success(data: String): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"success":true,"error":null,"detail":"OK","data":$data}""")
}
