package app.jabs.torboxdrop.util

import android.app.DownloadManager
import android.content.Context
import com.google.common.truth.Truth.assertThat
import java.net.URLEncoder
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises Android's queue with synthetic URLs, not a live network or TorBox account. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class RegionalCdnDeviceDownloadsTest {
    private val token = "test-only-secret+/value"
    private val encodedToken = URLEncoder.encode(token, "UTF-8")
    private val domains = listOf(
        "tb-cdn.cx", "tb-cdn.io", "tb-cdn.pw", "tb-cdn.sh",
        "tb-cdn.st", "tb-cdn.to", "tb-cdn.earth",
    )

    @Test fun regularFilesAndWholeTorrentZipsQueueDirectlyWithoutConversionOrRender() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(DownloadManager::class.java)
        domains.forEach { domain ->
            listOf("mkv" to "video/x-matroska", "zip" to "application/zip").forEach { (extension, mime) ->
                val host = if (extension == "zip") "zip.enam.$domain" else "store-079.wnam.$domain"
                val url = "https://$host/dld/synthetic-file?token=$encodedToken&filename=example.$extension"
                assertThat(UrlSafety.isSafeToShare(url, token)).isFalse()
                assertThat(UrlSafety.isSafeForDeviceDownload(url, token)).isTrue()
                val id = DeviceDownloads.enqueue(context, url, "folder/example.$extension", mime, emptyMap())
                assertThat(id).isAtLeast(0)
                manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    val queuedUrl = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_URI))
                    assertThat(queuedUrl).isEqualTo(url)
                    assertThat(queuedUrl).doesNotContain("onrender.com")
                    val local = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    assertThat(local).contains("Download")
                    assertThat(local).endsWith(".$extension")
                    assertThat(local).doesNotContain("folder/")
                }
            }
        }
    }

    @Test fun hostNormalizationAllowsEveryOfficialFamilyButDoesNotPermitDisclosure() {
        domains.forEach { domain ->
            listOf(domain, "store-079.wnam.$domain", "zip.enam.$domain", "STORE.$domain.".uppercase(Locale.ROOT)).forEach { host ->
                val url = "https://$host/dld/synthetic-file?token=$encodedToken"
                assertThat(UrlSafety.isExpectedTorBoxCredentialDownloadUrl(url, token)).isTrue()
                assertThat(UrlSafety.requireSafeToShare(url, token)).isEqualTo(url)
                assertThat(UrlSafety.isSafeForDeviceDownload(url, token)).isTrue()
                assertThat(UrlSafety.isSafeToShare(url, token)).isFalse()
                assertThat(UrlSafety.containsApiToken(UrlSafety.redact(url, token), token)).isFalse()
            }
        }
    }

    @Test fun spoofedDestinationsAndWrongCredentialFormsRemainBlocked() {
        domains.forEach { domain ->
            listOf(
                "https://store.$domain.evil.test/file?token=$encodedToken",
                "https://evil-$domain/file?token=$encodedToken",
                "http://store.$domain/file?token=$encodedToken",
                "https://user:pass@store.$domain/file?token=$encodedToken",
                "https://store.$domain/file?signature=$encodedToken",
                "https://store.$domain/file?token=wrong&other=$encodedToken",
            ).forEach { url ->
                assertThat(UrlSafety.isSafeForDeviceDownload(url, token)).isFalse()
                assertThat(UrlSafety.isSafeToShare(url, token)).isFalse()
            }
        }
    }
}
