package app.jabs.torboxdrop.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Cookie
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.jabs.torboxdrop.model.AccountInfo
import app.jabs.torboxdrop.model.AddOptions
import app.jabs.torboxdrop.model.DownloadDensity
import app.jabs.torboxdrop.ui.theme.TorBoxColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ln
import kotlin.math.pow

enum class TokenValidationStatus {
    IDLE,
    VALIDATING,
    VALID,
    INVALID,
}

/** All non-secret settings needed to render [SettingsScreen]. */
data class SettingsUiState(
    val account: AccountInfo? = null,
    val tokenConfigured: Boolean = false,
    /** A pre-masked hint only. Never pass the token itself through this field. */
    val tokenHint: String? = null,
    val tokenValidationStatus: TokenValidationStatus = TokenValidationStatus.IDLE,
    val tokenValidationMessage: String? = null,
    val defaultAddOptions: AddOptions = AddOptions(),
    val density: DownloadDensity = DownloadDensity.COMPACT,
    val autoSendSharedLinks: Boolean = true,
    val autoSendClipboardMagnets: Boolean = true,
    val autoSendBrowserMagnets: Boolean = true,
    val blockAdsAndTrackers: Boolean = true,
    val allowThirdPartyCookies: Boolean = false,
    val browserHomePage: String = "",
    val completionMonitoringStatus: String? = null,
    val monitoredDownloadCount: Int = 0,
    val appVersion: String = "",
)

