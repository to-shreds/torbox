package app.jabs.torboxdrop.util

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class TorrentPayloadReaderTest {
    @Test
    fun validatorAcceptsMinimalStructurallyValidEnvelope() {
        BencodeTorrentValidator.validate("d4:infodee".toByteArray(Charsets.US_ASCII))
    }

    @Test
    fun validatorRejectsNonDictionaryAndMissingInfo() {
        assertThrows(InvalidTorrentException::class.java) {
            BencodeTorrentValidator.validate("l4:infoe".toByteArray(Charsets.US_ASCII))
        }
        assertThrows(InvalidTorrentException::class.java) {
            BencodeTorrentValidator.validate("d4:name4:teste".toByteArray(Charsets.US_ASCII))
        }
    }

    @Test
    fun validatorRequiresInfoValueToBeDictionary() {
        assertThrows(InvalidTorrentException::class.java) {
            BencodeTorrentValidator.validate("d4:info1:xe".toByteArray(Charsets.US_ASCII))
        }
        assertThrows(InvalidTorrentException::class.java) {
            BencodeTorrentValidator.validate("d4:infolee".toByteArray(Charsets.US_ASCII))
        }
    }

    @Test
    fun validatorRejectsTruncatedAndTrailingPayloads() {
        assertThrows(InvalidTorrentException::class.java) {
            BencodeTorrentValidator.validate("d4:infod4:name4:testee".dropLast(1).toByteArray(Charsets.US_ASCII))
        }
        assertThrows(InvalidTorrentException::class.java) {
            BencodeTorrentValidator.validate("d4:infodeejunk".toByteArray(Charsets.US_ASCII))
        }
    }

    @Test
    fun validatorRejectsOversizedDeclaredStringAndExcessiveNesting() {
        assertThrows(InvalidTorrentException::class.java) {
            BencodeTorrentValidator.validate("d4:info99999999:xe".toByteArray(Charsets.US_ASCII))
        }

        val deeplyNested = buildString {
            append("d4:info")
            repeat(101) { append('l') }
            repeat(101) { append('e') }
            append('e')
        }
        assertThrows(InvalidTorrentException::class.java) {
            BencodeTorrentValidator.validate(deeplyNested.toByteArray(Charsets.US_ASCII))
        }
    }

    @Test
    fun fileNameSanitizerRemovesPathAndControlCharacters() {
        val sanitized = TorrentPayloadReader.sanitizeFileName("../folder\\bad\u0000:name?.torrent")

        assertThat(sanitized).isEqualTo(".._folder_bad_name_.torrent")
        assertThat(sanitized).doesNotContain("/")
        assertThat(sanitized).doesNotContain("\\")
        assertThat(sanitized.none(Char::isISOControl)).isTrue()
    }

    @Test
    fun fileNameSanitizerAddsOneExtensionAndUsesSafeFallback() {
        assertThat(TorrentPayloadReader.sanitizeFileName("release")).isEqualTo("release.torrent")
        assertThat(TorrentPayloadReader.sanitizeFileName("release.TORRENT")).isEqualTo("release.TORRENT")
        assertThat(TorrentPayloadReader.sanitizeFileName(" \u0000 ")).isEqualTo("_.torrent")
        assertThat(TorrentPayloadReader.sanitizeFileName(null)).isEqualTo("download.torrent")
    }

    @Test
    fun fileNameSanitizerBoundsUntrustedDisplayName() {
        val sanitized = TorrentPayloadReader.sanitizeFileName("x".repeat(1_000))

        assertThat(sanitized.length).isAtMost(188)
        assertThat(sanitized).endsWith(".torrent")
    }

    @Test
    fun fileNameSanitizerRemovesBidiAndFormatControls() {
        val sanitized = TorrentPayloadReader.sanitizeFileName("safe\u202Egnp.torrent")

        assertThat(sanitized).isEqualTo("safe_gnp.torrent")
        assertThat(sanitized).doesNotContain("\u202E")
    }
}
