package app.jabs.torboxdrop.data

import app.jabs.torboxdrop.model.DownloadType
import app.jabs.torboxdrop.util.UrlSafety
import com.google.common.truth.Truth.assertThat
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Synthetic API fixtures only. These tests never contact TorBox or fetch real media. */
class RegionalCdnApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: TorBoxApiClient
    private val token = "test-only-secret+/value"
    private val encodedToken = URLEncoder.encode(token, "UTF-8")
    private val domains = listOf(
        "tb-cdn.cx", "tb-cdn.io", "tb-cdn.pw", "tb-cdn.sh",
        "tb-cdn.st", "tb-cdn.to", "tb-cdn.earth",
    )

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = TorBoxApiClient(OkHttpClient(), { token }, server.url("/v1/api/"))
    }

    @After fun tearDown() { server.shutdown() }

    private fun respondWith(url: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody(Json.stringify(Json.obj("success" to Json.boolean(true), "data" to Json.string(url)))))
    }

    @Test fun ordinaryTorrentFilesReachThePrivateDeviceBoundaryForEveryOfficialCdn() = runTest {
        domains.forEach { domain ->
            val url = "https://store-079.wnam.$domain/dld/synthetic-id?token=$encodedToken&filename=test.mkv"
            respondWith(url)
            val result = api.requestTemporaryDownloadUrl(DownloadType.TORRENT, "17", fileId = 4, appendName = true)
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertThat(request.requestUrl!!.encodedPath).isEqualTo("/v1/api/torrents/requestdl")
            assertThat(request.requestUrl!!.queryParameter("torrent_id")).isEqualTo("17")
            assertThat(request.requestUrl!!.queryParameter("file_id")).isEqualTo("4")
            assertThat(request.requestUrl!!.queryParameter("zip_link")).isEqualTo("false")
            assertThat(request.requestUrl!!.queryParameter("redirect")).isEqualTo("false")
            assertThat(request.requestUrl!!.queryParameter("append_name")).isEqualTo("true")
            assertThat(request.requestUrl!!.queryParameter("token")).isEqualTo(token)
            assertThat(request.getHeader("Authorization")).isNull()
            assertThat(result).isEqualTo(url)
            assertThat(UrlSafety.requireSafeToShare(result, token)).isEqualTo(url)
            assertThat(UrlSafety.isSafeForDeviceDownload(result, token)).isTrue()
            assertThat(UrlSafety.isSafeToShare(result, token)).isFalse()
            assertThat(UrlSafety.containsApiToken(UrlSafety.redact(result, token), token)).isFalse()
        }
        assertThat(server.requestCount).isEqualTo(domains.size)
    }

    @Test fun wholeTorrentZipPreservesItsOwnRequestAndDirectCdnDestination() = runTest {
        domains.forEach { domain ->
            val url = "https://zip.enam.$domain/dld/synthetic-zip?token=$encodedToken"
            respondWith(url)
            val result = api.requestTemporaryDownloadUrl(DownloadType.TORRENT, "23", zip = true)
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertThat(request.requestUrl!!.queryParameter("torrent_id")).isEqualTo("23")
            assertThat(request.requestUrl!!.queryParameter("file_id")).isNull()
            assertThat(request.requestUrl!!.queryParameter("zip_link")).isEqualTo("true")
            assertThat(request.requestUrl!!.queryParameter("redirect")).isEqualTo("false")
            assertThat(result).isEqualTo(url)
            assertThat(UrlSafety.isSafeForDeviceDownload(result, token)).isTrue()
            assertThat(UrlSafety.isSafeToShare(result, token)).isFalse()
        }
        assertThat(server.requestCount).isEqualTo(domains.size)
    }

    @Test fun webDownloadOriginalFormatPathStillWorksAndUsesWebIdentity() = runTest {
        domains.forEach { domain ->
            val url = "https://store-079.wnam.$domain/dld/synthetic-video?token=$encodedToken&filename=video.webm"
            respondWith(url)
            val result = api.requestTemporaryDownloadUrl(DownloadType.WEB, "83", fileId = 0)
            val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertThat(request.requestUrl!!.encodedPath).isEqualTo("/v1/api/webdl/requestdl")
            assertThat(request.requestUrl!!.queryParameter("web_id")).isEqualTo("83")
            assertThat(request.requestUrl!!.queryParameter("torrent_id")).isNull()
            assertThat(request.requestUrl!!.queryParameter("file_id")).isEqualTo("0")
            assertThat(result).isEqualTo(url)
            assertThat(UrlSafety.isSafeForDeviceDownload(result, token)).isTrue()
        }
    }

    @Test fun lookalikeAndUntrustedCredentialDestinationsStillFailClosed() = runTest {
        val hosts = domains.flatMap { listOf("store.$it.evil.test", "evil-$it", "${it}evil.test") } +
            listOf("api.torbox.app", "relay.torbox.app", "cdn.example.test")
        hosts.forEach { host ->
            respondWith("https://$host/dld/synthetic-id?token=$encodedToken")
            val failure = runCatching {
                api.requestTemporaryDownloadUrl(DownloadType.TORRENT, "17", fileId = 4)
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(TorBoxUnsafeDownloadUrlException::class.java)
            assertThat(UrlSafety.containsApiToken(failure?.message, token)).isFalse()
            assertThat(server.takeRequest(5, TimeUnit.SECONDS)).isNotNull()
        }
    }
}
