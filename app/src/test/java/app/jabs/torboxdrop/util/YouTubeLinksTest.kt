package app.jabs.torboxdrop.util

import org.junit.Assert.*
import org.junit.Test

class YouTubeLinksTest {
    private val id = "BaW_jenozKc"
    private val canonical = "https://www.youtube.com/watch?v=$id"

    @Test fun normalizesSupportedVideoLinks() {
        listOf("https://youtu.be/$id?si=tracking", "https://youtube.com/watch?v=$id&list=PLignored&t=60",
            "https://m.youtube.com/watch?v=$id", "https://music.youtube.com/watch?v=$id",
            "https://www.youtube.com/shorts/$id?feature=share", "https://youtube.com/live/$id",
            "https://www.youtube-nocookie.com/embed/$id", "http://www.youtube.com/v/$id").forEach {
            assertEquals(canonical, YouTubeLinks.canonicalVideoUrl(it))
        }
    }
    @Test fun leavesOtherHostsAlone() {
        listOf("https://example.org/video?signature=a%2Bb", "https://youtube.com.evil.test/watch?v=$id",
            "https://youtu.be@evil.test/$id", "magnet:?xt=urn:btih:abc").forEach {
            assertNull(YouTubeLinks.canonicalVideoUrl(it))
        }
    }
    @Test fun rejectsNonVideoAndAmbiguousYouTubeLinks() {
        listOf("https://youtube.com/playlist?list=PLexample", "https://youtube.com/@channel",
            "https://youtube.com/watch?v=$id&v=$id", "https://youtu.be/bad",
            "https://user@youtube.com/watch?v=$id", "https://youtube.com:8443/watch?v=$id").forEach {
            assertThrows(IllegalArgumentException::class.java) { YouTubeLinks.canonicalVideoUrl(it) }
        }
    }
}
