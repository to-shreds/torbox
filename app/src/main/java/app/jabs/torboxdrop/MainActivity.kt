package app.jabs.torboxdrop

import android.Manifest
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.jabs.torboxdrop.browser.BrowserCallbacks
import app.jabs.torboxdrop.browser.BrowserDownloadRequest
import app.jabs.torboxdrop.browser.BrowserScreen
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import app.jabs.torboxdrop.model.IncomingAdd
import app.jabs.torboxdrop.notifications.CompletionNotifications
import app.jabs.torboxdrop.notifications.CompletionNotificationChannels
import app.jabs.torboxdrop.notifications.NotificationCapabilities
import app.jabs.torboxdrop.notifications.NotificationCapabilityIssue
import app.jabs.torboxdrop.notifications.NotificationCapabilityUse
import app.jabs.torboxdrop.ui.DownloadsScreen
import app.jabs.torboxdrop.ui.screens.AddScreen
import app.jabs.torboxdrop.ui.screens.DownloadDetailScreen
import app.jabs.torboxdrop.ui.screens.FileSelectionSheet
import app.jabs.torboxdrop.ui.screens.SettingsScreen
import app.jabs.torboxdrop.ui.theme.TorBoxDropTheme
import app.jabs.torboxdrop.util.UrlSafety
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.suspendCancellableCoroutine

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val clipboard by lazy { getSystemService(ClipboardManager::class.java) }
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { captureClipboard() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            TorBoxDropTheme {
                TorBoxDropRoot(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        viewModel.setForeground(true)
        clipboard.addPrimaryClipChangedListener(clipboardListener)
        captureClipboard()
    }

    override fun onStop() {
        clipboard.removePrimaryClipChangedListener(clipboardListener)
        viewModel.setForeground(false)
        super.onStop()
    }

    private fun handleIntent(incoming: Intent?) {
        incoming ?: return
        val action = incoming.action.orEmpty()
        val text = if (action == Intent.ACTION_SEND) {
            incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        } else null
        val stream = if (action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(incoming, Intent.EXTRA_STREAM, Uri::class.java)
                ?: incoming.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        } else null
        val data = incoming.data
        when {
            action == CompletionNotifications.ACTION_OPEN_FILES &&
                data?.scheme == "torboxdrop" &&
                data.host == "files" &&
                data.pathSegments.size == 2 -> {
                val segments = data?.pathSegments.orEmpty()
                val type = when (segments.getOrNull(0)?.lowercase(Locale.ROOT)) {
                    "torrent" -> DownloadType.TORRENT
                    "web" -> DownloadType.WEB
                    else -> null
                }
                val id = segments.getOrNull(1)
                if (type != null && !id.isNullOrBlank()) viewModel.openFilesFromNotification(type, id)
            }
            action == Intent.ACTION_VIEW && data?.scheme.equals("magnet", ignoreCase = true) -> {
                viewModel.receiveText(data.toString(), "deep-link")
            }
            action == Intent.ACTION_VIEW && data?.scheme == "content" && isTorrentStream(incoming.type, data) -> {
                viewModel.receiveTorrentUri(data, "view-intent")
            }
            action == Intent.ACTION_SEND && stream != null && isTorrentStream(incoming.type, stream) -> {
                viewModel.receiveTorrentUri(stream, "share-file")
            }
            action == Intent.ACTION_SEND && !text.isNullOrBlank() -> {
                viewModel.receiveText(text, "android-share")
            }
        }
    }

    private fun isTorrentStream(intentType: String?, uri: Uri): Boolean {
        val mime = intentType ?: runCatching { contentResolver.getType(uri) }.getOrNull()
        if (mime?.lowercase(Locale.ROOT) in TORRENT_MIME_TYPES) return true
        val displayName = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        return displayName?.endsWith(".torrent", ignoreCase = true) == true ||
            uri.lastPathSegment?.endsWith(".torrent", ignoreCase = true) == true
    }

    private fun captureClipboard() {
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return
        if (clip.itemCount == 0) return
        val text = runCatching { clip.getItemAt(0).coerceToText(this).toString().trim() }.getOrNull().orEmpty()
        if (text.isBlank()) return
        val fingerprint = "clipboard:${text.hashCode()}:${text.length}"
        if (viewModel.consumeIncomingFingerprint(fingerprint)) viewModel.onClipboardText(text)
    }

    private companion object {
        val TORRENT_MIME_TYPES = setOf(
            "application/x-bittorrent",
            "application/x-torrent",
            "application/vnd.bittorrent",
            "application/bittorrent",
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TorBoxDropRoot(viewModel: MainViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val destinationStateHolder = rememberSaveableStateHolder()

    fun allowsExternalUrl(url: String, required: Boolean): Boolean {
        if (!required) return true
        val token = (context.applicationContext as TorBoxDropApplication).container.tokenStore.read()
        return UrlSafety.isSafeToShare(url, token)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.completeNotificationPermission(granted)
    }

    fun requestNotificationPermission(action: PendingNotificationAction) {
        CompletionNotificationChannels.ensureCreated(context)
        when (
            NotificationCapabilities.current(context)
                .blockingIssue(NotificationCapabilityUse.COMPLETION_ALERTS)
        ) {
            null -> when (action) {
                is PendingNotificationAction.Watch -> viewModel.toggleWatch(action.item)
                is PendingNotificationAction.AddDefaults -> viewModel.setAddOptions(action.options)
                is PendingNotificationAction.Settings -> viewModel.updateSettings(action.state)
            }
            NotificationCapabilityIssue.RUNTIME_PERMISSION_REQUIRED -> {
                viewModel.rememberNotificationPermissionAction(action)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    viewModel.completeNotificationPermission(granted = true)
                }
            }
            else -> {
                NotificationCapabilities.settingsIntent(
                    context,
                    NotificationCapabilityUse.COMPLETION_ALERTS,
                )?.let { runCatching { context.startActivity(it) } }
                viewModel.emitMessage("Enable TorBox Drop completion alerts in Android settings, then try again.")
            }
        }
    }

    val torrentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.receiveTorrentUri(it, "file-picker") } }

    LaunchedEffect(viewModel) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is MainEvent.Message -> snackbarHostState.showSnackbar(event.text)
                is MainEvent.ShareText -> {
                    if (!allowsExternalUrl(event.text, event.requiresTorBoxUrlSafety)) {
                        snackbarHostState.showSnackbar("Unsafe credential-bearing link was blocked.")
                    } else runCatching {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, event.text)
                                    putExtra(Intent.EXTRA_TITLE, event.title)
                                },
                                event.title,
                            ),
                        )
                    }.onFailure { snackbarHostState.showSnackbar("No compatible sharing app is available.") }
                }
                is MainEvent.CopyText -> {
                    if (!allowsExternalUrl(event.text, event.requiresTorBoxUrlSafety)) {
                        snackbarHostState.showSnackbar("Unsafe credential-bearing link was blocked.")
                    } else {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText(event.label, event.text))
                        snackbarHostState.showSnackbar("Temporary link copied")
                    }
                }
                is MainEvent.DownloadToDevice -> {
                    if (!allowsExternalUrl(event.url, event.requiresTorBoxUrlSafety)) {
                        snackbarHostState.showSnackbar("Unsafe credential-bearing link was blocked.")
                    } else {
                        runCatching { enqueueDownload(context, event) }
                            .onSuccess { snackbarHostState.showSnackbar("Download queued in Android Downloads") }
                            .onFailure { snackbarHostState.showSnackbar("Android could not queue that download.") }
                    }
                }
                is MainEvent.OpenUri -> {
                    if (!allowsExternalUrl(event.url, event.requiresTorBoxUrlSafety)) {
                        snackbarHostState.showSnackbar("Unsafe credential-bearing link was blocked.")
                    } else runCatching { openUri(context, event) }
                        .onFailure { snackbarHostState.showSnackbar("No compatible app is available.") }
                }
                MainEvent.ClearBrowserData -> {
                    clearBrowserData(context)
                    snackbarHostState.showSnackbar("Browser data cleared")
                }
                MainEvent.OpenNotificationSettings -> {
                    CompletionNotificationChannels.ensureCreated(context)
                    val intent = NotificationCapabilities.settingsIntent(
                        context,
                        NotificationCapabilityUse.COMPLETION_ALERTS,
                    ) ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    } else {
                        Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                    }
                    runCatching { context.startActivity(intent) }
                }
            }
        }
    }

    if (state.selectedDownload != null) {
        val item = state.selectedDownload!!
        DownloadDetailScreen(
            download = item,
            onBack = viewModel::closeDetail,
            onFiles = { viewModel.openFiles(item) },
            onShare = { viewModel.openFiles(item, shareIfSingle = true) },
            onDownload = { viewModel.openFiles(item) },
            onOpen = { viewModel.openFiles(item) },
            onRename = if (item.isReady) {
                { name -> viewModel.rename(item, name) }
            } else null,
            onUpdateTags = if (item.isReady) {
                { tags -> viewModel.updateTags(item, tags) }
            } else null,
            onSetAirLocked = if (item.isReady || item.airLocked) {
                { enabled -> viewModel.setAirLock(item, enabled) }
            } else null,
            onReannounce = if (item.type == DownloadType.TORRENT) {
                { viewModel.reannounce(item) }
            } else null,
            onPause = if (item.type == DownloadType.TORRENT && item.canPause()) {
                { viewModel.pause(item) }
            } else null,
            onResume = if (item.type == DownloadType.TORRENT && item.canResume()) {
                { viewModel.resume(item) }
            } else null,
            onDelete = { viewModel.delete(item) },
            actionInProgress = state.actionInProgress,
        )
    } else {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                CompactBottomNavigation(
                    selected = state.destination,
                    onSelected = viewModel::navigate,
                )
            },
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                destinationStateHolder.SaveableStateProvider(state.destination.name) {
                    when (state.destination) {
                    AppDestination.DOWNLOADS -> DownloadsScreen(
                        state = state.downloads,
                        watchedDownloadKeys = state.downloads.downloads
                            .filter { "${it.type.name}:${it.id}" in state.watchedKeys }
                            .map { it.type to it.id }
                            .toSet(),
                        downloadKeysMatchingLoadedFiles = loadedFileMatches(state),
                        onRefresh = { viewModel.refresh() },
                        onTabSelected = viewModel::selectTab,
                        onDensityChanged = viewModel::setDensity,
                        onSearchChanged = viewModel::setSearch,
                        onSortChanged = viewModel::setSort,
                        onFilterChanged = viewModel::setFilter,
                        onDownloadClick = viewModel::openDetail,
                        onToggleNotification = { item ->
                            if ("${item.type.name}:${item.id}" in state.watchedKeys) {
                                viewModel.toggleWatch(item)
                            } else {
                                requestNotificationPermission(PendingNotificationAction.Watch(item))
                            }
                        },
                        onShare = { viewModel.openFiles(it, shareIfSingle = true) },
                        onFiles = viewModel::openFiles,
                        onToggleAirLock = { viewModel.setAirLock(it, !it.airLocked) },
                        onDownloadMenu = viewModel::openDetail,
                        onStartQueued = viewModel::startQueued,
                        onDeleteQueued = viewModel::deleteQueued,
                    )
                    AppDestination.ADD -> AddScreen(
                        candidate = state.addCandidate,
                        pendingTorrentName = state.pendingTorrentName,
                        options = state.addOptions,
                        recentSends = state.recentSends,
                        onCandidateChange = viewModel::setAddCandidate,
                        onOptionsChange = { next ->
                            if (next.notifyWhenComplete && !state.addOptions.notifyWhenComplete) {
                                requestNotificationPermission(PendingNotificationAction.AddDefaults(next))
                            } else viewModel.setAddOptions(next)
                        },
                        onSubmit = viewModel::submit,
                        onSubmitTorrent = viewModel::submitPendingTorrent,
                        onDiscardTorrent = viewModel::discardPendingTorrent,
                        onPickTorrent = { options ->
                            viewModel.setAddOptions(options)
                            torrentPicker.launch(
                                arrayOf(
                                    "application/x-bittorrent",
                                    "application/x-torrent",
                                    "application/vnd.bittorrent",
                                    "application/bittorrent",
                                    "application/octet-stream",
                                ),
                            )
                        },
                        onPasteRequested = {
                            val manager = context.getSystemService(ClipboardManager::class.java)
                            val value = manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                            viewModel.setAddCandidate(value)
                        },
                        onRecentSendSelected = { viewModel.setAddCandidate(it.value) },
                        onClearRecent = viewModel::clearRecentHistory,
                        submitting = state.addSubmitting,
                        result = state.addResult,
                        errorMessage = state.addError,
                        onDismissMessage = viewModel::dismissAddMessage,
                    )
                    AppDestination.BROWSER -> key(state.browserGeneration) {
                        BrowserScreen(
                            callbacks = BrowserCallbacks(
                                onMagnetLink = viewModel::receiveTrustedBrowserMagnet,
                                onSendToTorBox = { viewModel.receiveText(it, "browser", remainInBrowser = true) },
                                onBookmarkToggle = { request ->
                                    viewModel.addOrRemoveBookmark(request.title, request.url)
                                },
                                onOpenExternal = { viewModel.openExternal(it, chooserTitle = "Open in browser") },
                                onDownloadToDevice = { request ->
                                    val name = URLUtil.guessFileName(
                                        request.url,
                                        request.contentDisposition,
                                        request.mimeType,
                                    )
                                    viewModel.downloadBrowserUrl(
                                        request.url,
                                        name,
                                        request.mimeType,
                                        userAgent = request.userAgent,
                                        cookies = CookieManager.getInstance().getCookie(request.url),
                                        referrer = request.referrer,
                                    )
                                },
                                onCopyLink = { eventText ->
                                    context.getSystemService(ClipboardManager::class.java)
                                        .setPrimaryClip(ClipData.newPlainText("Browser link", eventText))
                                },
                                onSiteExceptionChanged = viewModel::setSiteException,
                                onAdBlockingChanged = viewModel::setAdBlocking,
                                onPageError = viewModel::emitMessage,
                            ),
                            initialUrl = state.browserRequestedUrl,
                            homeUrl = state.settings.browserHomePage,
                            bookmarkedUrls = state.bookmarks.map { it.url }.toSet(),
                            adBlockingInitiallyEnabled = state.settings.blockAdsAndTrackers,
                            allowThirdPartyCookies = state.settings.allowThirdPartyCookies,
                            siteExceptions = (context.applicationContext as TorBoxDropApplication)
                                .container.preferences.siteExceptions(),
                            restoredWebViewState = viewModel.browserWebViewState(state.browserGeneration),
                            onWebViewStateSaved = { savedState ->
                                viewModel.saveBrowserWebViewState(state.browserGeneration, savedState)
                            },
                        )
                    }
                    AppDestination.SETTINGS -> SettingsScreen(
                        state = state.settings,
                        onSettingsChange = { next ->
                            val enablingNotifications = next.defaultAddOptions.notifyWhenComplete &&
                                !state.settings.defaultAddOptions.notifyWhenComplete
                            if (enablingNotifications) {
                                requestNotificationPermission(PendingNotificationAction.Settings(next))
                            } else viewModel.updateSettings(next)
                        },
                        onValidateToken = viewModel::validateCurrentToken,
                        onReplaceToken = viewModel::replaceToken,
                        onDisconnectAccount = viewModel::disconnectAccount,
                        onOpenAccount = { viewModel.openExternal("https://torbox.app/settings") },
                        onOpenBrowserHome = { viewModel.openBookmark(state.settings.browserHomePage) },
                        onManageBookmarks = { viewModel.showBookmarks(true) },
                        onOpenNotificationSettings = viewModel::openNotificationSettings,
                        onExportDiagnostics = viewModel::exportDiagnostics,
                        onClearRecentHistory = viewModel::clearRecentHistory,
                        onClearBrowserData = viewModel::clearBrowserData,
                        onClearCachedDownloadMetadata = viewModel::clearCachedMetadata,
                        onResetSettings = viewModel::resetSettings,
                    )
                    }
                }
            }
        }
    }

    state.fileSheet?.let { sheet ->
        FileSelectionSheet(
            download = sheet.download,
            files = sheet.files,
            onDismissRequest = viewModel::closeFiles,
            isLoading = sheet.loading,
            error = sheet.error,
            onRetry = viewModel::retryFiles,
            onOpenFile = viewModel::openFile,
            onDownloadFile = { viewModel.downloadFile(it, infectedConfirmed = it.infected) },
            onShareFile = viewModel::shareFile,
            onCopyTemporaryLink = viewModel::copyTemporaryLink,
            onDownloadZip = if (sheet.download.type == DownloadType.TORRENT && sheet.download.allowZip == true) {
                { viewModel.downloadZip(sheet.download) }
            } else null,
        )
    }

    if (state.showBookmarks) {
        BookmarksDialog(
            bookmarks = state.bookmarks,
            onDismiss = { viewModel.showBookmarks(false) },
            onOpen = viewModel::openBookmark,
            onRemove = viewModel::removeBookmark,
        )
    }

    state.pendingBrowserMagnet?.let { magnet ->
        BrowserMagnetConfirmationDialog(
            magnet = magnet,
            onDismiss = viewModel::dismissBrowserMagnet,
            onConfirm = viewModel::confirmBrowserMagnet,
        )
    }
}

