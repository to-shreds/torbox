package app.jabs.torboxdrop.browser

import app.jabs.torboxdrop.util.InputParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BrowserNavigationPolicyTest {
    @Test
    fun trustedWebViewMagnet_isSentToNativeFlow() {
        val magnet = "magnet:?xt=urn:btih:abcdef"

        val decision = BrowserNavigationPolicy.decideWebViewNavigation(
            url = magnet,
            hasTrustedGesture = true,
        )

        assertThat(decision.action).isEqualTo(BrowserNavigationAction.SEND_MAGNET)
        assertThat(decision.destination).isEqualTo(magnet)
    }

    @Test
    fun scriptedWebViewMagnet_isBlockedWithClearError() {
        val decision = BrowserNavigationPolicy.decideWebViewNavigation(
            url = "magnet:?xt=urn:btih:abcdef",
            hasTrustedGesture = false,
        )

        assertThat(decision.action).isEqualTo(BrowserNavigationAction.BLOCK)
        assertThat(decision.error).isEqualTo(BrowserNavigationPolicy.UNTRUSTED_MAGNET_ERROR)
    }

    @Test
    fun legacyCallbackMagnet_failsClosedWithoutGestureEvidence() {
        val decision = BrowserNavigationPolicy.decideWebViewNavigation(
            url = "MAGNET:?xt=urn:btih:abcdef",
            hasTrustedGesture = false,
        )

        assertThat(decision.action).isEqualTo(BrowserNavigationAction.BLOCK)
    }

    @Test
    fun httpNavigation_isUpgradedButRawTorBoxInputIsPreserved() {
        val raw = "http://example.com/file?id=42"

        val navigation = BrowserNavigationPolicy.decideWebViewNavigation(
            url = raw,
            hasTrustedGesture = true,
        )

        assertThat(navigation.action).isEqualTo(BrowserNavigationAction.LOAD_IN_WEBVIEW)
        assertThat(navigation.destination).isEqualTo("https://example.com/file?id=42")
        assertThat(InputParser.classify(raw)?.value).isEqualTo(raw)
    }

    @Test
    fun typedHttpAddress_isUpgradedBeforeLoading() {
        assertThat(BrowserNavigationPolicy.normalizeBrowserAddress(" HTTP://example.com/path "))
            .isEqualTo("https://example.com/path")
    }

    @Test
    fun httpsNavigation_isAllowedWithoutRewriting() {
        val decision = BrowserNavigationPolicy.decideWebViewNavigation(
            url = "https://example.com/path",
            hasTrustedGesture = false,
        )

        assertThat(decision.action).isEqualTo(BrowserNavigationAction.ALLOW)
        assertThat(decision.destination).isNull()
    }

    @Test
    fun browserAutoSend_requiresBothInstantModeAndNoConfirmation() {
        assertThat(BrowserNavigationPolicy.shouldAutoSendBrowserMagnet(true, false)).isTrue()
        assertThat(BrowserNavigationPolicy.shouldAutoSendBrowserMagnet(true, true)).isFalse()
        assertThat(BrowserNavigationPolicy.shouldAutoSendBrowserMagnet(false, false)).isFalse()
        assertThat(BrowserNavigationPolicy.shouldAutoSendBrowserMagnet(false, true)).isFalse()
    }

    @Test
    fun blankHomePage_usesDefaultInLiveAndPersistedState() {
        assertThat(
            BrowserNavigationPolicy.normalizeHomePage(
                input = "   ",
                defaultHomePage = "https://default.example",
            ),
        ).isEqualTo("https://default.example")
    }

    @Test
    fun unsupportedWebViewScheme_isBlocked() {
        val decision = BrowserNavigationPolicy.decideWebViewNavigation(
            url = "javascript:alert(1)",
            hasTrustedGesture = true,
        )

        assertThat(decision.action).isEqualTo(BrowserNavigationAction.BLOCK)
        assertThat(decision.error).isEqualTo(BrowserNavigationPolicy.UNSUPPORTED_LINK_ERROR)
    }

    @Test
    fun trustedExternalSchemes_areHandedToAndroid() {
        listOf("mailto:person@example.com", "tel:+15551234567").forEach { url ->
            val decision = BrowserNavigationPolicy.decideWebViewNavigation(
                url = url,
                hasTrustedGesture = true,
            )

            assertThat(decision.action).isEqualTo(BrowserNavigationAction.OPEN_EXTERNAL)
            assertThat(decision.destination).isEqualTo(url)
        }
    }

    @Test
    fun scriptedExternalSchemes_areBlockedWithClearError() {
        listOf("mailto:person@example.com", "tel:+15551234567").forEach { url ->
            val decision = BrowserNavigationPolicy.decideWebViewNavigation(
                url = url,
                hasTrustedGesture = false,
            )

            assertThat(decision.action).isEqualTo(BrowserNavigationAction.BLOCK)
            assertThat(decision.error)
                .isEqualTo(BrowserNavigationPolicy.UNTRUSTED_EXTERNAL_LINK_ERROR)
        }
    }
}
