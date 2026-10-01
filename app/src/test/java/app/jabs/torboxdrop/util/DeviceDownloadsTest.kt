package app.jabs.torboxdrop.util

import android.app.DownloadManager
import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DeviceDownloadsTest {
    @Test fun trustedCredentialLinkQueuesInAndroidRatherThanBeingSilentlyBlocked() {
        val context = RuntimeEnvironment.getApplication()
        val token = "test-only-token"
        val url = "https://storage-a.torbox.app/file?token=$token"
        assertThat(UrlSafety.isSafeToShare(url, token)).isFalse()
        assertThat(UrlSafety.isSafeForDeviceDownload(url, token)).isTrue()
        val download = DeviceDownloads.enqueue(context, url, "folder/movie.mp4", "video/mp4", emptyMap())
        assertThat(download).isGreaterThan(0)
        context.getSystemService(DownloadManager::class.java).query(DownloadManager.Query().setFilterById(download)).use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_URI))).isEqualTo(url)
            val local = it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            assertThat(local).contains("Download")
            assertThat(local).endsWith(".mp4")
            assertThat(local).doesNotContain("folder/")
        }
    }
    @Test fun rejectsLookalikeHostAndCredentialRedirects() {
        val key = "test-only-token"
        listOf("http://storage.torbox.app/?token=$key", "https://storage.torbox.app.evil.test/?token=$key", "https://api.torbox.app/?token=$key", "https://evil.test/$key")
            .forEach { assertThat(UrlSafety.isSafeForDeviceDownload(it, key)).isFalse() }
    }
    @Test fun namesAreSafeUniqueAndKeepExtensionsForVeryLongUnicodeNames() {
        val value = "../folder/" + "電".repeat(200) + ".mkv"
        val a = DeviceDownloads.safeName(value)
        val b = DeviceDownloads.safeName(value)
        assertThat(a).isNotEqualTo(b)
        assertThat(a).endsWith(".mkv")
        assertThat(a.toByteArray(Charsets.UTF_8).size).isLessThan(255)
        assertThat(a).doesNotContain("/")
        assertThat(DeviceDownloads.safeName("\u0000bad:name?.mp4", "fixed")).isEqualTo("_bad_name_-fixed.mp4")
    }
    @Test fun enqueueRejectsCleartextAndHeaderInjection() {
        val context = RuntimeEnvironment.getApplication()
        assertThat(runCatching { DeviceDownloads.enqueue(context, "http://example.test/x", "x", null, emptyMap()) }.isFailure).isTrue()
        assertThat(runCatching { DeviceDownloads.enqueue(context, "https://example.test/x", "x", null, mapOf("X-Test" to "a\r\nInjected: yes")) }.isFailure).isTrue()
    }
    @Test fun failuresHaveActionableVisibleMessages() {
        assertThat(DeviceDownloads.failureMessage(DownloadManager.ERROR_INSUFFICIENT_SPACE)).contains("out of storage")
        assertThat(DeviceDownloads.failureMessage(403)).contains("fresh link")
        assertThat(DeviceDownloads.failureMessage(500)).contains("retry")
    }
}
