package app.jabs.torboxdrop.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import app.jabs.torboxdrop.ui.displayProgressFraction
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToLong

/**
 * A callback-driven download detail surface. Mutating actions remain disabled while
 * [actionInProgress], and destructive or security-sensitive changes are confirmed locally.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadDetailScreen(
    download: DownloadItem,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onFiles: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
    onRename: ((String) -> Unit)? = null,
    onUpdateTags: ((List<String>) -> Unit)? = null,
    onSetAirLocked: ((Boolean) -> Unit)? = null,
    onReannounce: (() -> Unit)? = null,
    onPause: (() -> Unit)? = null,
    onResume: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    actionInProgress: Boolean = false,
    deleteError: String? = null,
) {
    BackHandler(onBack = onBack)
    var dialog by remember(download.id) { mutableStateOf<DetailDialog?>(null) }
    val ready = download.isReady
    val filesAvailable = download.fileCount != 0

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Download details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                DownloadHeader(download = download)
            }

            item {
                ContentActions(
                    ready = ready,
                    filesAvailable = filesAvailable,
                    busy = actionInProgress,
                    onFiles = onFiles,
                    onShare = onShare,
                    onDownload = onDownload,
                    onOpen = onOpen,
                )
            }

            transferDetails(download)?.let { rows ->
                item { DetailSection(title = "Transfer", rows = rows) }
            }

            identityDetails(download)?.let { rows ->
                item { DetailSection(title = "Identity and source", rows = rows, selectable = true) }
            }

            timelineDetails(download)?.let { rows ->
                item { DetailSection(title = "Timeline", rows = rows) }
            }

            if (download.tags.isNotEmpty()) {
                item {
                    TagsSection(tags = download.tags)
                }
            }

            if (!download.error.isNullOrBlank() || !download.trackerMessage.isNullOrBlank()) {
                item {
                    MessagesSection(download = download)
                }
            }

            item {
                ManagementActions(
                    download = download,
                    busy = actionInProgress,
                    onRename = onRename?.let { { dialog = DetailDialog.Rename } },
                    onEditTags = onUpdateTags?.let { { dialog = DetailDialog.Tags } },
                    onToggleAirLock = onSetAirLocked?.let { { dialog = DetailDialog.AirLock } },
                    onReannounce = onReannounce,
                    onPause = onPause,
                    onResume = onResume,
                    onDelete = onDelete?.let { { dialog = DetailDialog.Delete } },
                )
            }
        }
    }

    when (dialog) {
        DetailDialog.Rename -> RenameDialog(
            currentName = download.name,
            onDismiss = { dialog = null },
            onConfirm = { name ->
                dialog = null
                onRename?.invoke(name)
            },
        )

        DetailDialog.Tags -> TagsDialog(
            currentTags = download.tags,
            onDismiss = { dialog = null },
            onConfirm = { tags ->
                dialog = null
                onUpdateTags?.invoke(tags)
            },
        )

        DetailDialog.AirLock -> ConfirmAirLockDialog(
            currentlyAirLocked = download.airLocked,
            onDismiss = { dialog = null },
            onConfirm = {
                dialog = null
                onSetAirLocked?.invoke(!download.airLocked)
            },
        )

        DetailDialog.Delete -> ConfirmDeleteDialog(
            name = download.name,
            busy = actionInProgress,
            error = deleteError,
            onDismiss = { if (!actionInProgress) dialog = null },
            onConfirm = { onDelete?.invoke() },
        )

        null -> Unit
    }
}

@Composable
private fun DownloadHeader(download: DownloadItem) {
    val fraction = displayProgressFraction(download)
    val statusColors = statusColors(download)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (download.type == DownloadType.TORRENT) "Torrent" else "Web download",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    color = statusColors.first,
                    contentColor = statusColors.second,
                    shape = RoundedCornerShape(50),
                ) {
                    Text(
                        text = download.friendlyState.ifBlank { "Unknown" },
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            SelectionContainer {
                Text(
                    text = download.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            val progressModifier = Modifier.fillMaxWidth().height(7.dp)
            val progressColor = if (download.isProblem) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
            if (fraction == null) {
                LinearProgressIndicator(
                    modifier = progressModifier,
                    color = progressColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            } else {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = progressModifier,
                    color = progressColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    drawStopIndicator = {},
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = fraction?.let { value ->
                        "${(value * 100).roundToLong()}%"
                    } ?: "Progress unavailable",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                val byteSummary = when {
                    download.downloadedBytes != null && download.totalSize != null ->
                        "${formatBytes(download.downloadedBytes)} of ${formatBytes(download.totalSize)}"
                    download.totalSize != null -> formatBytes(download.totalSize)
                    download.downloadedBytes != null -> "${formatBytes(download.downloadedBytes)} downloaded"
                    else -> null
                }
                if (byteSummary != null) {
                    Text(
                        text = byteSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (download.airLocked) StatusPill("AirLock")
                if (download.cached) StatusPill("Cached")
                if (download.privateTorrent == true) StatusPill("Private")
            }
        }
    }
}

@Composable
private fun StatusPill(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ContentActions(
    ready: Boolean,
    filesAvailable: Boolean,
    busy: Boolean,
    onFiles: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onDownload: (() -> Unit)?,
    onOpen: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Content", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ActionButton(
                label = "Files",
                icon = Icons.Outlined.FolderOpen,
                enabled = !busy && filesAvailable && onFiles != null,
                onClick = { onFiles?.invoke() },
            )
            ActionButton(
                label = "Download",
                icon = Icons.Outlined.Download,
                enabled = !busy && ready && onDownload != null,
                onClick = { onDownload?.invoke() },
            )
            ActionButton(
                label = "Share",
                icon = Icons.Outlined.Share,
                enabled = !busy && ready && onShare != null,
                onClick = { onShare?.invoke() },
            )
            ActionButton(
                label = "Open",
                icon = Icons.AutoMirrored.Outlined.OpenInNew,
                enabled = !busy && ready && onOpen != null,
                onClick = { onOpen?.invoke() },
            )
        }
        if (!ready && listOf(onDownload, onShare, onOpen).any { it != null }) {
            Text(
                text = "Download, share, and open become available when the content is complete and present.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ActionButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(onClick = onClick, enabled = enabled) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(label)
    }
}

@Composable
private fun DetailSection(
    title: String,
    rows: List<DetailRow>,
    selectable: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val content: @Composable () -> Unit = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = row.label,
                            modifier = Modifier.weight(0.38f),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = row.value,
                            modifier = Modifier.weight(0.62f),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                if (selectable) SelectionContainer { content() } else content()
            }
        }
    }
}

@Composable
private fun TagsSection(tags: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Tags", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tags.forEach { tag ->
                    AssistChip(onClick = {}, label = { Text(tag) }, enabled = false)
                }
            }
        }
    }
}

@Composable
private fun MessagesSection(download: DownloadItem) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        download.error?.takeIf { it.isNotBlank() }?.let { error ->
            MessageCard(
                title = "Problem",
                message = error,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
        download.trackerMessage?.takeIf { it.isNotBlank() }?.let { message ->
            MessageCard(
                title = "Tracker",
                message = message,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun MessageCard(
    title: String,
    message: String,
    containerColor: Color,
    contentColor: Color,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            SelectionContainer { Text(message, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun ManagementActions(
    download: DownloadItem,
    busy: Boolean,
    onRename: (() -> Unit)?,
    onEditTags: (() -> Unit)?,
    onToggleAirLock: (() -> Unit)?,
    onReannounce: (() -> Unit)?,
    onPause: (() -> Unit)?,
    onResume: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Manage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            if (onRename != null) {
                ManagementButton("Rename", Icons.Outlined.Edit, !busy, onRename)
            }
            if (onEditTags != null) {
                ManagementButton("Edit tags", Icons.AutoMirrored.Outlined.Label, !busy, onEditTags)
            }
            if (onToggleAirLock != null) {
                ManagementButton(
                    label = if (download.airLocked) "Remove from AirLock" else "Move to AirLock",
                    icon = if (download.airLocked) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                    enabled = !busy,
                    onClick = onToggleAirLock,
                )
            }
            if (download.type == DownloadType.TORRENT && onReannounce != null) {
                ManagementButton("Reannounce torrent", Icons.Outlined.Refresh, !busy, onReannounce)
            }
            if (download.type == DownloadType.TORRENT && onPause != null) {
                ManagementButton("Pause torrent", Icons.Outlined.Pause, !busy, onPause)
            }
            if (download.type == DownloadType.TORRENT && onResume != null) {
                ManagementButton("Resume torrent", Icons.Outlined.PlayArrow, !busy, onResume)
            }
            if (onDelete != null) {
                OutlinedButton(
                    onClick = onDelete,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("Delete download", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun ManagementButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(label)
    }
}

@Composable
private fun RenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    val trimmed = name.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename download") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Name") },
                singleLine = false,
                minLines = 1,
                maxLines = 4,
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotEmpty() && trimmed != currentName,
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TagsDialog(
    currentTags: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var text by remember(currentTags) { mutableStateOf(currentTags.joinToString(", ")) }
    val tags = remember(text) {
        text.split(',', '\n')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy(String::lowercase)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit tags") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Tags") },
                supportingText = { Text("Separate tags with commas or new lines.") },
                minLines = 3,
                maxLines = 6,
            )
        },
        confirmButton = { Button(onClick = { onConfirm(tags) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmAirLockDialog(
    currentlyAirLocked: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (currentlyAirLocked) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                contentDescription = null,
            )
        },
        title = { Text(if (currentlyAirLocked) "Remove from AirLock?" else "Move to AirLock?") },
        text = {
            Text(
                if (currentlyAirLocked) {
                    "TorBox will return this download to its normal retention schedule, so it may expire."
                } else {
                    "TorBox will retain this finished download while AirLock remains enabled."
                },
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(if (currentlyAirLocked) "Remove" else "Move")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ConfirmDeleteDialog(
    name: String,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
        title = { Text("Delete download?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "This removes “${name.ellipsizeMiddle(72)}” from TorBox. " +
                        "The action may also remove its stored files and cannot be undone here.",
                )
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, enabled = !busy) { Text(if (busy) "Deleting…" else "Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

private enum class DetailDialog { Rename, Tags, AirLock, Delete }

private data class DetailRow(val label: String, val value: String)

private fun transferDetails(download: DownloadItem): List<DetailRow>? {
    val rows = buildList {
        download.totalSize?.let { add(DetailRow("Total size", formatBytes(it))) }
        download.downloadedBytes?.let { add(DetailRow("Downloaded", formatBytes(it))) }
        download.downloadSpeed?.let { add(DetailRow("Download speed", "${formatBytes(it)}/s")) }
        download.uploadSpeed?.let { add(DetailRow("Upload speed", "${formatBytes(it)}/s")) }
        download.etaSeconds?.let { add(DetailRow("ETA", formatDuration(it))) }
        download.seeds?.let { add(DetailRow("Seeds", it.toString())) }
        download.peers?.let { add(DetailRow("Peers", it.toString())) }
        download.ratio?.let { add(DetailRow("Ratio", formatDecimal(it))) }
        download.availability?.let { add(DetailRow("Availability", formatDecimal(it))) }
        download.fileCount?.let { add(DetailRow("Files", it.toString())) }
    }
    return rows.takeIf { it.isNotEmpty() }
}

private fun identityDetails(download: DownloadItem): List<DetailRow>? {
    val rows = buildList {
        download.hash?.takeIf(String::isNotBlank)?.let { add(DetailRow("Hash", it)) }
        download.originalSource?.takeIf(String::isNotBlank)?.let { add(DetailRow("Source", it)) }
    }
    return rows.takeIf { it.isNotEmpty() }
}

private fun timelineDetails(download: DownloadItem): List<DetailRow>? {
    val rows = buildList {
        download.createdAt?.let { add(DetailRow("Added", formatInstant(it))) }
        download.updatedAt?.let { add(DetailRow("Updated", formatInstant(it))) }
        download.cachedAt?.let { add(DetailRow("Cached", formatInstant(it))) }
        download.expiresAt?.let {
            val prefix = if (it.isBefore(Instant.now())) "Expired" else "Expires"
            add(DetailRow(prefix, formatInstant(it)))
        }
    }
    return rows.takeIf { it.isNotEmpty() }
}

private fun statusColors(download: DownloadItem): Pair<Color, Color> = when {
    download.isProblem -> Color(0xFF5B1A1A) to Color(0xFFFFDAD6)
    download.airLocked -> Color(0xFF493E00) to Color(0xFFFFE16B)
    download.isReady -> Color(0xFF0A493F) to Color(0xFFB8F5E8)
    else -> Color(0xFF123F4A) to Color(0xFFBDEBF5)
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "–"
    if (bytes < 1_000) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB", "PB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1_000 && unit < units.lastIndex) {
        value /= 1_000
        unit++
    }
    val precision = if (value >= 100 || value % 1.0 == 0.0) 0 else 1
    return String.format(Locale.getDefault(), "%.${precision}f %s", value, units[unit])
}

private fun formatDuration(seconds: Long): String {
    if (seconds < 0) return "Unknown"
    val duration = Duration.ofSeconds(seconds)
    val days = duration.toDays()
    val hours = duration.minusDays(days).toHours()
    val minutes = duration.minusDays(days).minusHours(hours).toMinutes()
    return buildList {
        if (days > 0) add("${days}d")
        if (hours > 0 || days > 0) add("${hours}h")
        add("${minutes}m")
    }.joinToString(" ")
}

private fun formatDecimal(value: Double): String =
    if (value.isFinite()) String.format(Locale.getDefault(), "%.2f", value) else "–"

private val detailDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

private fun formatInstant(instant: Instant): String =
    runCatching { detailDateFormatter.format(instant.atZone(ZoneId.systemDefault())) }
        .getOrElse { instant.toString() }

private fun String.ellipsizeMiddle(maxLength: Int): String {
    if (length <= maxLength) return this
    val half = (maxLength - 1) / 2
    return take(half) + "…" + takeLast(half)
}
