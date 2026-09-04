package app.jabs.torboxdrop.browser

import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch

/**
 * A native browser shell around one isolated, hardened WebView.
 *
 * The callbacks deliberately expose browser actions rather than TorBox credentials or a generic
 * JavaScript bridge. The embedding screen remains responsible for persistence and Android intents.
 */
@Composable
fun BrowserScreen(
    callbacks: BrowserCallbacks,
    modifier: Modifier = Modifier,
    initialUrl: String? = null,
    homeUrl: String = "https://www.google.com/",
    bookmarkedUrls: Set<String> = emptySet(),
    adBlockingInitiallyEnabled: Boolean = true,
    allowThirdPartyCookies: Boolean = false,
    siteExceptions: Set<String> = emptySet(),
    restoredWebViewState: Bundle? = null,
    onWebViewStateSaved: (Bundle) -> Unit = {},
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val latestCallbacks by rememberUpdatedState(callbacks)
    val latestOnWebViewStateSaved by rememberUpdatedState(onWebViewStateSaved)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }

    var blockedCount by remember { mutableIntStateOf(0) }
    var blockingEnabled by rememberSaveable { mutableStateOf(adBlockingInitiallyEnabled) }
    var longPressedLink by remember { mutableStateOf<String?>(null) }
    var pendingDownload by remember { mutableStateOf<BrowserDownloadRequest?>(null) }
    var shieldMenuOpen by remember { mutableStateOf(false) }
    var addressHasFocus by remember { mutableStateOf(false) }

    val blocker = remember(context) {
        AdBlockEngine.fromAssets(
            context = context,
            enabled = adBlockingInitiallyEnabled,
            siteExceptions = siteExceptions,
            onBlockedCountChanged = { count ->
                mainHandler.post { blockedCount = count }
            },
        )
    }
    val webView = remember(context, homeUrl, allowThirdPartyCookies) { WebView(context) }
    val controller = remember(webView, blocker, homeUrl) {
        SecureBrowserController(
            webView = webView,
            homeUrl = homeUrl,
            blocker = blocker,
            allowThirdPartyCookies = allowThirdPartyCookies,
            callbacks = {
                latestCallbacks.copy(
                    onPageError = { message ->
                        latestCallbacks.onPageError(message)
                        scope.launch { snackbarHostState.showSnackbar(message) }
                    },
                )
            },
            onLongPressedLink = { longPressedLink = it },
            onDownloadRequested = { pendingDownload = it },
        )
    }

    var address by rememberSaveable { mutableStateOf(initialUrl ?: homeUrl) }
    var siteExcepted by remember { mutableStateOf(controller.isCurrentSiteExcepted()) }
    val currentUrl = controller.currentUrl
    val isBookmarked = currentUrl in bookmarkedUrls
    val canActOnPage = currentUrl.startsWith("https://")

    LaunchedEffect(controller) {
        if (!controller.restoreState(restoredWebViewState)) controller.loadInitialUrl(address)
    }
    LaunchedEffect(controller, adBlockingInitiallyEnabled) {
        blockingEnabled = adBlockingInitiallyEnabled
        controller.setBlockingEnabled(adBlockingInitiallyEnabled)
    }
    LaunchedEffect(currentUrl) {
        if (!addressHasFocus && currentUrl.isNotBlank()) address = currentUrl
        siteExcepted = controller.isCurrentSiteExcepted()
    }
    DisposableEffect(controller) {
        onDispose {
            controller.saveState()?.let(latestOnWebViewStateSaved)
            controller.destroy()
        }
    }
    BackHandler(enabled = controller.canGoBack) {
        controller.goBack()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .onFocusChanged { addressHasFocus = it.isFocused },
                singleLine = true,
                label = { Text("Address or search") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        controller.loadUserInput(address)
                        focusManager.clearFocus()
                    },
                ),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                BrowserAction(
                    label = "Back",
                    enabled = controller.canGoBack,
                    onClick = { controller.goBack() },
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                }
                BrowserAction(
                    label = "Forward",
                    enabled = controller.canGoForward,
                    onClick = controller::goForward,
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                }
                BrowserAction(
                    label = if (controller.isLoading) "Stop" else "Reload",
                    onClick = controller::reloadOrStop,
                ) {
                    Icon(
                        if (controller.isLoading) Icons.Default.Close else Icons.Default.Refresh,
                        contentDescription = null,
                    )
                }
                BrowserAction(label = "Home", onClick = controller::goHome) {
                    Icon(Icons.Default.Home, contentDescription = null)
                }

                Box {
                    BrowserAction(
                        label = "Privacy shield, $blockedCount blocked",
                        onClick = { shieldMenuOpen = true },
                    ) {
                        Box(contentAlignment = Alignment.TopEnd) {
                            Icon(
                                Icons.Default.Shield,
                                contentDescription = null,
                                tint = if (blockingEnabled && !siteExcepted) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            if (blockedCount > 0) {
                                Text(
                                    text = if (blockedCount > 99) "99+" else blockedCount.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    DropdownMenu(
                        expanded = shieldMenuOpen,
                        onDismissRequest = { shieldMenuOpen = false },
                    ) {
                        Text(
                            text = "$blockedCount requests blocked on this page",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        DropdownMenuItem(
                            text = { Text(if (blockingEnabled) "Turn blocking off" else "Turn blocking on") },
                            onClick = {
                                blockingEnabled = !blockingEnabled
                                controller.setBlockingEnabled(blockingEnabled)
                                latestCallbacks.onAdBlockingChanged(blockingEnabled)
                                shieldMenuOpen = false
                                webView.reload()
                            },
                        )
                        if (controller.currentUrl.isNotBlank()) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (siteExcepted) "Enable on this site" else "Disable on this site",
                                    )
                                },
                                onClick = {
                                    val disabled = !siteExcepted
                                    controller.setCurrentSiteException(disabled)?.let { host ->
                                        siteExcepted = disabled
                                        latestCallbacks.onSiteExceptionChanged(host, disabled)
                                    }
                                    shieldMenuOpen = false
                                },
                            )
                        }
                    }
                }

                BrowserAction(
                    label = if (isBookmarked) "Remove bookmark" else "Bookmark",
                    enabled = canActOnPage,
                    onClick = {
                        latestCallbacks.onBookmarkToggle(
                            BrowserBookmarkRequest(
                                title = controller.pageTitle.ifBlank { currentUrl },
                                url = currentUrl,
                            ),
                        )
                    },
                ) {
                    Icon(
                        if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = null,
                    )
                }
                BrowserAction(
                    label = "Send page to TorBox",
                    enabled = canActOnPage,
                    onClick = { latestCallbacks.onSendToTorBox(currentUrl) },
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                }
                BrowserAction(
                    label = "Open externally",
                    enabled = canActOnPage,
                    onClick = { latestCallbacks.onOpenExternal(currentUrl) },
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                }
            }

            if (controller.isLoading) {
                LinearProgressIndicator(
                    progress = { controller.loadProgress / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                )
            } else {
                Spacer(Modifier.height(2.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AndroidView(
                factory = { webView },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }

    longPressedLink?.let { link ->
        LinkActionDialog(
            link = link,
            onDismiss = { longPressedLink = null },
            onOpen = {
                controller.loadUserInput(link)
                longPressedLink = null
            },
            onSend = {
                latestCallbacks.onSendToTorBox(link)
                longPressedLink = null
            },
            onCopy = {
                latestCallbacks.onCopyLink(link)
                longPressedLink = null
            },
            onExternal = {
                latestCallbacks.onOpenExternal(link)
                longPressedLink = null
            },
        )
    }

    pendingDownload?.let { request ->
        DownloadChoiceDialog(
            request = request,
            onDismiss = { pendingDownload = null },
            onDevice = {
                latestCallbacks.onDownloadToDevice(request)
                pendingDownload = null
            },
            onTorBox = {
                latestCallbacks.onSendToTorBox(request.url)
                pendingDownload = null
            },
        )
    }
}

@Composable
private fun BrowserAction(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        content()
    }
}

@Composable
private fun LinkActionDialog(
    link: String,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onSend: () -> Unit,
    onCopy: () -> Unit,
    onExternal: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link actions") },
        text = {
            Column {
                Text(link, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = onSend, modifier = Modifier.fillMaxWidth()) {
                    Text("Send link to TorBox")
                }
                TextButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                    Text("Open link")
                }
                TextButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) {
                    Text("Copy link")
                }
                TextButton(onClick = onExternal, modifier = Modifier.fillMaxWidth()) {
                    Text("Open externally")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DownloadChoiceDialog(
    request: BrowserDownloadRequest,
    onDismiss: () -> Unit,
    onDevice: () -> Unit,
    onTorBox: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download link") },
        text = {
            Column {
                Text(
                    SecureBrowserController.suggestedFilename(request),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onTorBox, modifier = Modifier.fillMaxWidth()) {
                    Text("Send to TorBox")
                }
                TextButton(onClick = onDevice, modifier = Modifier.fillMaxWidth()) {
                    Text("Download to device")
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
