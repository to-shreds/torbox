package app.jabs.torboxdrop.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SafeBrowsingResponse
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class SecureBrowserController(
    val webView: WebView,
    private val homeUrl: String,
    private val blocker: AdBlockEngine,
    private val allowThirdPartyCookies: Boolean = false,
    private val callbacks: () -> BrowserCallbacks,
    private val onLongPressedLink: (String) -> Unit,
    private val onDownloadRequested: (BrowserDownloadRequest) -> Unit,
) {
    var currentUrl by mutableStateOf("")
        private set
    var pageTitle by mutableStateOf("")
        private set
    var isLoading by mutableStateOf(false)
        private set
    var loadProgress by mutableIntStateOf(0)
        private set
    var canGoBack by mutableStateOf(false)
        private set
    var canGoForward by mutableStateOf(false)
        private set

    private var destroyed = false
    private var longPressFallback: String? = null
    private val linkResultHandler = Handler(Looper.getMainLooper()) { message ->
        val link = message.data.getString("url") ?: longPressFallback
        longPressFallback = null
        if (isShareableHttpUrl(link)) onLongPressedLink(link!!)
        true
    }

    init {
        hardenWebView()
        attachClients()
    }

    fun loadInitialUrl(url: String?) {
        if (currentUrl.isNotBlank() || destroyed) return
        loadBrowserInput(
            input = url?.takeIf(String::isNotBlank) ?: homeUrl,
            hasTrustedGesture = false,
        )
    }

    fun loadUserInput(input: String) {
        if (destroyed) return
        loadBrowserInput(input, hasTrustedGesture = true)
    }

    fun restoreState(state: Bundle?): Boolean {
        if (destroyed || state == null) return false
        val restored = webView.restoreState(state) ?: return false
        currentUrl = restored.currentItem?.url.orEmpty()
        pageTitle = restored.currentItem?.title.orEmpty()
        blocker.beginPage(currentUrl)
        updateNavigationState(webView)
        return currentUrl.isNotBlank()
    }

    fun saveState(): Bundle? {
        if (destroyed) return null
        return Bundle().takeIf { webView.saveState(it) != null }
    }

    fun goHome() = loadUserInput(homeUrl)

    fun goBack(): Boolean {
        if (!destroyed && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return false
    }

    fun goForward() {
        if (!destroyed && webView.canGoForward()) webView.goForward()
    }

    fun reloadOrStop() {
        if (destroyed) return
        if (isLoading) webView.stopLoading() else webView.reload()
    }

    fun setBlockingEnabled(enabled: Boolean) = blocker.setEnabled(enabled)

    fun setThirdPartyCookiesEnabled(enabled: Boolean) {
        if (!destroyed) CookieManager.getInstance().setAcceptThirdPartyCookies(webView, enabled)
    }

    fun setCurrentSiteException(disabled: Boolean): String? {
        val host = blocker.currentSiteHost() ?: return null
        blocker.setSiteException(host, disabled)
        if (!destroyed) webView.reload()
        return host
    }

    fun isCurrentSiteExcepted(): Boolean = blocker.isDisabledForCurrentSite()

    fun destroy() {
        if (destroyed) return
        destroyed = true
        linkResultHandler.removeCallbacksAndMessages(null)
        webView.stopLoading()
        webView.setDownloadListener(null)
        webView.setOnLongClickListener(null)
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.removeAllViews()
        webView.destroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun hardenWebView() = with(webView.settings) {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        mediaPlaybackRequiresUserGesture = true
        builtInZoomControls = true
        displayZoomControls = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, allowThirdPartyCookies)
        }
    }

    @Suppress("DEPRECATION")
    private fun attachClients() {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleNavigation(request.url.toString(), request.hasGesture())

            @Deprecated("Only used by old WebView implementations")
            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleNavigation(url, hasTrustedGesture = false)

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = blocker.intercept(request)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                blocker.beginPage(url)
                isLoading = true
                currentUrl = url.orEmpty()
                updateNavigationState(view)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                currentUrl = url.orEmpty()
                updateNavigationState(view)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                isLoading = false
                currentUrl = url.orEmpty()
                pageTitle = view.title.orEmpty()
                updateNavigationState(view)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    isLoading = false
                    callbacks().onPageError(error.description?.toString() ?: "Page failed to load")
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (request.isForMainFrame) {
                    callbacks().onPageError("HTTP ${errorResponse.statusCode}")
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError,
            ) {
                handler.cancel()
                isLoading = false
                callbacks().onPageError("Secure connection failed")
            }

            override fun onSafeBrowsingHit(
                view: WebView,
                request: WebResourceRequest,
                threatType: Int,
                callback: SafeBrowsingResponse,
            ) {
                callback.backToSafety(true)
                callbacks().onPageError("Unsafe page blocked")
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                loadProgress = newProgress.coerceIn(0, 100)
                isLoading = newProgress < 100
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                pageTitle = title.orEmpty()
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message,
            ): Boolean = false

            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback,
            ) {
                callback.invoke(origin, false, false)
            }
        }

        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            if (isShareableHttpUrl(url)) {
                onDownloadRequested(
                    BrowserDownloadRequest(
                        url = url,
                        userAgent = userAgent,
                        contentDisposition = contentDisposition,
                        mimeType = mimeType,
                        contentLength = contentLength,
                        referrer = currentUrl.takeIf { it.startsWith("https://", ignoreCase = true) },
                    ),
                )
            } else {
                callbacks().onPageError("Unsupported download address")
            }
        }

        webView.setOnLongClickListener { view ->
            val hit = (view as WebView).hitTestResult
            val candidate = hit.extra
            when (hit.type) {
                WebView.HitTestResult.ANCHOR_TYPE,
                WebView.HitTestResult.SRC_ANCHOR_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
                -> {
                    longPressFallback = candidate
                    view.requestFocusNodeHref(linkResultHandler.obtainMessage())
                    true
                }
                else -> false
            }
        }
    }

    private fun handleNavigation(url: String, hasTrustedGesture: Boolean): Boolean {
        val decision = BrowserNavigationPolicy.decideWebViewNavigation(url, hasTrustedGesture)
        return when (decision.action) {
            BrowserNavigationAction.ALLOW -> false
            BrowserNavigationAction.LOAD_IN_WEBVIEW -> {
                webView.loadUrl(checkNotNull(decision.destination))
                true
            }
            BrowserNavigationAction.SEND_MAGNET -> {
                callbacks().onMagnetLink(checkNotNull(decision.destination))
                true
            }
            BrowserNavigationAction.OPEN_EXTERNAL -> {
                callbacks().onOpenExternal(checkNotNull(decision.destination))
                true
            }
            BrowserNavigationAction.BLOCK -> {
                callbacks().onPageError(decision.error ?: BrowserNavigationPolicy.UNSUPPORTED_LINK_ERROR)
                true
            }
        }
    }

    private fun loadBrowserInput(input: String, hasTrustedGesture: Boolean) {
        val destination = normalizeAddress(input)
        if (destination.startsWith("magnet:", ignoreCase = true)) {
            handleNavigation(destination, hasTrustedGesture)
        } else {
            webView.loadUrl(destination)
        }
    }

    private fun updateNavigationState(view: WebView) {
        canGoBack = view.canGoBack()
        canGoForward = view.canGoForward()
    }

    companion object {
        fun normalizeAddress(input: String): String {
            return BrowserNavigationPolicy.normalizeBrowserAddress(input)
        }

        fun suggestedFilename(request: BrowserDownloadRequest): String =
            URLUtil.guessFileName(request.url, request.contentDisposition, request.mimeType)

        private fun isShareableHttpUrl(value: String?): Boolean {
            if (value.isNullOrBlank()) return false
            val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
            val scheme = uri.scheme?.lowercase()
            return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
        }
    }
}