@Composable
private fun BrowserMagnetConfirmationDialog(
    magnet: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send magnet to TorBox?") },
        text = {
            Text(
                text = magnet,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Send") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun DownloadItem.canResume(): Boolean {
    val state = rawState.trim().lowercase(Locale.ROOT)
    return state == "stopped" || state.startsWith("paused")
}

private fun DownloadItem.canPause(): Boolean {
    if (isReady || cached) return false
    val state = rawState.trim().lowercase(Locale.ROOT)
    return state.isNotBlank() &&
        state != "stopped" &&
        !state.startsWith("paused") &&
        state !in setOf("error", "failed", "failed_processing", "expired", "missing", "missingfiles")
}

@Composable
private fun CompactBottomNavigation(selected: AppDestination, onSelected: (AppDestination) -> Unit) {
    NavigationBar {
        AppDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = selected == destination,
                onClick = { onSelected(destination) },
                icon = {
                    Icon(
                        imageVector = when (destination) {
                            AppDestination.DOWNLOADS -> Icons.Outlined.Download
                            AppDestination.ADD -> Icons.Outlined.AddCircleOutline
                            AppDestination.BROWSER -> Icons.Outlined.Language
                            AppDestination.SETTINGS -> Icons.Outlined.Settings
                        },
                        contentDescription = null,
                    )
                },
                label = {
                    Text(
                        when (destination) {
                            AppDestination.DOWNLOADS -> "Downloads"
                            AppDestination.ADD -> "Add"
                            AppDestination.BROWSER -> "Browser"
                            AppDestination.SETTINGS -> "Settings"
                        },
                    )
                },
                alwaysShowLabel = true,
            )
        }
    }
}

