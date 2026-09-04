package app.jabs.torboxdrop.util

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlSafetyTest {
    private val apiToken = "tb-secret+/value"

    @Test
    fun shareValidation_acceptsCdnButRejectsTokenAndApiEndpoint() {
        assertTrue(UrlSafety.isSafeToShare("https://cdn.torbox.app/file?id=temporary-signature", apiToken))
        assertFalse(UrlSafety.isSafeToShare("https://api.torbox.app/v1/api/torrents/requestdl", apiToken))
        assertFalse(UrlSafety.isSafeToShare("https://cdn.example/file?token=$apiToken", apiToken))
        assertFalse(UrlSafety.isSafeToShare("http://cdn.example/file", apiToken))
        assertFalse(UrlSafety.isSafeToShare("https://cdn.example/file", null))
        assertFalse(UrlSafety.isSafeToShare("file:///private/file", apiToken))
        assertFalse(UrlSafety.isSafeToShare("https://user:pass@cdn.example/file", apiToken))
    }

    @Test
    fun shareValidation_detectsPercentEncodedApiToken() {
        val encoded = URLEncoder.encode(apiToken, StandardCharsets.UTF_8.name())

        assertTrue(UrlSafety.containsApiToken("https://cdn.example/file?v=$encoded", apiToken))
        assertFalse(UrlSafety.isSafeToShare("https://cdn.example/file?v=$encoded", apiToken))
    }

    @Test
    fun redact_removesKnownTokenAndCredentialFields() {
        val input = "GET https://x.test/file?api_key=$apiToken&ok=1 Authorization: Bearer other-secret"

        val output = UrlSafety.redact(input, apiToken)

        assertFalse(output.contains(apiToken))
        assertFalse(output.contains("other-secret"))
        assertTrue(output.contains("api_key=[REDACTED]"))
        assertTrue(output.contains("Bearer [REDACTED]"))
    }

    @Test
    fun redact_removesEncodedTokenAndJsonCredentialWithoutKnownToken() {
        val encoded = URLEncoder.encode(apiToken, StandardCharsets.UTF_8.name())
        val input = "url=https://x.test/?v=$encoded body={\"access_token\":\"another-secret\"}"

        val output = UrlSafety.redact(input, apiToken)

        assertFalse(output.contains(apiToken))
        assertFalse(output.contains(encoded))
        assertFalse(output.contains("another-secret"))
    }

    @Test
    fun partiallyEncodedToken_failsClosedInsteadOfLeaking() {
        val partialEncoding = "tb-secret+%2Fvalue"

        assertTrue(UrlSafety.containsApiToken("https://cdn.example/file?v=$partialEncoding", apiToken))
        val output = UrlSafety.redact("Failure at $partialEncoding", apiToken)
        assertFalse(output.contains(partialEncoding))
        assertFalse(output.contains(apiToken))
    }

    @Test
    fun sanitizedError_neverLeaksApiTokenFromNestedCause() {
        val failure = IllegalStateException(
            "Request failed for https://api.torbox.app/path?token=$apiToken",
            IllegalArgumentException("Bearer $apiToken"),
        )

        val output = UrlSafety.sanitizedError(failure, apiToken)

        assertFalse(output.contains(apiToken))
        assertTrue(output.contains("[REDACTED]"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun requireSafeToShare_failsClosed() {
        UrlSafety.requireSafeToShare("https://cdn.example/file?$apiToken", apiToken)
    }
}
