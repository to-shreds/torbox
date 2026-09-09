package app.jabs.torboxdrop.drive

import java.security.MessageDigest

/**
 * Creates a non-reversible local scope key so automation from one TorBox credential can never run
 * under a replacement credential. The API token itself is never persisted.
 */
internal fun driveAccountScope(apiToken: String?): String? {
    val token = apiToken?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
