package app.jabs.torboxdrop.util

import app.jabs.torboxdrop.model.DownloadType
import java.net.URI
import java.util.Locale

/** Pure parsing helpers for text and file intents. */
object InputParser {
    enum class Kind { MAGNET, HTTP_URL }

    data class ParsedLink(
        val value: String,
        val kind: Kind,
    ) {
        val downloadType: DownloadType
            get() = if (kind == Kind.MAGNET) DownloadType.TORRENT else DownloadType.WEB
    }

    private val candidatePattern = Regex(
        pattern = "(?i)(?<![a-z0-9+.:_-])(?:magnet:\\?[^\\s<>\\\"']+|https?://[^\\s<>\\\"']+)",
    )

    private val torrentMimeTypes = setOf(
        "application/x-bittorrent",
        "application/x-torrent",
        "application/vnd.bittorrent",
    )

    /** Returns supported links in encounter order, de-duplicated without changing their spelling. */
    fun extractLinks(text: String?): List<ParsedLink> {
        if (text.isNullOrBlank()) return emptyList()

        val seen = linkedSetOf<String>()
        return candidatePattern.findAll(text)
            .mapNotNull { classify(trimSharePunctuation(it.value)) }
            .filter { seen.add(it.value) }
            .toList()
    }

    fun firstLink(text: String?): ParsedLink? = extractLinks(text).firstOrNull()

    fun classify(candidate: String?): ParsedLink? {
        val value = candidate?.trim()?.let(::trimSharePunctuation).orEmpty()
        if (value.isEmpty()) return null
        return when {
            isMagnet(value) -> ParsedLink(value, Kind.MAGNET)
            isHttpUrl(value) -> ParsedLink(value, Kind.HTTP_URL)
            else -> null
        }
    }

    fun isMagnet(candidate: String?): Boolean {
        val value = candidate?.trim().orEmpty()
        if (!value.startsWith("magnet:?", ignoreCase = true)) return false
        return runCatching {
            val uri = URI(value)
            val schemeSpecific = uri.rawSchemeSpecificPart.orEmpty()
            uri.scheme.equals("magnet", ignoreCase = true) &&
                schemeSpecific.startsWith('?') &&
                schemeSpecific.length > 1
        }.getOrDefault(false)
    }

    fun isHttpUrl(candidate: String?): Boolean {
        val value = candidate?.trim().orEmpty()
        return runCatching {
            val uri = URI(value)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            (scheme == "http" || scheme == "https") &&
                !uri.rawAuthority.isNullOrBlank() &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null
        }.getOrDefault(false)
    }

    fun isTorrentFile(displayName: String?, mimeType: String?): Boolean {
        val normalizedMime = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
        if (normalizedMime != null && normalizedMime in torrentMimeTypes) return true

        val cleanName = displayName
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?.trim()
        return cleanName?.endsWith(".torrent", ignoreCase = true) == true
    }

    fun isTorrentMimeType(mimeType: String?): Boolean {
        val normalized = mimeType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
        return normalized != null && normalized in torrentMimeTypes
    }

    private fun trimSharePunctuation(input: String): String {
        var value = input.trim()
        while (value.lastOrNull()?.let { it in TRAILING_PUNCTUATION } == true) {
            // A magnet's query delimiter is never trailing in a valid magnet, so this is safe.
            value = value.dropLast(1)
        }
        value = trimUnbalancedCloser(value, '(', ')')
        value = trimUnbalancedCloser(value, '[', ']')
        value = trimUnbalancedCloser(value, '{', '}')
        return value
    }

    private fun trimUnbalancedCloser(value: String, opener: Char, closer: Char): String {
        var result = value
        while (result.endsWith(closer) && result.count { it == closer } > result.count { it == opener }) {
            result = result.dropLast(1)
        }
        return result
    }

    private val TRAILING_PUNCTUATION = setOf('.', ',', ';', '!', '?')
}
