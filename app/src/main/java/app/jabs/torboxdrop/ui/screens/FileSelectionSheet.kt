package app.jabs.torboxdrop.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.jabs.torboxdrop.model.DownloadFile
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.util.FileBrowserEntry
import app.jabs.torboxdrop.util.FileBrowserResult
import app.jabs.torboxdrop.util.FileSort
import app.jabs.torboxdrop.util.browseDownloadFiles
import app.jabs.torboxdrop.util.parentFolder
import java.util.Locale

/**
 * A searchable file picker for a download.
 *
 * Infected files cannot be opened, shared, or included in multi-share. A single infected
 * file can only reach [onDownloadFile] after an explicit warning. ZIP is intentionally a
 * whole-download action and is never synthesized from the current selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileSelectionSheet(
    download: DownloadItem,
    files: List<DownloadFile>,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    error: String? = null,
    sharingFileId: Long? = null,
    shareError: String? = null,
    onRetry: (() -> Unit)? = null,
    onOpenFile: ((DownloadFile) -> Unit)? = null,
    onDownloadFile: ((DownloadFile) -> Unit)? = null,
    onShareFile: ((DownloadFile) -> Unit)? = null,
    onCopyTemporaryLink: ((DownloadFile) -> Unit)? = null,
    onShareFiles: ((List<DownloadFile>) -> Unit)? = null,
    onDownloadZip: (() -> Unit)? = null,
    zipEligible: Boolean = download.allowZip == true,
) {
    var query by rememberSaveable(download.id) { mutableStateOf("") }
    var currentFolder by rememberSaveable(download.id) { mutableStateOf("") }
    var extensionFilter by rememberSaveable(download.id) { mutableStateOf<String?>(null) }
    var sortName by rememberSaveable(download.id) { mutableStateOf(FileSort.NAME_ASC.name) }
    var selectionMode by remember(download.id) { mutableStateOf(false) }
    var selectedIds by remember(download.id) { mutableStateOf(emptySet<Long>()) }
    var infectedDownload by remember(download.id) { mutableStateOf<DownloadFile?>(null) }
    var pendingMultiShare by remember(download.id) { mutableStateOf<List<DownloadFile>?>(null) }

    val safeFiles = remember(files) { files.filterNot(DownloadFile::infected) }
    val fileSort = remember(sortName) {
        FileSort.entries.firstOrNull { it.name == sortName } ?: FileSort.NAME_ASC
    }
    val browser = remember(files, currentFolder, query, extensionFilter, fileSort, download.name) {
        browseDownloadFiles(
            files = files,
            currentFolder = currentFolder,
            query = query,
            extensionFilter = extensionFilter,
            sort = fileSort,
            downloadName = download.name,
        )
    }
    val selectedFiles = remember(safeFiles, selectedIds) {
        safeFiles.filter { it.id in selectedIds }
    }
    val containsInfectedFiles = files.any(DownloadFile::infected)
    val contentReady = download.isReady
    val zipAvailable = onDownloadZip != null &&
        zipEligible &&
        contentReady &&
        !isLoading &&
        error == null &&
        !containsInfectedFiles

    LaunchedEffect(files) {
        val validIds = files.asSequence().filterNot(DownloadFile::infected).map(DownloadFile::id).toSet()
        selectedIds = selectedIds.intersect(validIds)
        if (validIds.size < 2) selectionMode = false
    }

    LaunchedEffect(browser.currentFolder) {
        if (currentFolder != browser.currentFolder) currentFolder = browser.currentFolder
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .navigationBarsPadding()
                .imePadding(),
        ) {
            // Register inside the sheet content so folder-up takes precedence over sheet dismissal.
            BackHandler(enabled = currentFolder.isNotBlank()) {
                currentFolder = parentFolder(currentFolder)
            }

            FileSheetHeader(
                title = download.name,
                fileCount = files.size,
                selectionMode = selectionMode,
                canSelect = onShareFiles != null && safeFiles.size > 1 && contentReady,
                selectedCount = selectedFiles.size,
                onToggleSelection = {
                    selectionMode = !selectionMode
                    if (!selectionMode) selectedIds = emptySet()
                },
                onDismiss = onDismissRequest,
            )

            if (containsInfectedFiles) {
                InfectedFilesBanner(count = files.count(DownloadFile::infected))
            }

            if (sharingFileId != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Preparing a new temporary share link…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            } else if (shareError != null) {
                InlineError(message = shareError, onRetry = null)
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear search")
                        }
                    }
                },
                placeholder = { Text("Search files") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )

            FileBrowserControls(
                browser = browser,
                selectedExtension = extensionFilter,
                selectedSort = fileSort,
                onFolderSelected = { currentFolder = it },
                onExtensionSelected = { extensionFilter = it },
                onSortSelected = { sortName = it.name },
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                FileSheetBody(
                    browser = browser,
                    allFilesEmpty = files.isEmpty(),
                    query = query,
                    extensionFilter = extensionFilter,
                    isLoading = isLoading,
                    error = error,
                    selectionMode = selectionMode,
                    selectedIds = selectedIds,
                    contentReady = contentReady,
                    shareInProgress = sharingFileId != null,
                    onRetry = onRetry,
                    onToggleSelected = { file ->
                        if (!file.infected) {
                            selectedIds = if (file.id in selectedIds) {
                                selectedIds - file.id
                            } else {
                                selectedIds + file.id
                            }
                        }
                    },
                    onOpenFolder = { currentFolder = it },
                    onOpenFile = onOpenFile,
                    onDownloadFile = onDownloadFile?.let { callback ->
                        { file ->
                            if (file.infected) infectedDownload = file else callback(file)
                        }
                    },
                    onShareFile = onShareFile,
                    onCopyTemporaryLink = onCopyTemporaryLink,
                )
            }

            FileSheetFooter(
                selectionMode = selectionMode,
                selectedFiles = selectedFiles,
                canShareSelection = onShareFiles != null && contentReady,
                showZipAction = onDownloadZip != null,
                zipAvailable = zipAvailable,
                zipUnavailableReason = zipUnavailableReason(
                    download = download,
                    zipEligible = zipEligible,
                    isLoading = isLoading,
                    error = error,
                    containsInfectedFiles = containsInfectedFiles,
                ),
                onShareSelection = {
                    if (selectedFiles.size > 1) {
                        pendingMultiShare = selectedFiles
                    } else if (selectedFiles.size == 1) {
                        onShareFiles?.invoke(selectedFiles)
                        selectionMode = false
                        selectedIds = emptySet()
                    }
                },
                onDownloadZip = { if (zipAvailable) onDownloadZip?.invoke() },
            )
        }
    }

    infectedDownload?.let { file ->
        InfectedDownloadDialog(
            file = file,
            onDismiss = { infectedDownload = null },
            onConfirm = {
                infectedDownload = null
                onDownloadFile?.invoke(file)
            },
        )
    }

    pendingMultiShare?.let { selected ->
        MultiShareDialog(
            count = selected.size,
            onDismiss = { pendingMultiShare = null },
            onConfirm = {
                pendingMultiShare = null
                onShareFiles?.invoke(selected)
                selectionMode = false
                selectedIds = emptySet()
            },
        )
    }
}

@Composable
private fun FileSheetHeader(
    title: String,
    fileCount: Int,
    selectionMode: Boolean,
    canSelect: Boolean,
    selectedCount: Int,
    onToggleSelection: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (selectionMode) {
                    if (selectedCount == 0) "Choose files" else "$selectedCount selected"
                } else {
                    "Files"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (selectionMode) "Select safe files to share" else "$fileCount files · $title",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (canSelect || selectionMode) {
            TextButton(onClick = onToggleSelection) {
                Icon(
                    if (selectionMode) Icons.Outlined.Close else Icons.Outlined.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(if (selectionMode) "Cancel" else "Select")
            }
        }

        IconButton(onClick = onDismiss) {
            Icon(Icons.Outlined.Close, contentDescription = "Close file list")
        }
    }
}

@Composable
private fun FileBrowserControls(
    browser: FileBrowserResult,
    selectedExtension: String?,
    selectedSort: FileSort,
    onFolderSelected: (String) -> Unit,
    onExtensionSelected: (String?) -> Unit,
    onSortSelected: (FileSort) -> Unit,
) {
    var showTypeMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            browser.breadcrumbs.forEachIndexed { index, crumb ->
                if (index > 0) {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onFolderSelected(crumb.path) }) {
                    Text(
                        text = crumb.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (index == browser.breadcrumbs.lastIndex) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                OutlinedButton(onClick = { showTypeMenu = true }) {
                    Icon(Icons.Outlined.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(selectedExtension?.uppercase(Locale.getDefault()) ?: "All types")
                }
                DropdownMenu(
                    expanded = showTypeMenu,
                    onDismissRequest = { showTypeMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("All file types (${browser.scopeFileCount})") },
                        leadingIcon = if (selectedExtension == null) {
                            { Icon(Icons.Outlined.Check, contentDescription = null) }
                        } else null,
                        onClick = {
                            onExtensionSelected(null)
                            showTypeMenu = false
                        },
                    )
                    browser.extensionCounts.forEach { (extension, count) ->
                        DropdownMenuItem(
                            text = { Text("${extension.uppercase(Locale.getDefault())} ($count)") },
                            leadingIcon = if (selectedExtension.equals(extension, ignoreCase = true)) {
                                { Icon(Icons.Outlined.Check, contentDescription = null) }
                            } else null,
                            onClick = {
                                onExtensionSelected(extension)
                                showTypeMenu = false
                            },
                        )
                    }
                }
            }

            Box {
                OutlinedButton(onClick = { showSortMenu = true }) {
                    Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(selectedSort.displayLabel())
                }
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false },
                ) {
                    FileSort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(sort.displayLabel()) },
                            leadingIcon = if (sort == selectedSort) {
                                { Icon(Icons.Outlined.Check, contentDescription = null) }
                            } else null,
                            onClick = {
                                onSortSelected(sort)
                                showSortMenu = false
                            },
                        )
                    }
                }
            }

            Text(
                text = if (browser.recursiveResults) {
                    "${browser.matchingFileCount} match${if (browser.matchingFileCount == 1) "" else "es"}"
                } else {
                    "${browser.scopeFileCount} file${if (browser.scopeFileCount == 1) "" else "s"}"
                },
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InfectedFilesBanner(count: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = if (count == 1) "1 file was flagged" else "$count files were flagged",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Flagged files cannot be opened, shared, multi-selected, or included in a ZIP download.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun FileSheetBody(
    browser: FileBrowserResult,
    allFilesEmpty: Boolean,
    query: String,
    extensionFilter: String?,
    isLoading: Boolean,
    error: String?,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
    contentReady: Boolean,
    shareInProgress: Boolean,
    onRetry: (() -> Unit)?,
    onToggleSelected: (DownloadFile) -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenFile: ((DownloadFile) -> Unit)?,
    onDownloadFile: ((DownloadFile) -> Unit)?,
    onShareFile: ((DownloadFile) -> Unit)?,
    onCopyTemporaryLink: ((DownloadFile) -> Unit)?,
) {
    when {
        isLoading && allFilesEmpty -> FileLoadingState()
        error != null && allFilesEmpty -> FileErrorState(message = error, onRetry = onRetry)
        allFilesEmpty -> FileEmptyState(
            title = "No files",
            message = "This download does not contain any available files.",
        )
        browser.entries.isEmpty() -> FileEmptyState(
            title = "No matches",
            message = when {
                query.isNotBlank() && extensionFilter != null ->
                    "No ${extensionFilter.uppercase(Locale.getDefault())} files match “${query.trim()}” here."
                query.isNotBlank() -> "No files match “${query.trim()}” here."
                extensionFilter != null ->
                    "No ${extensionFilter.uppercase(Locale.getDefault())} files are in this folder."
                else -> "This folder is empty."
            },
        )
        else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (error != null) {
                item(key = "error") {
                    InlineError(message = error, onRetry = onRetry)
                }
            }
            items(
                items = browser.entries,
                key = FileBrowserEntry::stableKey,
            ) { entry ->
                when (entry) {
                    is FileBrowserEntry.Folder -> FolderRow(
                        folder = entry,
                        onOpen = { onOpenFolder(entry.path) },
                    )
                    is FileBrowserEntry.File -> {
                        val file = entry.file
                        FileRow(
                            file = file,
                            parentPath = entry.parentPath.takeIf { browser.recursiveResults && it.isNotBlank() },
                            selectionMode = selectionMode,
                            selected = file.id in selectedIds,
                            contentReady = contentReady,
                            onToggleSelected = { onToggleSelected(file) },
                            onOpen = onOpenFile?.let { callback -> { callback(file) } },
                            onDownload = onDownloadFile?.let { callback -> { callback(file) } },
                            downloadActionAvailable = contentReady && onDownloadFile != null,
                            onShare = onShareFile?.let { callback -> { callback(file) } },
                            shareInProgress = shareInProgress,
                            onCopyTemporaryLink = onCopyTemporaryLink?.let { callback -> { callback(file) } },
                        )
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(start = if (selectionMode) 60.dp else 64.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun FolderRow(
    folder: FileBrowserEntry.Folder,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(23.dp))
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildList {
                    add(if (folder.fileCount == 1) "1 file" else "${folder.fileCount} files")
                    folder.totalSize?.let { add(formatFileBytes(it)) }
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            Icons.Outlined.ChevronRight,
            contentDescription = "Open ${folder.name}",
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FileRow(
    file: DownloadFile,
    parentPath: String?,
    selectionMode: Boolean,
    selected: Boolean,
    contentReady: Boolean,
    onToggleSelected: () -> Unit,
    onOpen: (() -> Unit)?,
    onDownload: (() -> Unit)?,
    downloadActionAvailable: Boolean,
    onShare: (() -> Unit)?,
    shareInProgress: Boolean,
    onCopyTemporaryLink: (() -> Unit)?,
) {
    val safe = !file.infected
    val rowClick: (() -> Unit)? = when {
        selectionMode && safe -> onToggleSelected
        !selectionMode && safe && contentReady && onOpen != null -> onOpen
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = rowClick != null) { rowClick?.invoke() }
            .padding(start = 12.dp, end = 8.dp, top = 9.dp, bottom = 7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelected() },
                    enabled = safe,
                )
            } else {
                Surface(
                    modifier = Modifier.size(42.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = if (file.infected) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    contentColor = if (file.infected) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = fileTypeIcon(file),
                            contentDescription = null,
                            modifier = Modifier.size(23.dp),
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                parentPath?.let { path ->
                    Text(
                        text = path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val metadata = buildList {
                    file.size?.let { add(formatFileBytes(it)) }
                    fileTypeLabel(file)?.let(::add)
                }.joinToString(" · ")
                if (metadata.isNotBlank()) {
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (file.infected) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.WarningAmber,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Potentially infected",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        if (!selectionMode) {
            Row(
                modifier = Modifier.padding(start = 48.dp, top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onOpen != null) {
                    FileActionButton(
                        icon = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = "Open ${file.name}",
                        enabled = safe && contentReady,
                        onClick = onOpen,
                    )
                }
                if (onDownload != null) {
                    FileActionButton(
                        icon = Icons.Outlined.Download,
                        contentDescription = if (file.infected) {
                            "Review warning before downloading ${file.name}"
                        } else {
                            "Download ${file.name}"
                        },
                        tint = if (file.infected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        enabled = downloadActionAvailable,
                        onClick = onDownload,
                    )
                }
                if (onShare != null) {
                    FileActionButton(
                        icon = Icons.Outlined.Share,
                        contentDescription = "Share ${file.name}",
                        enabled = safe && contentReady && !shareInProgress,
                        onClick = onShare,
                    )
                }
                if (onCopyTemporaryLink != null) {
                    FileActionButton(
                        icon = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy temporary link for ${file.name}",
                        enabled = safe && contentReady,
                        onClick = onCopyTemporaryLink,
                    )
                }
            }
        }
    }
}

@Composable
private fun FileActionButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(20.dp),
            tint = tint,
        )
    }
}

@Composable
private fun FileSheetFooter(
    selectionMode: Boolean,
    selectedFiles: List<DownloadFile>,
    canShareSelection: Boolean,
    showZipAction: Boolean,
    zipAvailable: Boolean,
    zipUnavailableReason: String?,
    onShareSelection: () -> Unit,
    onDownloadZip: () -> Unit,
) {
    if (!selectionMode && !showZipAction) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (selectionMode) {
                Button(
                    onClick = onShareSelection,
                    enabled = canShareSelection && selectedFiles.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (selectedFiles.size == 1) "Share selected file" else "Share ${selectedFiles.size} files",
                    )
                }
                Text(
                    text = "Multiple selections are shared as separate files; ZIP always means the whole download.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (showZipAction) {
                FilledTonalButton(
                    onClick = onDownloadZip,
                    enabled = zipAvailable,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.Archive, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Download whole ZIP")
                }
                zipUnavailableReason?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FileLoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator()
            Text("Loading files…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FileErrorState(message: String, onRetry: (() -> Unit)?) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.error,
            )
            Text("Couldn’t load files", style = MaterialTheme.typography.titleMedium)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (onRetry != null) {
                OutlinedButton(onClick = onRetry) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Try again")
                }
            }
        }
    }
}

@Composable
private fun FileEmptyState(title: String, message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.InsertDriveFile,
                contentDescription = null,
                modifier = Modifier.size(38.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InlineError(message: String, onRetry: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Text(
            text = message,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun InfectedDownloadDialog(
    file: DownloadFile,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text("Download flagged file?") },
        text = {
            Text(
                "“${file.name.fileDialogName()}” was flagged as potentially infected. " +
                    "Do not open it unless you trust the source and can scan it safely.",
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Download anyway") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun MultiShareDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Share, contentDescription = null) },
        title = { Text("Share $count files?") },
        text = {
            Text(
                "The selected files will be sent separately to Android’s share sheet. " +
                    "Only choose an app and recipient you trust.",
            )
        },
        confirmButton = { Button(onClick = onConfirm) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun fileTypeIcon(file: DownloadFile): ImageVector {
    val mime = file.mimeType.orEmpty().lowercase(Locale.ROOT)
    val extension = file.name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
    return when {
        mime.startsWith("image/") -> Icons.Outlined.Image
        mime.startsWith("video/") -> Icons.Outlined.Movie
        mime.startsWith("audio/") -> Icons.Outlined.Audiotrack
        mime == "application/pdf" || extension == "pdf" -> Icons.Outlined.PictureAsPdf
        mime.startsWith("text/") -> Icons.Outlined.Description
        mime.contains("zip") || mime.contains("archive") || extension in archiveExtensions -> Icons.Outlined.Archive
        mime.contains("json") || mime.contains("xml") || extension in codeExtensions -> Icons.Outlined.Code
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }
}

private fun fileTypeLabel(file: DownloadFile): String? {
    val mime = file.mimeType?.takeIf(String::isNotBlank)
    if (mime != null) return mime.substringAfter('/').substringBefore(';').uppercase(Locale.getDefault())
    return file.name.substringAfterLast('.', missingDelimiterValue = "")
        .takeIf(String::isNotBlank)
        ?.uppercase(Locale.getDefault())
}

private fun FileSort.displayLabel(): String = when (this) {
    FileSort.NAME_ASC -> "Name A–Z"
    FileSort.NAME_DESC -> "Name Z–A"
    FileSort.SIZE_DESC -> "Largest"
    FileSort.SIZE_ASC -> "Smallest"
    FileSort.TYPE -> "File type"
}

private fun zipUnavailableReason(
    download: DownloadItem,
    zipEligible: Boolean,
    isLoading: Boolean,
    error: String?,
    containsInfectedFiles: Boolean,
): String? = when {
    isLoading -> "ZIP is available after the file list is checked."
    error != null -> "ZIP is unavailable until the file list can be verified."
    containsInfectedFiles -> "ZIP is disabled because it would include a flagged file."
    !download.isReady -> "ZIP is available when the download is complete and present."
    !zipEligible -> "This download is not eligible for a ZIP archive."
    else -> null
}

private fun formatFileBytes(bytes: Long): String {
    if (bytes < 0) return "Unknown size"
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

private fun String.fileDialogName(): String =
    if (length <= 80) this else take(39) + "…" + takeLast(39)

private val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
private val codeExtensions = setOf("json", "xml", "html", "htm", "js", "kt", "java", "py", "sh", "css")
