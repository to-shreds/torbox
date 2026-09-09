package app.jabs.torboxdrop.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Timer
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.jabs.torboxdrop.TorBoxDropApplication
import app.jabs.torboxdrop.model.AddOptions
import app.jabs.torboxdrop.model.AddResult
import app.jabs.torboxdrop.model.DownloadType
import app.jabs.torboxdrop.model.IncomingAdd
import app.jabs.torboxdrop.model.RecentSend
import app.jabs.torboxdrop.ui.theme.TorBoxColors
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Hoisted add flow. File selection and ordinary persistence live outside this composable. The
 * Google Drive toggle reads only non-secret local setup state so a per-torrent choice can cleanly
 * inherit or override the global Drive default.
 */
@Composable
fun AddScreen(
    candidate: String,
    pendingTorrentName: String?,
    options: AddOptions,
    recentSends: List<RecentSend>,
    onCandidateChange: (String) -> Unit,
    onOptionsChange: (AddOptions) -> Unit,
    onSubmit: (IncomingAdd, AddOptions) -> Unit,
    onSubmitTorrent: (AddOptions) -> Unit,
    onDiscardTorrent: () -> Unit,
    onPickTorrent: (AddOptions) -> Unit,
    onPasteRequested: () -> Unit,
    onRecentSendSelected: (RecentSend) -> Unit,
    onClearRecent: () -> Unit,
    modifier: Modifier = Modifier,
    submitting: Boolean = false,
    result: AddResult? = null,
    errorMessage: String? = null,
    onDismissMessage: () -> Unit = {},
) {
    val detection = remember(candidate) { detectCandidate(candidate) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    val app = LocalContext.current.applicationContext as TorBoxDropApplication
    val drivePreferences = app.container.preferences
    val driveConnected = drivePreferences.googleDriveConnected &&
        !drivePreferences.googleDriveFolderId.isNullOrBlank()
    val sendToDrive = options.sendToGoogleDrive ?: drivePreferences.googleDriveByDefault
    val isTorrentInput = pendingTorrentName != null || detection.kind == CandidateKind.MAGNET
    val driveRequirementUnmet = isTorrentInput && sendToDrive && !driveConnected

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 112.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            AddHero(modifier = Modifier.widthIn(max = 720.dp))
        }

        if (result != null || errorMessage != null) {
            item {
                AddMessage(
                    result = result,
                    errorMessage = errorMessage,
                    onDismiss = onDismissMessage,
                    modifier = Modifier.widthIn(max = 720.dp),
                )
            }
        }

        item {
            Card(
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.84f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Add to TorBox", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Paste a magnet link or direct file URL, or choose a .torrent file.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (pendingTorrentName != null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, contentDescription = null)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Torrent ready to upload", style = MaterialTheme.typography.labelMedium)
                                    Text(
                                        pendingTorrentName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                IconButton(onClick = onDiscardTorrent) {
                                    Icon(Icons.Rounded.Clear, contentDescription = "Discard torrent file")
                                }
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = candidate,
                            onValueChange = onCandidateChange,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !submitting,
                            label = { Text("Magnet or URL") },
                            placeholder = {
                                Text("magnet:?xt=urn:btih:…\nor https://example.com/file.zip")
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Link,
                                    contentDescription = null,
                                )
                            },
                            trailingIcon = {
                                if (candidate.isNotEmpty() && !submitting) {
                                    IconButton(onClick = { onCandidateChange("") }) {
                                        Icon(Icons.Rounded.Clear, contentDescription = "Clear link")
                                    }
                                }
                            },
                            minLines = 3,
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done,
                            ),
                            shape = RoundedCornerShape(16.dp),
                        )

                        DetectionRow(detection)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(
                            onClick = onPasteRequested,
                            enabled = !submitting,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 11.dp),
                        ) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text("Paste")
                        }
                        OutlinedButton(
                            onClick = { onPickTorrent(options) },
                            enabled = !submitting,
                            modifier = Modifier.weight(1.35f),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 11.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.InsertDriveFile, contentDescription = null)
                            Spacer(Modifier.width(7.dp))
                            Text("Choose .torrent", maxLines = 1)
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    AddOptionToggle(
                        title = "Queue it",
                        description = "Do not consume an active slot yet",
                        checked = options.queued,
                        icon = { Icon(Icons.Rounded.Timer, contentDescription = null) },
                        onCheckedChange = { onOptionsChange(options.copy(queued = it)) },
                    )
                    AddOptionToggle(
                        title = "Cached only",
                        description = "Only add if TorBox already has it",
                        checked = options.cachedOnly,
                        icon = { Icon(Icons.Rounded.CloudUpload, contentDescription = null) },
                        onCheckedChange = { onOptionsChange(options.copy(cachedOnly = it)) },
                    )
                    AddOptionToggle(
                        title = "Notify when ready",
                        description = "Send a device notification when processing completes",
                        checked = options.notifyWhenComplete,
                        icon = { Icon(Icons.Rounded.NotificationsActive, contentDescription = null) },
                        onCheckedChange = { onOptionsChange(options.copy(notifyWhenComplete = it)) },
                    )
                    if (isTorrentInput) {
                        AddOptionToggle(
                            title = "Send to Google Drive when ready",
                            description = if (driveConnected) {
                                "Destination: ${drivePreferences.googleDriveFolderName}"
                            } else {
                                "Connect Google Drive in Settings first"
                            },
                            checked = sendToDrive,
                            enabled = driveConnected || sendToDrive,
                            icon = { Icon(Icons.Rounded.CloudUpload, contentDescription = null) },
                            onCheckedChange = { requested ->
                                if (!requested || driveConnected) {
                                    onOptionsChange(options.copy(sendToGoogleDrive = requested))
                                }
                            },
                        )
                    }

                    Surface(
                        onClick = { advancedExpanded = !advancedExpanded },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Advanced options",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Icon(
                                imageVector = if (advancedExpanded) {
                                    Icons.Rounded.ExpandLess
                                } else {
                                    Icons.Rounded.ExpandMore
                                },
                                contentDescription = if (advancedExpanded) "Collapse" else "Expand",
                            )
                        }
                    }

                    AnimatedVisibility(visible = advancedExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = options.customName.orEmpty(),
                                onValueChange = {
                                    onOptionsChange(options.copy(customName = it.takeIf(String::isNotBlank)))
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !submitting,
                                label = { Text("Custom name") },
                                placeholder = { Text("Optional") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Sentences,
                                    imeAction = ImeAction.Done,
                                ),
                                shape = RoundedCornerShape(16.dp),
                            )
                            TorrentSeedingChooser(
                                selected = options.seed,
                                onSelected = { onOptionsChange(options.copy(seed = it)) },
                            )
                            AddOptionToggle(
                                title = "Allow ZIP downloads",
                                description = "Let TorBox prepare a ZIP when downloading folders",
                                checked = options.allowZip,
                                icon = { Icon(Icons.Rounded.Archive, contentDescription = null) },
                                onCheckedChange = { onOptionsChange(options.copy(allowZip = it)) },
                            )
                        }
                    }

                    if (driveRequirementUnmet) {
                        Text(
                            "Google Drive is selected, but it needs to be connected in Settings before this torrent can be added.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Button(
                        onClick = {
                            if (pendingTorrentName != null) {
                                onSubmitTorrent(options)
                            } else {
                                onSubmit(
                                    IncomingAdd.Text(value = detection.value, source = "manual"),
                                    options,
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        enabled = (detection.isValid || pendingTorrentName != null) &&
                            !submitting && !driveRequirementUnmet,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TorBoxColors.Teal,
                            contentColor = TorBoxColors.Night,
                        ),
                    ) {
                        if (submitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = TorBoxColors.Night,
                            )
                            Spacer(Modifier.width(9.dp))
                            Text("Sending…")
                        } else {
                            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null)
                            Spacer(Modifier.width(9.dp))
                            Text("Add to TorBox")
                        }
                    }
                }
            }
        }

        item {
            RecentHeader(
                hasHistory = recentSends.isNotEmpty(),
                totalCount = recentSends.size,
                onClear = onClearRecent,
                modifier = Modifier.widthIn(max = 720.dp),
            )
        }

        if (recentSends.isEmpty()) {
            item {
                EmptyHistory(modifier = Modifier.widthIn(max = 720.dp))
            }
        } else {
            items(
                items = recentSends.take(MAX_VISIBLE_HISTORY),
                key = { recent -> "${recent.id}-${recent.sentAt}-${recent.value}" },
            ) { recent ->
                RecentSendRow(
                    recent = recent,
                    onClick = { onRecentSendSelected(recent) },
                    modifier = Modifier.widthIn(max = 720.dp),
                )
            }
        }
    }
}

