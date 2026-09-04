package app.jabs.torboxdrop.browser

data class BrowserBookmarkRequest(
    val title: String,
    val url: String,
)

data class BrowserDownloadRequest(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    val contentLength: Long,
    val referrer: String? = null,
)

data class BrowserCallbacks(
    val onMagnetLink: (String) -> Unit,
    val onSendToTorBox: (String) -> Unit,
    val onBookmarkToggle: (BrowserBookmarkRequest) -> Unit,
    val onOpenExternal: (String) -> Unit,
    val onDownloadToDevice: (BrowserDownloadRequest) -> Unit,
    val onCopyLink: (String) -> Unit = {},
    val onSiteExceptionChanged: (host: String, disabled: Boolean) -> Unit = { _, _ -> },
    val onAdBlockingChanged: (Boolean) -> Unit = {},
    val onPageError: (String) -> Unit = {},
)
