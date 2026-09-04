package app.jabs.torboxdrop.util

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TorrentPayload(val bytes: ByteArray, val fileName: String)

class InvalidTorrentException(message: String) : IllegalArgumentException(message)

object TorrentPayloadReader {
    const val MAX_TORRENT_BYTES = 10 * 1024 * 1024

    suspend fun read(contentResolver: ContentResolver, uri: Uri): TorrentPayload =
        withContext(Dispatchers.IO) {
            if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
                throw InvalidTorrentException("Only secure content-provider files are accepted")
            }
            val displayName = queryDisplayName(contentResolver, uri)
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_TORRENT_BYTES) {
                        throw InvalidTorrentException("The .torrent file is larger than 10 MB")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: throw InvalidTorrentException("The selected .torrent file could not be opened")

            BencodeTorrentValidator.validate(bytes)
            TorrentPayload(bytes, sanitizeFileName(displayName))
        }

    internal fun sanitizeFileName(value: String?): String {
        val cleaned = value.orEmpty()
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cc}\\p{Cf}]+"), "_")
            .trim()
            .take(180)
            .ifBlank { "download.torrent" }
        return if (cleaned.endsWith(".torrent", ignoreCase = true)) cleaned else "$cleaned.torrent"
    }

    private fun queryDisplayName(contentResolver: ContentResolver, uri: Uri): String? =
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> cursor.displayName() }
        }.getOrNull()

    private fun Cursor.displayName(): String? {
        if (!moveToFirst()) return null
        val index = getColumnIndex(OpenableColumns.DISPLAY_NAME)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }
}

internal object BencodeTorrentValidator {
    fun validate(bytes: ByteArray) {
        if (bytes.size < 8 || bytes.firstOrNull()?.toInt()?.toChar() != 'd') {
            throw InvalidTorrentException("This is not a valid bencoded .torrent file")
        }
        val parser = Parser(bytes)
        parser.parseRootDictionary()
        if (!parser.rootHasInfo) {
            throw InvalidTorrentException("The .torrent file has no info dictionary")
        }
        if (parser.position != bytes.size) {
            throw InvalidTorrentException("Unexpected data follows the .torrent file")
        }
    }

    private class Parser(private val bytes: ByteArray) {
        var position: Int = 0
            private set
        var rootHasInfo: Boolean = false
            private set

        fun parseRootDictionary() {
            expect('d')
            while (peek() != 'e') {
                val key = parseByteString()
                if (key.contentEquals(INFO_KEY)) {
                    if (peek() != 'd') {
                        throw InvalidTorrentException("The .torrent info value is not a dictionary")
                    }
                    rootHasInfo = true
                }
                parseValue(depth = 1)
            }
            expect('e')
        }

        private fun parseValue(depth: Int) {
            if (depth > 100) throw InvalidTorrentException("The .torrent file is too deeply nested")
            when (val marker = peek()) {
                'i' -> parseInteger()
                'l' -> parseList(depth)
                'd' -> parseDictionary(depth)
                in '0'..'9' -> parseByteString()
                else -> throw InvalidTorrentException("Invalid bencode value '$marker'")
            }
        }

        private fun parseInteger() {
            expect('i')
            if (peek() == '-') position++
            val start = position
            while (peekOrNull()?.isDigit() == true) position++
            if (position == start) throw InvalidTorrentException("Invalid bencode integer")
            expect('e')
        }

        private fun parseList(depth: Int) {
            expect('l')
            while (peek() != 'e') parseValue(depth + 1)
            expect('e')
        }

        private fun parseDictionary(depth: Int) {
            expect('d')
            while (peek() != 'e') {
                parseByteString()
                parseValue(depth + 1)
            }
            expect('e')
        }

        private fun parseByteString(): ByteArray {
            val lengthStart = position
            while (peekOrNull()?.isDigit() == true) position++
            if (position == lengthStart || peekOrNull() != ':') {
                throw InvalidTorrentException("Invalid bencode byte string")
            }
            val lengthText = bytes.copyOfRange(lengthStart, position).toString(Charsets.US_ASCII)
            val length = lengthText.toLongOrNull()
                ?.takeIf { it in 0..TorrentPayloadReader.MAX_TORRENT_BYTES.toLong() }
                ?.toInt()
                ?: throw InvalidTorrentException("Invalid bencode byte-string length")
            position++
            if (position + length > bytes.size) throw InvalidTorrentException("Truncated bencode byte string")
            return bytes.copyOfRange(position, position + length).also { position += length }
        }

        private fun expect(expected: Char) {
            if (peekOrNull() != expected) throw InvalidTorrentException("Invalid bencode structure")
            position++
        }

        private fun peek(): Char = peekOrNull() ?: throw InvalidTorrentException("Truncated bencode data")

        private fun peekOrNull(): Char? = bytes.getOrNull(position)?.toInt()?.toChar()

        companion object {
            private val INFO_KEY = "info".toByteArray(Charsets.US_ASCII)
        }
    }
}
