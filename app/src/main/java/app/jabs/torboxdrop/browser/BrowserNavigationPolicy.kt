package app.jabs.torboxdrop.browser

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

internal enum class BrowserNavigationAction {
    ALLOW,
    LOAD_IN_WEBVIEW,
    SEND_MAGNET,
    OPEN_EXTERNAL,
    BLOCK,
}

internal data class BrowserNavigationDecision(
    val action: BrowserNavigationAction,
    val destination: String? = null,
    val error: String? = null,
)

/** Pure navigation policy shared by both WebView callbacks and covered by JVM tests. */
internal object BrowserNavigationPolicy {
    const val UNTRUSTED_MAGNET_ERROR = "Blocked magnet link without a user gesture."
    const val UNTRUSTED_EXTERNAL_LINK_ERROR = "Blocked external link without a user gesture."
    const val UNSUPPORTED_LINK_ERROR = "Blocked unsupported link."

    fun decideWebViewNavigation(
        url: String,
        hasTrustedGesture: Boolean,
    ): BrowserNavigationDecision = when (schemeOf(url)) {
        "https" -> BrowserNavigationDecision(BrowserNavigationAction.ALLOW)
        "http" -> BrowserNavigationDecision(
            action = BrowserNavigationAction.LOAD_IN_WEBVIEW,
            destination = upgradeHttpToHttps(url),
        )
        "magnet" -> if (hasTrustedGesture) {
            BrowserNavigationDecision(
                action = BrowserNavigationAction.SEND_MAGNET,
                destination = url,
            )
        } else {
            BrowserNavigationDecision(
                action = BrowserNavigationAction.BLOCK,
                error = UNTRUSTED_MAGNET_ERROR,
            )
        }
        "mailto", "tel" -> if (hasTrustedGesture) {
            BrowserNavigationDecision(
                action = BrowserNavigationAction.OPEN_EXTERNAL,
                destination = url,
            )
        } else {
            BrowserNavigationDecision(
                action = BrowserNavigationAction.BLOCK,
                error = UNTRUSTED_EXTERNAL_LINK_ERROR,
            )
        }
        else -> BrowserNavigationDecision(
            action = BrowserNavigationAction.BLOCK,
            error = UNSUPPORTED_LINK_ERROR,
        )
    }

    fun shouldAutoSendBrowserMagnet(autoSendBrowserMagnets: Boolean): Boolean =
        autoSendBrowserMagnets

    fun normalizeHomePage(input: String, defaultHomePage: String): String =
        input.trim().ifBlank { defaultHomePage }

    /** Normalizes addresses only for browser loading. TorBox-bound input remains untouched. */
    fun normalizeBrowserAddress(input: String): String {
        val value = input.trim()
        if (value.startsWith("magnet:", ignoreCase = true)) return value
        return when (schemeOf(value)) {
            "http" -> upgradeHttpToHttps(value)
            "https" -> value
            else -> {
                val looksLikeHost = !value.contains(' ') &&
                    (value.contains('.') || value.equals("localhost", ignoreCase = true))
                if (looksLikeHost) {
                    "https://$value"
                } else {
                    "https://duckduckgo.com/?q=${encodeQuery(value)}"
                }
            }
        }
    }

    private fun upgradeHttpToHttps(url: String): String = "https:${url.substringAfter(':')}"

    private fun schemeOf(url: String): String = url
        .substringBefore(':', missingDelimiterValue = "")
        .lowercase(Locale.ROOT)

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
}
