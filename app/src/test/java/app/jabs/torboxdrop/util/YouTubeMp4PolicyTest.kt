package app.jabs.torboxdrop.util

import app.jabs.torboxdrop.model.DownloadFile
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class YouTubeMp4PolicyTest {
    private fun download(source: String? = "https://youtu.be/BaW_jenozKc", ready: Boolean = true, type: DownloadType = DownloadType.WEB) =
        DownloadItem(
            id = "83", type = type, name = "Video",
            originalSource = source, downloadFinished = ready, downloadPresent = ready,
        )
    private fun file(
        name: String = "Video.webm",
        type: DownloadType = DownloadType.WEB,
        downloadId: String = "83",
        infected: Boolean = false,
    ) = DownloadFile(
        id = 902, downloadId = downloadId, downloadType = type, name = name, infected = infected,
    )

    @Test fun onlyExplicitYouTubeWebVideoFilesCanOfferMp4() {
        assertThat(YouTubeMp4Policy.eligible(download(), file())).isTrue()
        assertThat(YouTubeMp4Policy.eligible(download(), file("Video.mp4"))).isTrue()
        assertThat(YouTubeMp4Policy.eligible(download(), file("Video.txt"))).isFalse()
        assertThat(YouTubeMp4Policy.eligible(download(ready = false), file())).isFalse()
        assertThat(YouTubeMp4Policy.eligible(download(type = DownloadType.TORRENT), file(type = DownloadType.TORRENT))).isFalse()
        assertThat(YouTubeMp4Policy.eligible(download("https://example.com/video.webm"), file())).isFalse()
        assertThat(YouTubeMp4Policy.eligible(download(null), file())).isFalse()
        assertThat(YouTubeMp4Policy.eligible(download(), file(downloadId = "84"))).isFalse()
        assertThat(YouTubeMp4Policy.eligible(download(), file(infected = true))).isFalse()
    }

    @Test fun youtubeHostsMustBeRealAndCanonical() {
        assertThat(YouTubeMp4Policy.canonicalSource("https://www.youtube.com/watch?v=BaW_jenozKc"))
            .isEqualTo("https://www.youtube.com/watch?v=BaW_jenozKc")
        assertThat(YouTubeMp4Policy.canonicalSource("https://youtube.com.attacker.invalid/watch?v=BaW_jenozKc")).isNull()
        assertThat(YouTubeMp4Policy.canonicalSource("https://www.youtube.com/playlist?list=abc")).isNull()
    }

    @Test fun mp4NameReplacesExtensionRatherThanSimplyRenamingMediaBytes() {
        assertThat(YouTubeMp4Policy.outputFilename("A video.webm")).isEqualTo("A video.mp4")
        assertThat(YouTubeMp4Policy.outputFilename("../../bad:movie.webm")).isEqualTo("bad_movie.mp4")
        assertThat(YouTubeMp4Policy.outputFilename("Video.mp4")).isEqualTo("Video.mp4")
    }
}
