package app.jabs.torboxdrop.data

import app.jabs.torboxdrop.model.DownloadType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class TorBoxCredentialDownloadUrlTest {
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
    fun expectedTorBoxStorageUrlContainingConfiguredToken_reachesConsentBoundary() = runTest {
        val downloadUrl = "https://storage.torbox.app/dld/presigned-key?token=$token"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"success":true,"data":"$downloadUrl"}"""),
        )

        val result = api.requestTemporaryDownloadUrl(
            DownloadType.TORRENT,
            "17",
            fileId = 4,
        )

        assertThat(result).isEqualTo(downloadUrl)
        val request = server.takeRequest()
        assertThat(request.requestUrl?.queryParameter("redirect")).isEqualTo("false")
        assertThat(request.requestUrl?.queryParameter("token")).isEqualTo(token)
    }
}