/**
 * Settings UI with no storage or networking side effects. Token callbacks must
 * hand the value to secure native storage; this screen never persists the draft.
 * [onValidateToken] validates the already-saved token, while [onReplaceToken]
 * should validate and atomically save the supplied replacement.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onSettingsChange: (SettingsUiState) -> Unit,
    onValidateToken: () -> Unit,
    onReplaceToken: (String) -> Unit,
    onDisconnectAccount: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenBrowserHome: () -> Unit,
    onManageBookmarks: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onClearRecentHistory: () -> Unit,
    onClearBrowserData: () -> Unit,
    onClearCachedDownloadMetadata: () -> Unit,
    onResetSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingConfirmation by remember { mutableStateOf<DestructiveAction?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 112.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 2.dp),
            ) {
                Text("Settings", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Connection, defaults, browser, and app preferences",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsCard(
                title = "TorBox account",
                subtitle = if (state.tokenConfigured) {
                    "Connected with a securely stored API token"
                } else {
                    "Connect to add and manage downloads"
                },
                icon = Icons.Rounded.AccountCircle,
                modifier = Modifier.widthIn(max = 720.dp),
            ) {
                TokenEditor(
                    state = state,
                    onValidateToken = onValidateToken,
                    onReplaceToken = onReplaceToken,
                )

                if (state.account != null) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AccountSummary(
                        account = state.account,
                        onOpenAccount = onOpenAccount,
                    )
                }

                if (state.tokenConfigured) {
                    OutlinedButton(
                        onClick = { pendingConfirmation = DestructiveAction.DISCONNECT },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.tokenValidationStatus != TokenValidationStatus.VALIDATING,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Disconnect account")
                    }
                }
            }
        }

        item {
            SettingsCard(
                title = "Add defaults",
                subtitle = "Applied when a new link or torrent arrives",
                icon = Icons.Rounded.Download,
                modifier = Modifier.widthIn(max = 720.dp),
            ) {
                SettingsToggle(
                    title = "Queue new downloads",
                    description = "Do not consume an active slot immediately",
                    checked = state.defaultAddOptions.queued,
                    icon = Icons.Rounded.CloudQueue,
                    onCheckedChange = {
                        onSettingsChange(
                            state.copy(defaultAddOptions = state.defaultAddOptions.copy(queued = it)),
                        )
                    },
                )
                SettingsToggle(
                    title = "Cached only",
                    description = "Only add content already available on TorBox",
                    checked = state.defaultAddOptions.cachedOnly,
                    icon = Icons.Rounded.AutoAwesome,
                    onCheckedChange = {
                        onSettingsChange(
                            state.copy(
                                defaultAddOptions = state.defaultAddOptions.copy(cachedOnly = it),
                            ),
                        )
                    },
                )
                SettingsToggle(
                    title = "Notify when ready",
                    description = "Arm a completion notification for every new download",
                    checked = state.defaultAddOptions.notifyWhenComplete,
                    icon = Icons.Rounded.Notifications,
                    onCheckedChange = {
                        onSettingsChange(
                            state.copy(
                                defaultAddOptions = state.defaultAddOptions.copy(
                                    notifyWhenComplete = it,
                                ),
                            ),
                        )
                    },
                )
                TorrentSeedingChooser(
                    selected = state.defaultAddOptions.seed,
                    onSelected = { seed ->
                        onSettingsChange(
                            state.copy(defaultAddOptions = state.defaultAddOptions.copy(seed = seed)),
                        )
                    },
                )
                SettingsToggle(
                    title = "Allow ZIP downloads",
                    description = "Let TorBox package folders as a ZIP",
                    checked = state.defaultAddOptions.allowZip,
                    icon = Icons.Rounded.Archive,
                    onCheckedChange = {
                        onSettingsChange(
                            state.copy(
                                defaultAddOptions = state.defaultAddOptions.copy(allowZip = it),
                            ),
                        )
                    },
                )
            }
        }

        item {
            SettingsCard(
                title = "Download density",
                subtitle = "Choose how much detail each download row shows",
                icon = Icons.Rounded.Speed,
                modifier = Modifier.widthIn(max = 720.dp),
            ) {
                DensityChooser(
                    selected = state.density,
                    onSelected = { onSettingsChange(state.copy(density = it)) },
                )
            }
        }

        item {
            SettingsCard(
                title = "Browser",
                subtitle = "Control how web pages and magnet links open",
                icon = Icons.Rounded.Language,
                modifier = Modifier.widthIn(max = 720.dp),
            ) {
                SettingsToggle(
                    title = "Instantly send browser magnets",
                    description = "Submit tapped magnet links without another confirmation",
                    checked = state.autoSendBrowserMagnets,
                    icon = Icons.Rounded.Link,
                    onCheckedChange = {
                        onSettingsChange(state.copy(autoSendBrowserMagnets = it))
                    },
                )
                SettingsToggle(
                    title = "Block ads & trackers",
                    description = "Filter common advertising and tracking requests",
                    checked = state.blockAdsAndTrackers,
                    icon = Icons.Rounded.Security,
                    onCheckedChange = {
                        onSettingsChange(state.copy(blockAdsAndTrackers = it))
                    },
                )
                SettingsToggle(
                    title = "Allow third-party cookies",
                    description = "Off by default for safer in-app browsing",
                    checked = state.allowThirdPartyCookies,
                    icon = Icons.Rounded.Cookie,
                    onCheckedChange = {
                        onSettingsChange(state.copy(allowThirdPartyCookies = it))
                    },
                )
                OutlinedTextField(
                    value = state.browserHomePage,
                    onValueChange = { onSettingsChange(state.copy(browserHomePage = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Browser home page") },
                    placeholder = { Text("https://…") },
                    leadingIcon = { Icon(Icons.Rounded.Language, contentDescription = null) },
                    trailingIcon = {
                        if (state.browserHomePage.isNotBlank()) {
                            IconButton(onClick = onOpenBrowserHome) {
                                Icon(
                                    Icons.Rounded.OpenInBrowser,
                                    contentDescription = "Open home page",
                                )
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    shape = RoundedCornerShape(16.dp),
                )
                SettingsAction(
                    title = "Manage bookmarks",
                    description = "Edit saved pages and your browser shortcuts",
                    icon = Icons.Rounded.Bookmarks,
                    onClick = onManageBookmarks,
                )
                SettingsAction(
                    title = "Clear browser data",
                    description = "Remove cookies, cache, site storage, and browsing history",
                    icon = Icons.Rounded.DeleteSweep,
                    destructive = true,
                    onClick = { pendingConfirmation = DestructiveAction.CLEAR_BROWSER_DATA },
                )
            }
        }

        item {
            SettingsCard(
                title = "App behavior",
                subtitle = "Sharing, clipboard detection, and notifications",
                icon = Icons.Rounded.Smartphone,
                modifier = Modifier.widthIn(max = 720.dp),
            ) {
                SettingsToggle(
                    title = "Instantly send shared links",
                    description = "Submit links shared to TorBox Drop immediately",
                    checked = state.autoSendSharedLinks,
                    icon = Icons.Rounded.FileDownload,
                    onCheckedChange = {
                        onSettingsChange(state.copy(autoSendSharedLinks = it))
                    },
                )
                SettingsToggle(
                    title = "Instantly send copied magnets",
                    description = "Works while TorBox Drop or its browser is on screen",
                    checked = state.autoSendClipboardMagnets,
                    icon = Icons.Rounded.ContentPaste,
                    onCheckedChange = {
                        onSettingsChange(state.copy(autoSendClipboardMagnets = it))
                    },
                )
                SettingsAction(
                    title = "Completion monitoring",
                    description = state.completionMonitoringStatus ?: when {
                        state.monitoredDownloadCount == 1 -> "Monitoring 1 download for completion"
                        state.monitoredDownloadCount > 1 ->
                            "Monitoring ${state.monitoredDownloadCount} downloads for completion"
                        else -> "No downloads are currently being monitored"
                    },
                    icon = Icons.Rounded.Notifications,
                    onClick = onOpenNotificationSettings,
                )
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Rounded.Info,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(9.dp))
                        Text(
                            "Android only allows clipboard detection while this app is visible. " +
                                "Sharing to TorBox Drop works from any app.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        item {
            SettingsCard(
                title = "Data & app",
                subtitle = buildString {
                    append("Local tools and app information")
                    if (state.appVersion.isNotBlank()) append(" · v${state.appVersion}")
                },
                icon = Icons.Rounded.Security,
                modifier = Modifier.widthIn(max = 720.dp),
            ) {
                SettingsAction(
                    title = "Export diagnostics",
                    description = "Create a shareable troubleshooting report without your token",
                    icon = Icons.Rounded.FileDownload,
                    onClick = onExportDiagnostics,
                )
                SettingsAction(
                    title = "Clear recent sends",
                    description = "Remove locally saved send history",
                    icon = Icons.Rounded.DeleteSweep,
                    destructive = true,
                    onClick = { pendingConfirmation = DestructiveAction.CLEAR_HISTORY },
                )
                SettingsAction(
                    title = "Clear cached download metadata",
                    description = "Remove offline list and file metadata; TorBox data stays intact",
                    icon = Icons.Rounded.DeleteForever,
                    destructive = true,
                    onClick = { pendingConfirmation = DestructiveAction.CLEAR_DOWNLOAD_CACHE },
                )
                SettingsAction(
                    title = "Reset preferences",
                    description = "Restore add, browser, and display defaults",
                    icon = Icons.Rounded.RestartAlt,
                    destructive = true,
                    onClick = { pendingConfirmation = DestructiveAction.RESET_SETTINGS },
                )
            }
        }
    }

    pendingConfirmation?.let { action ->
        DestructiveConfirmationDialog(
            action = action,
            onDismiss = { pendingConfirmation = null },
            onConfirm = {
                pendingConfirmation = null
                when (action) {
                    DestructiveAction.DISCONNECT -> onDisconnectAccount()
                    DestructiveAction.CLEAR_HISTORY -> onClearRecentHistory()
                    DestructiveAction.CLEAR_BROWSER_DATA -> onClearBrowserData()
                    DestructiveAction.CLEAR_DOWNLOAD_CACHE -> onClearCachedDownloadMetadata()
                    DestructiveAction.RESET_SETTINGS -> onResetSettings()
                }
            },
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(13.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                }
                Spacer(Modifier.width(11.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            content()
        }
    }
}

@Composable
private fun TokenEditor(
    state: SettingsUiState,
    onValidateToken: () -> Unit,
    onReplaceToken: (String) -> Unit,
) {
    // Token input intentionally stays in memory only; never place a secret in SavedState.
    var editing by remember(state.tokenConfigured) {
        mutableStateOf(!state.tokenConfigured)
    }
    var draft by remember(state.tokenConfigured) { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    val busy = state.tokenValidationStatus == TokenValidationStatus.VALIDATING

    LaunchedEffect(state.tokenValidationStatus, state.tokenValidationMessage, state.tokenHint) {
        if (
            state.tokenConfigured &&
            state.tokenValidationStatus == TokenValidationStatus.VALID &&
            state.tokenValidationMessage == "Connected"
        ) {
            draft = ""
            reveal = false
            editing = false
        }
    }

    if (state.tokenConfigured && !editing) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("API token", style = MaterialTheme.typography.labelMedium)
                    Text(
                        text = obscuredTokenHint(state.tokenHint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = {
                        draft = ""
                        reveal = false
                        editing = true
                    },
                ) {
                    Text("Replace")
                }
            }
        }

        Button(
            onClick = onValidateToken,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(19.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (busy) "Checking connection…" else "Check connection")
        }
    } else {
        Text(
            if (state.tokenConfigured) {
                "Paste a new token. The current token remains active until replacement succeeds."
            } else {
                "Create an API token in your TorBox account, then paste it here."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            label = { Text("API token") },
            placeholder = { Text("Paste TorBox API token") },
            leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = { reveal = !reveal }) {
                    Icon(
                        imageVector = if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (reveal) "Hide token" else "Show token",
                    )
                }
            },
            singleLine = true,
            visualTransformation = if (reveal) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
            shape = RoundedCornerShape(16.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            if (state.tokenConfigured) {
                OutlinedButton(
                    onClick = {
                        draft = ""
                        reveal = false
                        editing = false
                    },
                    modifier = Modifier.weight(0.75f),
                    enabled = !busy,
                ) {
                    Text("Cancel")
                }
            }
            Button(
                onClick = { onReplaceToken(draft) },
                modifier = Modifier.weight(1.4f),
                enabled = draft.length >= MIN_TOKEN_LENGTH && !busy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = TorBoxColors.Teal,
                    contentColor = TorBoxColors.Night,
                ),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(19.dp),
                        strokeWidth = 2.dp,
                        color = TorBoxColors.Night,
                    )
                    Spacer(Modifier.width(8.dp))
                } else {
                    Icon(Icons.Rounded.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        busy -> "Validating…"
                        state.tokenConfigured -> "Validate & replace"
                        else -> "Validate & save"
                    },
                    maxLines = 1,
                )
            }
        }
    }

    TokenStatus(state.tokenValidationStatus, state.tokenValidationMessage)
}

@Composable
private fun TokenStatus(status: TokenValidationStatus, message: String?) {
    if (status == TokenValidationStatus.IDLE || status == TokenValidationStatus.VALIDATING) return

    val valid = status == TokenValidationStatus.VALID
    val color = if (valid) TorBoxColors.Green else MaterialTheme.colorScheme.error
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (valid) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = message ?: if (valid) "TorBox accepted the token." else "The token could not be validated.",
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

@Composable
private fun AccountSummary(account: AccountInfo, onOpenAccount: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.email ?: "TorBox account",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildAccountLine(account),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpenAccount) {
                Text("Manage")
                Icon(Icons.Rounded.ChevronRight, contentDescription = null)
            }
        }

        val facts = buildList {
            account.totalDownloaded?.let { add("Downloaded" to formatBytes(it)) }
            account.activeTorrentSlots?.let { add("Torrent slots" to "$it active") }
            account.activeWebSlots?.let { add("Web slots" to "$it active") }
        }
        if (facts.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                facts.take(3).forEach { (label, value) ->
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(13.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.58f),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 9.dp, vertical = 10.dp)) {
                            Text(
                                value,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String,
    description: String,
    checked: Boolean,
    icon: ImageVector,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(21.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(9.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DensityChooser(
    selected: DownloadDensity,
    onSelected: (DownloadDensity) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        DownloadDensity.entries.forEach { density ->
            val active = density == selected
            Surface(
                onClick = { onSelected(density) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                color = if (active) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                },
                contentColor = if (active) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                border = BorderStroke(
                    1.dp,
                    if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (active) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.height(3.dp))
                    }
                    Text(
                        text = density.displayName(),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                    Text(
                        text = density.densityHint(),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsAction(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(21.dp))
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (destructive) color else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = color)
    }
}

@Composable
private fun DestructiveConfirmationDialog(
    action: DestructiveAction,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = when (action) {
                    DestructiveAction.DISCONNECT -> Icons.AutoMirrored.Rounded.Logout
                    DestructiveAction.CLEAR_HISTORY -> Icons.Rounded.DeleteSweep
                    DestructiveAction.CLEAR_BROWSER_DATA -> Icons.Rounded.DeleteSweep
                    DestructiveAction.CLEAR_DOWNLOAD_CACHE -> Icons.Rounded.DeleteForever
                    DestructiveAction.RESET_SETTINGS -> Icons.Rounded.DeleteForever
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(action.title) },
        text = { Text(action.message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text(action.confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private enum class DestructiveAction(
    val title: String,
    val message: String,
    val confirmLabel: String,
) {
    DISCONNECT(
        title = "Disconnect TorBox?",
        message = "The securely stored API token will be removed from this device. Your TorBox account and downloads will not be deleted.",
        confirmLabel = "Disconnect",
    ),
    CLEAR_HISTORY(
        title = "Clear recent sends?",
        message = "This removes the local send history from this device. It does not remove anything from TorBox.",
        confirmLabel = "Clear history",
    ),
    CLEAR_BROWSER_DATA(
        title = "Clear browser data?",
        message = "Cookies, cache, site storage, and local browsing history will be removed. Saved bookmarks are kept.",
        confirmLabel = "Clear browser data",
    ),
    CLEAR_DOWNLOAD_CACHE(
        title = "Clear cached metadata?",
        message = "Offline download lists and cached file details will be removed from this device. Your TorBox downloads will not be deleted.",
        confirmLabel = "Clear metadata",
    ),
    RESET_SETTINGS(
        title = "Reset preferences?",
        message = "Add defaults, browser behavior, and display density will return to their original values. Your API token stays connected.",
        confirmLabel = "Reset",
    ),
}

private fun DownloadDensity.displayName(): String = when (this) {
    DownloadDensity.COMPACT -> "Compact"
    DownloadDensity.COZY -> "Cozy"
    DownloadDensity.DETAILED -> "Detailed"
}

private fun DownloadDensity.densityHint(): String = when (this) {
    DownloadDensity.COMPACT -> "More rows"
    DownloadDensity.COZY -> "Balanced"
    DownloadDensity.DETAILED -> "More info"
}

private fun buildAccountLine(account: AccountInfo): String {
    val plan = account.plan?.takeIf(String::isNotBlank) ?: "TorBox plan"
    val expires = account.premiumExpiresAt ?: account.subscriptionExpiresAt
    return if (expires != null) "$plan · renews ${formatDate(expires)}" else plan
}

private fun obscuredTokenHint(hint: String?): String {
    val suffix = hint.orEmpty()
        .filter { it.isLetterOrDigit() }
        .takeLast(4)
        .takeIf(String::isNotBlank)
        ?: "••••"
    return "••••••••••••  $suffix"
}

private fun formatDate(instant: Instant): String = runCatching {
    SETTINGS_DATE_FORMATTER.format(instant)
}.getOrDefault("later")

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    val group = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
    val value = bytes / 1024.0.pow(group.toDouble())
    return when {
        value >= 100 -> "%.0f %s".format(value, units[group])
        value >= 10 -> "%.1f %s".format(value, units[group])
        else -> "%.2f %s".format(value, units[group])
    }
}

private const val MIN_TOKEN_LENGTH = 10
private val SETTINGS_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault())
