package app.jabs.torboxdrop.util

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Only canonicalizes a single public video URL. Never follows an arbitrary redirect. */
object YouTubeLinks {
    private val hosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be", "youtube-nocookie.com", "www.youtube-nocookie.com")
    private val videoId = Regex("[A-Za-z0-9_-]{11}")

    /** Null means a different site. A malformed YouTube URL is rejected rather than queued. */
    fun canonicalVideoUrl(value: String): String? {
        val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        if (host !in hosts) return null
        require(uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && uri.userInfo == null && uri.port in setOf(-1, 80, 443)) {
            "Use a normal HTTPS YouTube video link."
        }
        val path = uri.path.orEmpty().trimEnd('/')
        val parts = path.trimStart('/').split('/')
        val query = uri.rawQuery.orEmpty().split('&').map {
            val key = URLDecoder.decode(it.substringBefore('='), "UTF-8")
            val text = URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            key to text
        }
        val id = when {
            host == "youtu.be" || host == "www.youtu.be" -> parts.singleOrNull()
            path == "/watch" && query.count { it.first == "v" } == 1 -> query.first { it.first == "v" }.second
            parts.size == 2 && parts[0] in setOf("shorts", "live", "embed", "v") -> parts[1]
            else -> null
        }
        require(id != null && videoId.matches(id)) {
            "Paste a link to one YouTube video (watch, share, Shorts or recorded live video), not a playlist or channel."
        }
        return "https://www.youtube.com/watch?v=$id"
    }
}
