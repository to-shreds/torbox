package app.jabs.torboxdrop.util

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

object UrlSafety {
    const val REDACTED = "[REDACTED]"

    private val credentialQuery = Regex(
        "(?i)([?&](?:api[_-]?(?:key|token)|access[_-]?token|authorization)=)[^&#\\s]*",
    )
    private val bearer = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/%=-]+")
    private val credentialAssignment = Regex(
        "(?i)((?:\\\"|')?(?:api[_-]?(?:key|token)|access[_-]?token|authorization)(?:\\\"|')?\\s*[:=]\\s*(?:\\\"|')?)(?!bearer\\b)[^\\\"',\\s}&]+",
    )

    /**
     * Verifies that a URL is suitable for an external Android share intent without user override.
     * TorBox API endpoints and URLs containing the user's API token fail closed.
     */
    fun isSafeToShare(url: String?, apiToken: String?): Boolean {
        val value = url?.trim().orEmpty()
        if (value.isEmpty() || apiToken.isNullOrBlank() || containsApiToken(value, apiToken)) return false
        return runCatching {
            val uri = URI(value)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')
            scheme == "https" &&
                !host.isNullOrBlank() &&
                uri.userInfo == null &&
                host != "api.torbox.app"
        }.getOrDefault(false)
    }

    /**
     * Recognizes the narrow credential-bearing URL form TorBox uses for direct downloads.
     *
     * This does not make the URL credential-free or safe to disclose. It only lets an expected
     * TorBox storage URL reach the UI boundary where Share can require explicit user consent.
     */
    fun isExpectedTorBoxCredentialDownloadUrl(url: String?, apiToken: String?): Boolean {
        val value = url?.trim().orEmpty()
        val token = apiToken?.trim().orEmpty()
        if (value.isEmpty() || token.isEmpty() || !containsApiToken(value, token)) return false
        return runCatching {
            val uri = URI(value)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')
            if (
                scheme != "https" ||
                host.isNullOrBlank() ||
                uri.userInfo != null ||
                !isTorBoxDownloadHost(host)
            ) {
                return@runCatching false
            }
            uri.rawQuery.orEmpty().split('&').any { parameter ->
                val rawName = parameter.substringBefore('=', parameter)
                val rawValue = parameter.substringAfter('=', "")
                decodePercentPreservingPlus(rawName).equals("token", ignoreCase = true) &&
                    decodePercentPreservingPlus(rawValue) == token
            }
        }.getOrDefault(false)
    }

    /**
     * Validates a URL before it leaves the data/ViewModel layer.
     *
     * Expected TorBox storage links that contain the configured token may pass this internal
     * boundary so the Activity can ask for explicit Share consent. They are still rejected by
     * [isSafeToShare] unless that separate consent path is used.
     */
    fun requireSafeToShare(url: String, apiToken: String?): String {
        require(
            isSafeToShare(url, apiToken) || isExpectedTorBoxCredentialDownloadUrl(url, apiToken),
        ) { "Refusing to share a URL that may contain credentials" }
        return url
    }

    fun containsApiToken(text: String?, apiToken: String?): Boolean {
        val value = text.orEmpty()
        val token = apiToken?.trim().orEmpty()
        if (token.isEmpty()) return false
        val encoded = URLEncoder.encode(token, StandardCharsets.UTF_8.name())
        val percentSpaceEncoded = encoded.replace("+", "%20")
        val encodedTwice = URLEncoder.encode(encoded, StandardCharsets.UTF_8.name())
        if (value.contains(token) ||
            value.contains(encoded, ignoreCase = true) ||
            value.contains(percentSpaceEncoded, ignoreCase = true) ||
            value.contains(encodedTwice, ignoreCase = true)
        ) return true

        var decoded = value
        repeat(2) {
            val next = decodePercentPreservingPlus(decoded)
            if (next.contains(token)) return true
            if (next == decoded) return false
            decoded = next
        }
        return false
    }

    /** Removes known credentials and authorization values before text reaches UI or logs. */
    fun redact(text: String?, apiToken: String? = null): String {
        var safe: String = text ?: return ""
        val token = apiToken?.trim().orEmpty()
        if (token.isNotEmpty()) {
            val encoded = URLEncoder.encode(token, StandardCharsets.UTF_8.name())
            val variants = setOf(
                token,
                encoded,
                encoded.replace("+", "%20"),
                URLEncoder.encode(encoded, StandardCharsets.UTF_8.name()),
            )
                .filter { it.isNotEmpty() }
                .sortedByDescending { it.length }
            variants.forEach { safe = safe.replace(it, REDACTED, ignoreCase = false) }
        }
        safe = credentialQuery.replace(safe) { "${it.groupValues[1]}$REDACTED" }
        safe = bearer.replace(safe) { "${it.groupValues[1]}$REDACTED" }
        safe = credentialAssignment.replace(safe) { "${it.groupValues[1]}$REDACTED" }
        if (token.isNotEmpty() && containsApiToken(safe, token)) {
            // A partially or unusually encoded secret is safer to discard than to reproduce.
            return "Sensitive details $REDACTED"
        }
        return safe
    }

    fun sanitizedError(throwable: Throwable, apiToken: String? = null): String {
        val chain = generateSequence(throwable) { it.cause }
            .take(4)
            .mapNotNull { error ->
                error.message?.takeIf(String::isNotBlank)?.let { message ->
                    "${error.javaClass.simpleName}: $message"
                }
            }
            .joinToString(" · ")
            .ifBlank { throwable.javaClass.simpleName.ifBlank { "Unexpected error" } }
        return redact(chain, apiToken)
    }

    private fun isTorBoxDownloadHost(host: String): Boolean =
        host == "storage.torbox.app" ||
            host == "storage-zip.torbox.app" ||
            (host.startsWith("storage-") && host.endsWith(".torbox.app")) ||
            host == "cdn.torbox.app" ||
            host.endsWith(".cdn.torbox.app")

    private fun decodePercentPreservingPlus(value: String): String = runCatching {
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    }.getOrDefault(value)
}