@Composable
private fun BookmarksDialog(
    bookmarks: List<app.jabs.torboxdrop.model.Bookmark>,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bookmarks") },
        text = {
            if (bookmarks.isEmpty()) {
                Text("Bookmark a page from the Browser toolbar and it will appear here.")
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(bookmarks, key = { it.id }) { bookmark ->
                        Column(
                            modifier = Modifier
                                .clickable { onOpen(bookmark.url) }
                                .padding(vertical = 10.dp),
                        ) {
                            Text(bookmark.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(bookmark.url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { onRemove(bookmark.url) }) { Text("Remove") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

private fun loadedFileMatches(state: MainUiState): Set<String> {
    val query = state.downloads.search.trim()
    if (query.isEmpty()) return emptySet()
    return state.cachedFileMatchKeys + state.loadedFiles
        .filterValues { files ->
            files.any { it.name.contains(query, true) || it.path.orEmpty().contains(query, true) }
        }
        .keys
        .toSet()
}

private fun enqueueDownload(context: Context, event: MainEvent.DownloadToDevice): Long {
    val uri = Uri.parse(event.url)
    require(uri.scheme == "https" && !uri.host.isNullOrBlank())
    val cleanName = event.fileName
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cc}]"), "_")
        .trim()
        .take(180)
        .ifBlank { "torbox-download" }
    val destinationName = uniqueDownloadName(cleanName)
    val request = DownloadManager.Request(uri)
        .setTitle(cleanName)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(false)
    event.mimeType?.takeIf { it.contains('/') }?.let(request::setMimeType)
    event.requestHeaders.forEach { (name, value) ->
        require(name.matches(Regex("[A-Za-z0-9-]{1,64}")))
        require(value.length <= 8_192 && '\r' !in value && '\n' !in value)
        request.addRequestHeader(name, value)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, destinationName)
    } else {
        request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, destinationName)
    }
    return context.getSystemService(DownloadManager::class.java).enqueue(request)
}

private fun openUri(context: Context, event: MainEvent.OpenUri) {
    val uri = Uri.parse(event.url)
    require(uri.scheme in setOf("https", "http", "mailto", "tel"))
    require(uri.scheme in setOf("https", "http") || event.mimeType.isNullOrBlank())
    val intent = Intent(Intent.ACTION_VIEW).apply {
        if (event.mimeType.isNullOrBlank()) data = uri else setDataAndType(uri, event.mimeType)
    }
    context.startActivity(
        event.chooserTitle?.let { Intent.createChooser(intent, it) } ?: intent,
    )
}

private fun uniqueDownloadName(fileName: String): String {
    val lastDot = fileName.lastIndexOf('.')
    val hasExtension = lastDot in 1 until fileName.lastIndex
    val base = if (hasExtension) fileName.substring(0, lastDot) else fileName
    val extension = if (hasExtension) fileName.substring(lastDot) else ""
    val suffix = "-${System.currentTimeMillis()}"
    return base.take((180 - extension.length - suffix.length).coerceAtLeast(1)) + suffix + extension
}

private suspend fun clearBrowserData(context: Context) {
    suspendCancellableCoroutine { continuation ->
        CookieManager.getInstance().removeAllCookies {
            if (continuation.isActive) continuation.resume(Unit)
        }
    }
    CookieManager.getInstance().flush()
    WebStorage.getInstance().deleteAllData()
    WebViewDatabase.getInstance(context).apply {
        clearHttpAuthUsernamePassword()
    }
    WebView(context).apply {
        clearCache(true)
        clearHistory()
        clearFormData()
        clearSslPreferences()
        destroy()
    }
}
