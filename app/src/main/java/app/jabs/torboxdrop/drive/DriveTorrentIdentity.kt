package app.jabs.torboxdrop.drive

import java.net.URI
import java.net.URLDecoder

internal object DriveTorrentIdentity {
    fun fromMagnet(magnet: String): String? = runCatching {
        val uri = URI(magnet)
        if (!uri.scheme.equals("magnet", true)) return null
        val hashes = uri.rawSchemeSpecificPart.substringAfter('?', "").split('&').mapNotNull { pair ->
            val parts = pair.split('=', limit = 2)
            if (parts.size != 2 || !parts[0].equals("xt", true)) return@mapNotNull null
            val value = URLDecoder.decode(parts[1], "UTF-8")
            when {
                value.startsWith("urn:btih:", true) -> decodeV1(value.substring(9))
                value.startsWith("urn:btmh:1220", true) -> value.substring(13)
                    .takeIf { Regex("[a-fA-F0-9]{64}").matches(it) }?.lowercase()
                else -> null
            }
        }.distinct()
        val v1 = hashes.filter { it.length == 40 }
        val v2 = hashes.filter { it.length == 64 }
        if (v1.size > 1 || v2.size > 1) null else v1.singleOrNull() ?: v2.singleOrNull()
    }.getOrNull()

    private fun decodeV1(value: String): String? {
        if (Regex("[a-fA-F0-9]{40}").matches(value)) return value.lowercase()
        if (!Regex("[a-zA-Z2-7]{32}").matches(value)) return null
        var bits = 0
        var buffer = 0
        val bytes = mutableListOf<Int>()
        for (c in value.uppercase()) {
            buffer = (buffer shl 5) or "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c)
            bits += 5
            if (bits >= 8) { bits -= 8; bytes += (buffer shr bits) and 255 }
        }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
