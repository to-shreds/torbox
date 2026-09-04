package app.jabs.torboxdrop.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.IDN
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Small, deterministic host blocker for WebView requests.
 *
 * Rules are hosts, not substrings. A rule for `tracker.example` matches that host and its
 * subdomains, but never `nottracker.example`. This intentionally implements only a maintainable
 * subset of filter-list syntax; cosmetic filtering and arbitrary URL regex rules do not belong in
 * a WebView client.
 */
class AdBlockEngine private constructor(
    rules: Set<String>,
    enabled: Boolean,
    siteExceptions: Set<String>,
    private val onBlockedCountChanged: (Int) -> Unit,
) {
    private val blockedHosts = rules.mapNotNull(::canonicalHost).toHashSet()
    private val enabled = AtomicBoolean(enabled)
    private val currentSite = AtomicReference<String?>(null)
    private val exceptions = ConcurrentHashMap.newKeySet<String>().apply {
        addAll(siteExceptions.mapNotNull(::canonicalHost))
    }
    private val pageBlockedCount = AtomicInteger(0)

    fun beginPage(url: String?) {
        currentSite.set(url?.let(::httpHost))
        pageBlockedCount.set(0)
        onBlockedCountChanged(0)
    }

    fun setEnabled(value: Boolean) {
        enabled.set(value)
    }

    fun isEnabled(): Boolean = enabled.get()

    fun currentSiteHost(): String? = currentSite.get()

    fun isDisabledForCurrentSite(): Boolean = currentSite.get()?.let(exceptions::contains) == true

    fun setSiteException(host: String, disabled: Boolean) {
        val canonical = canonicalHost(host) ?: return
        if (disabled) exceptions.add(canonical) else exceptions.remove(canonical)
    }

    fun currentSiteExceptions(): Set<String> = exceptions.toSet()

    fun blockedCount(): Int = pageBlockedCount.get()

    private fun matchesBlockedHost(host: String): Boolean = hostMatchesAnyRule(host, blockedHosts)

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (request.isForMainFrame || !enabled.get() || isDisabledForCurrentSite()) return null
        val scheme = request.url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = canonicalHost(request.url.host) ?: return null
        // A user who deliberately visits a ruleset host should still get a usable top-level site.
        if (host == currentSite.get()) return null
        if (!matchesBlockedHost(host)) return null

        val count = pageBlockedCount.incrementAndGet()
        onBlockedCountChanged(count)
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            204,
            "No Content",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
    }

    companion object {
        fun fromAssets(
            context: Context,
            enabled: Boolean = true,
            siteExceptions: Set<String> = emptySet(),
            onBlockedCountChanged: (Int) -> Unit = {},
        ): AdBlockEngine {
            val rules = context.assets.open("adblock_hosts.txt").bufferedReader().useLines { lines ->
                lines.mapNotNull(::parseRule).toSet()
            }
            return AdBlockEngine(rules, enabled, siteExceptions, onBlockedCountChanged)
        }

        internal fun parseRule(raw: String): String? {
            val line = raw.substringBefore('#').trim()
            if (line.isBlank() || line.startsWith('!') || line.startsWith('[')) return null
            val candidate = when {
                line.startsWith("||") -> line.removePrefix("||").substringBefore('^')
                line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ") ->
                    line.substringAfter(' ').trim().substringBefore(' ')
                else -> line.substringBefore(' ')
            }
            return canonicalHost(candidate)
        }

        internal fun canonicalHost(value: String?): String? {
            if (value.isNullOrBlank()) return null
            return runCatching {
                IDN.toASCII(value.trim().trimEnd('.').lowercase())
            }.getOrNull()?.takeIf { host ->
                host.isNotBlank() && host.length <= 253 &&
                    host.split('.').all { label ->
                        label.isNotBlank() && label.length <= 63 &&
                            label.first() != '-' && label.last() != '-' &&
                            label.all { it.isLetterOrDigit() || it == '-' }
                    }
            }
        }

        internal fun hostMatchesAnyRule(value: String?, rules: Set<String>): Boolean {
            var suffix = canonicalHost(value) ?: return false
            while (true) {
                if (suffix in rules) return true
                val separator = suffix.indexOf('.')
                if (separator < 0) return false
                suffix = suffix.substring(separator + 1)
            }
        }

        private fun httpHost(url: String): String? {
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() != "http" && uri.scheme?.lowercase() != "https") return null
            return canonicalHost(uri.host)
        }
    }
}
