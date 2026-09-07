package app.jabs.torboxdrop.discover

import java.util.Locale

enum class MediaKind(val wire: String, val label: String) {
    MOVIE("movie", "Movies"), TV("series", "TV"),
}

data class DiscoveryCatalog(
    val id: String,
    val name: String,
    val kind: MediaKind,
    val genres: List<String>,
    val searchable: Boolean,
    val pageable: Boolean,
    val genreRequired: Boolean = false,
)

data class DiscoveryFilter(
    val kind: MediaKind = MediaKind.MOVIE,
    val catalogId: String = "top",
    val query: String = "",
    val genre: String = "",
    val season: Int = 1,
    val episode: Int = 1,
) {
    init { require(season in 0..999 && episode in 1..9999) }
    fun streamId(imdbId: String): String {
        require(DiscoveryRules.validImdb(imdbId))
        return if (kind == MediaKind.TV) "$imdbId:$season:$episode" else imdbId
    }
    val scope: String get() = if (kind == MediaKind.TV) "S${season} E${episode}" else "Movie"
}

data class DiscoveryTitle(val id: String, val title: String, val year: String, val genres: List<String>)
data class TorrentCandidate(val hash: String, val name: String, val sourceLabel: String)
data class CachedRelease(
    val hash: String,
    val name: String,
    val size: Long?,
    val quality: String,
    val codec: String?,
    val checkedAt: Long,
) {
    // Never forward third-party URLs, trackers, credentials, or magnet parameters.
    val magnet: String get() = "magnet:?xt=urn:btih:${requireNotNull(DiscoveryRules.hash(hash))}"
}
data class CachedTitle(val title: DiscoveryTitle, val scope: String, val releases: List<CachedRelease>)

object DiscoveryRules {
    private val hashPattern = Regex("[a-fA-F0-9]{40}")
    private val imdbPattern = Regex("tt[0-9]{5,12}")
    fun hash(value: String?): String? = value?.trim()?.takeIf { hashPattern.matches(it) }?.lowercase(Locale.ROOT)
    fun validImdb(value: String): Boolean = imdbPattern.matches(value)
    fun quality(value: String): String = when {
        Regex("(?i)(?<![a-z0-9])(2160p?|4k)(?![a-z0-9])").containsMatchIn(value) -> "4K"
        Regex("(?i)(?<![a-z0-9])1080[pi]?(?![a-z0-9])").containsMatchIn(value) -> "1080p"
        Regex("(?i)(?<![a-z0-9])720p?(?![a-z0-9])").containsMatchIn(value) -> "720p"
        Regex("(?i)(?<![a-z0-9])(480p?|576p?)(?![a-z0-9])").containsMatchIn(value) -> "SD"
        else -> "Unknown quality"
    }
    fun codec(value: String): String? = when {
        Regex("(?i)(?<![a-z0-9])(hevc|[xh][. ]?265)(?![a-z0-9])").containsMatchIn(value) -> "HEVC"
        Regex("(?i)(?<![a-z0-9])av1(?![a-z0-9])").containsMatchIn(value) -> "AV1"
        Regex("(?i)(?<![a-z0-9])(avc|[xh][. ]?264)(?![a-z0-9])").containsMatchIn(value) -> "H.264"
        else -> null
    }
    fun sizeLabel(size: Long?): String = when {
        size == null || size <= 0 -> "Size unknown"
        size >= 1_073_741_824L -> String.format(Locale.US, "%.1f GiB", size / 1_073_741_824.0)
        else -> String.format(Locale.US, "%.0f MiB", size / 1_048_576.0)
    }
    fun deduplicate(candidates: List<TorrentCandidate>): List<TorrentCandidate> = candidates.mapNotNull {
        hash(it.hash)?.let { normalized -> it.copy(hash = normalized) }
    }.distinctBy { it.hash }
}

data class DiscoveryPage(val titles: List<DiscoveryTitle>, val sourceCount: Int)

interface DiscoverySource {
    suspend fun catalogs(): List<DiscoveryCatalog>
    suspend fun titles(filter: DiscoveryFilter, catalog: DiscoveryCatalog, offset: Int): DiscoveryPage
    suspend fun candidates(filter: DiscoveryFilter, title: DiscoveryTitle): List<TorrentCandidate>
}