@Composable
private fun AddHero(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(TorBoxColors.HeroGradient)
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(TorBoxColors.Teal.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.CloudUpload,
                    contentDescription = null,
                    tint = TorBoxColors.TealBright,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = "Drop something in",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "Share it. Catch it. Send it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AddMessage(
    result: AddResult?,
    errorMessage: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isError = errorMessage != null
    val container = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        result?.queued == true -> TorBoxColors.Amber.copy(alpha = 0.17f)
        else -> TorBoxColors.Green.copy(alpha = 0.15f)
    }
    val content = when {
        isError -> MaterialTheme.colorScheme.onErrorContainer
        result?.queued == true -> TorBoxColors.Amber
        else -> TorBoxColors.Green
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = container,
        contentColor = content,
        border = BorderStroke(1.dp, content.copy(alpha = 0.42f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = if (isError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
                contentDescription = null,
                modifier = Modifier.padding(top = 1.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        isError -> "Couldn’t add it"
                        result?.queued == true -> "Added to the queue"
                        else -> "Added to TorBox"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = errorMessage ?: result?.detail.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.9f),
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Clear, contentDescription = "Dismiss message")
            }
        }
    }
}

@Composable
private fun DetectionRow(detection: CandidateDetection) {
    val chipColor = when (detection.kind) {
        CandidateKind.MAGNET -> TorBoxColors.TealBright
        CandidateKind.WEB -> Color(0xFF92C7FF)
        CandidateKind.INVALID -> MaterialTheme.colorScheme.error
        CandidateKind.EMPTY -> MaterialTheme.colorScheme.outline
    }
    val chipTextColor = when (detection.kind) {
        CandidateKind.MAGNET -> TorBoxColors.Night
        CandidateKind.WEB -> Color(0xFF071522)
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Surface(
            color = chipColor.copy(alpha = if (detection.isValid) 1f else 0.16f),
            contentColor = chipTextColor,
            shape = CircleShape,
            border = if (detection.isValid) null else BorderStroke(
                1.dp,
                chipColor.copy(alpha = 0.55f),
            ),
        ) {
            Text(
                text = detection.label,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Text(
            text = detection.help,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddOptionToggle(
    title: String,
    description: String,
    checked: Boolean,
    icon: @Composable () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(38.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.11f else 0.05f),
            contentColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) { icon() }
        }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun RecentHeader(
    hasHistory: Boolean,
    totalCount: Int,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 7.dp, start = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Recent sends", style = MaterialTheme.typography.titleLarge)
            Text(
                if (totalCount > MAX_VISIBLE_HISTORY) {
                    "Latest $MAX_VISIBLE_HISTORY of $totalCount · saved only on this device"
                } else {
                    "Saved only on this device"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (hasHistory) {
            TextButton(onClick = onClear) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text("Clear")
            }
        }
    }
}

@Composable
private fun EmptyHistory(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 24.dp, horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Rounded.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text("No links sent yet", style = MaterialTheme.typography.titleSmall)
            Text(
                "Successful sends will appear here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecentSendRow(
    recent: RecentSend,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val magnet = recent.type == DownloadType.TORRENT
            Surface(
                shape = CircleShape,
                color = if (magnet) TorBoxColors.TealBright else Color(0xFF92C7FF),
                contentColor = if (magnet) TorBoxColors.Night else Color(0xFF071522),
            ) {
                Text(
                    text = if (magnet) "MAG" else "WEB",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = recent.value,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${formatHistoryTime(recent)} · ${recent.source.humanizeSource()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Rounded.Send,
                contentDescription = "Use again",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private data class CandidateDetection(
    val kind: CandidateKind,
    val value: String,
    val label: String,
    val help: String,
) {
    val isValid: Boolean get() = kind == CandidateKind.MAGNET || kind == CandidateKind.WEB
}

private enum class CandidateKind { EMPTY, MAGNET, WEB, INVALID }

private fun detectCandidate(raw: String): CandidateDetection {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
        return CandidateDetection(
            CandidateKind.EMPTY,
            "",
            "Waiting for a link",
            "Paste or share a link to get started.",
        )
    }

    val magnet = MAGNET_PATTERN.find(trimmed)?.value
    if (magnet != null) {
        return CandidateDetection(
            CandidateKind.MAGNET,
            magnet.replace("&amp;", "&"),
            "Magnet link",
            "This will be added as a torrent.",
        )
    }

    val web = WEB_PATTERN.find(trimmed)?.value?.trimEnd(')', ',', '.', ';', '!', '?')
    if (web != null) {
        return CandidateDetection(
            CandidateKind.WEB,
            web,
            "Web download",
            "This direct URL will use TorBox web downloads.",
        )
    }

    return CandidateDetection(
        CandidateKind.INVALID,
        trimmed,
        "Link not recognized",
        "Enter a magnet link or an http(s) URL.",
    )
}

private fun formatHistoryTime(recent: RecentSend): String = runCatching {
    HISTORY_TIME_FORMATTER.format(recent.sentAt)
}.getOrDefault("Recently")

private fun String.humanizeSource(): String = when (lowercase()) {
    "manual" -> "Added here"
    "share", "shared" -> "Shared to app"
    "clipboard" -> "Clipboard"
    "browser" -> "Built-in browser"
    "file", "picker" -> "File picker"
    else -> replaceFirstChar { it.titlecase() }
}

private const val MAX_VISIBLE_HISTORY = 8
private val MAGNET_PATTERN = Regex("magnet:\\?[^\\s<>\\\"']+", RegexOption.IGNORE_CASE)
private val WEB_PATTERN = Regex("https?://[^\\s<>\\\"']+", RegexOption.IGNORE_CASE)
private val HISTORY_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())
