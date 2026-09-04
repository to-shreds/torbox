package app.jabs.torboxdrop.data

import java.io.IOException

sealed class TorBoxException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** No API token is currently available from secure storage. */
class TorBoxMissingTokenException : TorBoxException("Enter a TorBox API token to continue.")

/** TorBox authoritatively rejected the configured API token. */
class TorBoxBadTokenException internal constructor(
    val apiCode: String?,
    val statusCode: Int,
    message: String,
) : TorBoxException(message)

/** A transport failure such as no connectivity, DNS failure, or timeout. */
class TorBoxOfflineException internal constructor(cause: IOException) :
    TorBoxException("TorBox could not be reached. Check your connection and try again.") {
    /** Safe diagnostic category only. The raw IOException may contain a credential-bearing URL. */
    val reason: String = cause.javaClass.simpleName.take(80)
}

class TorBoxRateLimitException internal constructor(
    val retryAfterSeconds: Long?,
    message: String,
) : TorBoxException(message)

class TorBoxApiException internal constructor(
    val statusCode: Int,
    val apiCode: String?,
    val userDetail: String,
) : TorBoxException(userDetail)

class TorBoxInvalidResponseException internal constructor(message: String) : TorBoxException(message)

class TorBoxUnsafeDownloadUrlException internal constructor(message: String) : TorBoxException(message)

internal object TorBoxErrorSanitizer {
    private val bearer = Regex("(?i)\\bbearer\\s+[^\\s,;]+")
    private val tokenQuery = Regex("(?i)([?&](?:token|api[_-]?key)=)[^&#\\s]+")

    fun sanitize(value: String?, token: String?): String? {
        var sanitized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        sanitized = bearer.replace(sanitized, "Bearer [redacted]")
        sanitized = tokenQuery.replace(sanitized) { match -> "${match.groupValues[1]}[redacted]" }
        if (!token.isNullOrEmpty()) {
            sanitized = sanitized.replace(token, "[redacted]", ignoreCase = false)
            val decoded = runCatching { java.net.URLDecoder.decode(token, Charsets.UTF_8.name()) }.getOrNull()
            if (!decoded.isNullOrEmpty() && decoded != token) {
                sanitized = sanitized.replace(decoded, "[redacted]", ignoreCase = false)
            }
        }
        return sanitized.take(MAX_USER_DETAIL)
    }

    private const val MAX_USER_DETAIL = 500
}
