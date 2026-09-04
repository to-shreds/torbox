package app.jabs.torboxdrop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.TableRows
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.jabs.torboxdrop.model.DownloadDensity
import app.jabs.torboxdrop.model.DownloadFilter
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadSort
import app.jabs.torboxdrop.model.DownloadTab
import app.jabs.torboxdrop.model.DownloadType
import app.jabs.torboxdrop.model.DownloadsUiState
import app.jabs.torboxdrop.model.QueuedDownload
import app.jabs.torboxdrop.util.DownloadLists
import app.jabs.torboxdrop.ui.theme.TorBoxColors
import java.time.Instant

internal const val WIDE_LAYOUT_MIN_WIDTH_DP = 840

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(
    state: DownloadsUiState,
    modifier: Modifier = Modifier,
    watchedDownloadIds: Set<String> = emptySet(),
    watchedDownloadKeys: Set<Pair<DownloadType, String>> = emptySet(),
    downloadKeysMatchingLoadedFiles: Set<String> = emptySet(),
    onRefresh: () -> Unit,
    onTabSelected: (DownloadTab) -> Unit,
    onDensityChanged: (DownloadDensity) -> Unit,
    onSearchChanged: (String) -> Unit,
    onSortChanged: (DownloadSort) -> Unit,
    onFilterChanged: (DownloadFilter) -> Unit,
    onDownloadClick: (DownloadItem) -> Unit,
    onToggleNotification: (DownloadItem) -> Unit,
    onShare: (DownloadItem) -> Unit,
    onFiles: (DownloadItem) -> Unit,
    onToggleAirLock: (DownloadItem) -> Unit,
    onDownloadMenu: (DownloadItem) -> Unit,
    onStartQueued: (QueuedDownload) -> Unit,
    onDeleteQueued: (QueuedDownload) -> Unit,
) {
    var searchVisible by rememberSaveable { mutableStateOf(state.search.isNotEmpty()) }
    var densityMenuVisible by remember { mutableStateOf(false) }
    var organizeMenuVisible by remember { mutableStateOf(false) }
    var pendingAirLockRemoval by remember { mutableStateOf<DownloadItem?>(null) }
    val activeListState = rememberLazyListState()
    val finishedListState = rememberLazyListState()
    val queueListState = rememberLazyListState()
    val airLockListState = rememberLazyListState()
    val selectedListState = when (state.selectedTab) {
        DownloadTab.ACTIVE -> activeListState
        DownloadTab.FINISHED -> finishedListState
        DownloadTab.QUEUE -> queueListState
        DownloadTab.AIRLOCK -> airLockListState
    }
    val closeSearch: () -> Unit = {
        onSearchChanged("")
        searchVisible = false
    }

    val visibleDownloads = remember(
        state.downloads,
        state.selectedTab,
        state.search,
        state.filter,
        state.sort,
        downloadKeysMatchingLoadedFiles,
    ) {
        val source = when (state.selectedTab) {
            DownloadTab.ACTIVE -> state.active
            DownloadTab.FINISHED -> state.finished
            DownloadTab.AIRLOCK -> state.airLocked
            DownloadTab.QUEUE -> emptyList()
        }
        DownloadLists.filterAndSort(
            items = source,
            search = state.search,
            filter = state.filter,
            sort = state.sort,
            keysMatchingFiles = downloadKeysMatchingLoadedFiles,
        )
    }
    val visibleQueue = remember(state.queue, state.search, state.filter, state.sort) {
        state.queue
            .asSequence()
            .filter { queued ->
                (state.filter.type == null || queued.type == state.filter.type) &&
                    (state.search.isBlank() || queued.name.contains(state.search, true) ||
                        queued.source?.contains(state.search, true) == true)
            }
            .sortedWith(state.sort.effectiveFor(state.selectedTab).queueComparator())
            .toList()
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        DownloadsToolbar(
            refreshing = state.refreshing,
            selectedTab = state.selectedTab,
            filterActive = state.filter.isActiveFor(state.selectedTab),
            density = state.density,
            onRefresh = onRefresh,
            onSearch = {
                if (searchVisible) closeSearch() else searchVisible = true
            },
            densityMenuVisible = densityMenuVisible,
            onDensityMenuChanged = { densityMenuVisible = it },
            onDensityChanged = onDensityChanged,
            organizeMenuVisible = organizeMenuVisible,
            onOrganizeMenuChanged = { organizeMenuVisible = it },
            sort = state.sort,
            filter = state.filter,
            onSortChanged = onSortChanged,
            onFilterChanged = onFilterChanged,
        )
        DownloadSummary(
            active = state.active.size,
            queue = state.queue.size,
            ready = state.finished.size,
            airLock = state.airLocked.size,
        )
        if (state.offline || state.stale) {
            FreshnessBanner(offline = state.offline, lastUpdated = state.lastUpdated)
        }
        if (state.error != null && (state.downloads.isNotEmpty() || state.queue.isNotEmpty())) {
            RefreshErrorBanner(message = state.error, onRetry = onRefresh)
        }
        if (searchVisible) {
            CompactSearchField(
                query = state.search,
                onQueryChanged = onSearchChanged,
                onClose = closeSearch,
            )
        }
        DownloadsTabs(
            selected = state.selectedTab,
            onSelected = onTabSelected,
            counts = mapOf(
                DownloadTab.ACTIVE to state.active.size,
                DownloadTab.FINISHED to state.finished.size,
                DownloadTab.QUEUE to state.queue.size,
                DownloadTab.AIRLOCK to state.airLocked.size,
            ),
        )

        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= WIDE_LAYOUT_MIN_WIDTH_DP.dp
                when {
                    state.initialLoading && state.downloads.isEmpty() && state.queue.isEmpty() -> LoadingRows(state.density)
                    state.error != null && state.downloads.isEmpty() && state.queue.isEmpty() -> ErrorState(
                        message = state.error,
                        onRetry = onRefresh,
                    )
                    state.selectedTab == DownloadTab.QUEUE -> QueueList(
                        queue = visibleQueue,
                        density = state.density,
                        wide = wide,
                        listState = selectedListState,
                        searchOrFilterActive = state.search.isNotBlank() || state.filter.isActiveFor(DownloadTab.QUEUE),
                        onStart = onStartQueued,
                        onDelete = onDeleteQueued,
                    )
                    else -> DownloadList(
                        downloads = visibleDownloads,
                        tab = state.selectedTab,
                        density = state.density,
                        wide = wide,
                        listState = selectedListState,
                        watchedDownloadIds = watchedDownloadIds,
                        watchedDownloadKeys = watchedDownloadKeys,
                        searchOrFilterActive = state.search.isNotBlank() || state.filter.isActive,
                        onClick = onDownloadClick,
                        onToggleNotification = onToggleNotification,
                        onShare = onShare,
                        onFiles = onFiles,
                        onToggleAirLock = { item ->
                            if (item.airLocked) pendingAirLockRemoval = item else onToggleAirLock(item)
                        },
                        onMenu = onDownloadMenu,
                    )
                }
            }
        }
    }

    pendingAirLockRemoval?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingAirLockRemoval = null },
            icon = { Icon(Icons.Outlined.LockOpen, contentDescription = null) },
            title = { Text("Remove from AirLock?") },
            text = { Text("“${item.name}” will return to TorBox’s normal retention schedule and may expire.") },
            dismissButton = { TextButton(onClick = { pendingAirLockRemoval = null }) { Text("Keep protected") } },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingAirLockRemoval = null
                        onToggleAirLock(item)
                    },
                ) { Text("Remove") }
            },
        )
    }
}

@Composable
private fun DownloadsToolbar(
    refreshing: Boolean,
    selectedTab: DownloadTab,
    filterActive: Boolean,
    density: DownloadDensity,
    onRefresh: () -> Unit,
    onSearch: () -> Unit,
    densityMenuVisible: Boolean,
    onDensityMenuChanged: (Boolean) -> Unit,
    onDensityChanged: (DownloadDensity) -> Unit,
    organizeMenuVisible: Boolean,
    onOrganizeMenuChanged: (Boolean) -> Unit,
    sort: DownloadSort,
    filter: DownloadFilter,
    onSortChanged: (DownloadSort) -> Unit,
    onFilterChanged: (DownloadFilter) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("TorBox", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("Downloads", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onSearch) {
            Icon(Icons.Outlined.Search, contentDescription = "Search downloads")
        }
        Box {
            IconButton(onClick = { onOrganizeMenuChanged(true) }) {
                Box {
                    Icon(Icons.Outlined.Tune, contentDescription = "Filter and sort downloads")
                    if (filterActive) {
                        Badge(modifier = Modifier.align(Alignment.TopEnd).size(7.dp))
                    }
                }
            }
            OrganizeMenu(
                expanded = organizeMenuVisible,
                selectedTab = selectedTab,
                sort = sort,
                filter = filter,
                onDismiss = { onOrganizeMenuChanged(false) },
                onSortChanged = onSortChanged,
                onFilterChanged = onFilterChanged,
            )
        }
        Box {
            IconButton(onClick = { onDensityMenuChanged(true) }) {
                Icon(density.icon(), contentDescription = "Download list density: ${density.label()}")
            }
            DropdownMenu(expanded = densityMenuVisible, onDismissRequest = { onDensityMenuChanged(false) }) {
                DownloadDensity.entries.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(option.label(), fontWeight = if (option == density) FontWeight.Bold else FontWeight.Normal)
                                Text(option.description(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        leadingIcon = { Icon(option.icon(), contentDescription = null) },
                        onClick = {
                            onDensityChanged(option)
                            onDensityMenuChanged(false)
                        },
                    )
                }
            }
        }
        IconButton(onClick = onRefresh, enabled = !refreshing) {
            if (refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Refresh, contentDescription = "Refresh downloads")
            }
        }
    }
}

@Composable
private fun OrganizeMenu(
    expanded: Boolean,
    selectedTab: DownloadTab,
    sort: DownloadSort,
    filter: DownloadFilter,
    onDismiss: () -> Unit,
    onSortChanged: (DownloadSort) -> Unit,
    onFilterChanged: (DownloadFilter) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            "SORT",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        sortOptionsFor(selectedTab).forEach { option ->
            DropdownMenuItem(
                text = {
                    Text(
                        option.label(),
                        fontWeight = if (option == sort.effectiveFor(selectedTab)) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null) },
                onClick = {
                    onSortChanged(option)
                    onDismiss()
                },
            )
        }
        HorizontalDivider()
        Text(
            "FILTER",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        FilterMenuItem("Torrents", filter.type == DownloadType.TORRENT) {
            onFilterChanged(filter.copy(type = if (filter.type == DownloadType.TORRENT) null else DownloadType.TORRENT))
        }
        FilterMenuItem("Web downloads", filter.type == DownloadType.WEB) {
            onFilterChanged(filter.copy(type = if (filter.type == DownloadType.WEB) null else DownloadType.WEB))
        }
        if (selectedTab != DownloadTab.QUEUE) {
            FilterMenuItem("Problems only", filter.problemsOnly) {
                onFilterChanged(filter.copy(problemsOnly = !filter.problemsOnly))
            }
            FilterMenuItem("Cached", filter.cachedOnly) {
                onFilterChanged(filter.copy(cachedOnly = !filter.cachedOnly))
            }
            FilterMenuItem("Tagged", filter.taggedOnly) {
                onFilterChanged(filter.copy(taggedOnly = !filter.taggedOnly))
            }
        }
        if (filter.isActiveFor(selectedTab)) {
            TextButton(
                onClick = { onFilterChanged(filter.clearedFor(selectedTab)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Clear filters") }
        }
    }
}

@Composable
private fun FilterMenuItem(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
        onClick = onClick,
    )
}

@Composable
private fun DownloadSummary(active: Int, queue: Int, ready: Int, airLock: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SummaryItem("Active", active, MaterialTheme.colorScheme.primary)
        SummaryItem("Queue", queue, MaterialTheme.colorScheme.tertiary)
        SummaryItem("Ready", ready, TorBoxColors.Green)
        SummaryItem("AirLock", airLock, MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SummaryItem(label: String, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(color))
        Text(count.toString(), fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
    }
}

@Composable
private fun FreshnessBanner(offline: Boolean, lastUpdated: Instant?) {
    val text = if (offline) {
        listOfNotNull("Offline", formatRelativeUpdate(lastUpdated)).joinToString(" · ")
    } else {
        listOfNotNull("Showing saved data", formatRelativeUpdate(lastUpdated)).joinToString(" · ")
    }
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .55f)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 30.dp).padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(7.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun RefreshErrorBanner(message: String, onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .65f)) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(7.dp))
            Text(
                message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun CompactSearchField(query: String, onQueryChanged: (String) -> Unit, onClose: () -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        singleLine = true,
        placeholder = { Text("Search names, loaded files, or tags") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close search") } },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 2.dp),
        textStyle = MaterialTheme.typography.bodyMedium,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsTabs(selected: DownloadTab, onSelected: (DownloadTab) -> Unit, counts: Map<DownloadTab, Int>) {
    PrimaryTabRow(selectedTabIndex = DownloadTab.entries.indexOf(selected), divider = {}) {
        DownloadTab.entries.forEach { tab ->
            Tab(
                selected = tab == selected,
                onClick = { onSelected(tab) },
                modifier = Modifier.height(40.dp),
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(tab.label(), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                        val count = counts[tab] ?: 0
                        if (count > 0) Text(count.toString(), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        }
    }
}

@Composable
private fun DownloadList(
    downloads: List<DownloadItem>,
    tab: DownloadTab,
    density: DownloadDensity,
    wide: Boolean,
    listState: LazyListState,
    watchedDownloadIds: Set<String>,
    watchedDownloadKeys: Set<Pair<DownloadType, String>>,
    searchOrFilterActive: Boolean,
    onClick: (DownloadItem) -> Unit,
    onToggleNotification: (DownloadItem) -> Unit,
    onShare: (DownloadItem) -> Unit,
    onFiles: (DownloadItem) -> Unit,
    onToggleAirLock: (DownloadItem) -> Unit,
    onMenu: (DownloadItem) -> Unit,
) {
    if (downloads.isEmpty()) {
        EmptyDownloadsState(tab, searchOrFilterActive)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
        if (wide) {
            item(key = "column-headings", contentType = "column-headings") {
                WideColumnHeadings(tab)
            }
        }
        items(
            items = downloads,
            key = { "${it.type}-${it.id}" },
            contentType = { "${tab.name}-${density.name}" },
        ) { item ->
            if (wide) {
                WideDownloadRow(
                    item = item,
                    tab = tab,
                    density = density,
                    watched = item.id in watchedDownloadIds || (item.type to item.id) in watchedDownloadKeys,
                    onClick = { onClick(item) },
                    onToggleNotification = { onToggleNotification(item) },
                    onShare = { onShare(item) },
                    onToggleAirLock = { onToggleAirLock(item) },
                    onMenu = { onMenu(item) },
                )
            } else {
                DownloadRow(
                    item = item,
                    tab = tab,
                    density = density,
                    watched = item.id in watchedDownloadIds || (item.type to item.id) in watchedDownloadKeys,
                    onClick = { onClick(item) },
                    onToggleNotification = { onToggleNotification(item) },
                    onShare = { onShare(item) },
                    onFiles = { onFiles(item) },
                    onToggleAirLock = { onToggleAirLock(item) },
                    onMenu = { onMenu(item) },
                )
            }
            if (density != DownloadDensity.DETAILED) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .42f))
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun WideColumnHeadings(tab: DownloadTab) {
    Row(
        modifier = Modifier.fillMaxWidth().height(34.dp).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(38.dp))
        Text("NAME", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("STATUS", Modifier.width(126.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("SIZE", Modifier.width(92.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (tab == DownloadTab.ACTIVE) "SPEED" else "RETENTION", Modifier.width(106.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (tab == DownloadTab.ACTIVE) "ETA" else "FILES", Modifier.width(76.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(if (tab == DownloadTab.ACTIVE) 88.dp else 132.dp))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun WideDownloadRow(
    item: DownloadItem,
    tab: DownloadTab,
    density: DownloadDensity,
    watched: Boolean,
    onClick: () -> Unit,
    onToggleNotification: () -> Unit,
    onShare: () -> Unit,
    onToggleAirLock: () -> Unit,
    onMenu: () -> Unit,
) {
    val active = tab == DownloadTab.ACTIVE
    val rowHeight = when (density) {
        DownloadDensity.COMPACT -> if (active) 70.dp else 60.dp
        DownloadDensity.COZY -> if (active) 84.dp else 74.dp
        DownloadDensity.DETAILED -> if (active) 102.dp else 88.dp
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = rowHeight).clickable(onClick = onClick).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeMarker(item.type, item.isProblem)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                item.name,
                style = if (density == DownloadDensity.DETAILED) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (density == DownloadDensity.DETAILED) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (active) {
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DownloadProgressIndicator(
                        item = item,
                        modifier = Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(50)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(percentLabel(item), style = MaterialTheme.typography.labelSmall)
                }
            } else if (density == DownloadDensity.DETAILED && item.tags.isNotEmpty()) {
                Text(item.tags.joinToString(", "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Text(item.friendlyState, Modifier.width(126.dp).padding(start = 12.dp), style = MaterialTheme.typography.labelMedium, color = if (item.isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(formatBytes(item.totalSize) ?: "–", Modifier.width(92.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (active) formatRate(item.downloadSpeed) ?: "–" else if (item.airLocked) "AirLocked" else formatExpiry(item.expiresAt) ?: "–",
            Modifier.width(106.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            if (active) formatEta(item.etaSeconds) ?: "–" else item.fileCount?.toString() ?: "–",
            Modifier.width(76.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (active) {
            IconButton(onClick = onToggleNotification, modifier = Modifier.size(44.dp)) {
                Icon(if (watched) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsNone, if (watched) "Stop completion notification" else "Notify when complete", Modifier.size(20.dp))
            }
        } else {
            IconButton(onClick = onShare, modifier = Modifier.size(44.dp)) { Icon(Icons.Outlined.Share, "Share", Modifier.size(20.dp)) }
            if (tab == DownloadTab.AIRLOCK) {
                IconButton(onClick = onToggleAirLock, modifier = Modifier.size(44.dp)) { Icon(Icons.Outlined.LockOpen, "Remove from AirLock", Modifier.size(20.dp)) }
            }
        }
        IconButton(onClick = onMenu, modifier = Modifier.size(44.dp)) { Icon(Icons.Outlined.MoreVert, "More actions", Modifier.size(20.dp)) }
    }
}

@Composable
private fun DownloadRow(
    item: DownloadItem,
    tab: DownloadTab,
    density: DownloadDensity,
    watched: Boolean,
    onClick: () -> Unit,
    onToggleNotification: () -> Unit,
    onShare: () -> Unit,
    onFiles: () -> Unit,
    onToggleAirLock: () -> Unit,
    onMenu: () -> Unit,
) {
    when (density) {
        DownloadDensity.COMPACT -> CompactDownloadRow(
            item, tab, watched, onClick, onToggleNotification, onShare, onToggleAirLock, onMenu,
        )
        DownloadDensity.COZY -> CozyDownloadRow(
            item, tab, watched, onClick, onToggleNotification, onShare, onFiles, onToggleAirLock, onMenu,
        )
        DownloadDensity.DETAILED -> DetailedDownloadRow(
            item, tab, watched, onClick, onToggleNotification, onShare, onFiles, onToggleAirLock, onMenu,
        )
    }
}

@Composable
private fun CompactDownloadRow(
    item: DownloadItem,
    tab: DownloadTab,
    watched: Boolean,
    onClick: () -> Unit,
    onToggleNotification: () -> Unit,
    onShare: () -> Unit,
    onToggleAirLock: () -> Unit,
    onMenu: () -> Unit,
) {
    val active = tab == DownloadTab.ACTIVE
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = if (active) 90.dp else 68.dp)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeMarker(item.type, item.isProblem)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    compactDisplayName(item.name),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (active) {
                    Text(percentLabel(item), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
            if (active) {
                Spacer(Modifier.height(5.dp))
                DownloadProgressIndicator(
                    item = item,
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)),
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    activeMetadata(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(Modifier.height(3.dp))
                Text(
                    finishedMetadata(item, tab),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (active) {
            IconButton(onClick = onToggleNotification, modifier = Modifier.size(44.dp)) {
                Icon(
                    if (watched) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsNone,
                    contentDescription = if (watched) "Stop completion notification" else "Notify when complete",
                    modifier = Modifier.size(20.dp),
                    tint = if (watched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            IconButton(onClick = onShare, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Share, contentDescription = "Share file", modifier = Modifier.size(20.dp))
            }
            if (tab == DownloadTab.AIRLOCK) {
                IconButton(onClick = onToggleAirLock, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.LockOpen, contentDescription = "Remove from AirLock", modifier = Modifier.size(20.dp))
                }
            }
        }
        IconButton(onClick = onMenu, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "More actions", modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun CozyDownloadRow(
    item: DownloadItem,
    tab: DownloadTab,
    watched: Boolean,
    onClick: () -> Unit,
    onToggleNotification: () -> Unit,
    onShare: () -> Unit,
    onFiles: () -> Unit,
    onToggleAirLock: () -> Unit,
    onMenu: () -> Unit,
) {
    val active = tab == DownloadTab.ACTIVE
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = if (active) 116.dp else 94.dp).clickable(onClick = onClick).padding(12.dp, 8.dp, 3.dp, 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypeMarker(item.type, item.isProblem, 31)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(
                listOfNotNull(item.type.label(), formatBytes(item.totalSize), item.friendlyState).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = if (item.isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (active) {
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DownloadProgressIndicator(
                        item = item,
                        modifier = Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(50)),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(percentLabel(item), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Text(activeMetadata(item), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            } else {
                Spacer(Modifier.height(4.dp))
                Text(finishedMetadata(item, tab), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Column {
            if (active) {
                IconButton(onClick = onToggleNotification, modifier = Modifier.size(42.dp)) {
                    Icon(if (watched) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsNone, if (watched) "Stop completion notification" else "Notify when complete", modifier = Modifier.size(21.dp))
                }
            } else {
                Row {
                    IconButton(onClick = onFiles, modifier = Modifier.size(42.dp)) { Icon(Icons.Outlined.FolderOpen, "Files", Modifier.size(20.dp)) }
                    IconButton(onClick = onShare, modifier = Modifier.size(42.dp)) { Icon(Icons.Outlined.Share, "Share", Modifier.size(20.dp)) }
                }
            }
            Row {
                if (!active) {
                    IconButton(onClick = onToggleAirLock, modifier = Modifier.size(42.dp)) {
                        Icon(if (item.airLocked) Icons.Outlined.LockOpen else Icons.Outlined.Lock, if (item.airLocked) "Remove from AirLock" else "Add to AirLock", Modifier.size(20.dp))
                    }
                }
                IconButton(onClick = onMenu, modifier = Modifier.size(42.dp)) { Icon(Icons.Outlined.MoreVert, "More actions", Modifier.size(20.dp)) }
            }
        }
    }
}

@Composable
private fun DetailedDownloadRow(
    item: DownloadItem,
    tab: DownloadTab,
    watched: Boolean,
    onClick: () -> Unit,
    onToggleNotification: () -> Unit,
    onShare: () -> Unit,
    onFiles: () -> Unit,
    onToggleAirLock: () -> Unit,
    onMenu: () -> Unit,
) {
    val active = tab == DownloadTab.ACTIVE
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                TypeMarker(item.type, item.isProblem, 34)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(item.type.label(), formatBytes(item.totalSize), item.friendlyState).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (item.isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onMenu, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.MoreVert, "More actions") }
            }
            if (active) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DownloadProgressIndicator(
                        item = item,
                        modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(50)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(percentLabel(item), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Text(detailedActiveMetadata(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Spacer(Modifier.height(10.dp))
                Text(detailedFinishedMetadata(item, tab), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    AssistChip(
                        onClick = onToggleNotification,
                        label = { Text(if (watched) "Watching" else "Notify") },
                        leadingIcon = { Icon(if (watched) Icons.Outlined.NotificationsActive else Icons.Outlined.NotificationsNone, null, Modifier.size(17.dp)) },
                        colors = if (watched) AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else AssistChipDefaults.assistChipColors(),
                    )
                } else {
                    SmallAction(Icons.Outlined.FolderOpen, "Files", onFiles)
                    SmallAction(Icons.Outlined.Share, "Share", onShare)
                    SmallAction(if (item.airLocked) Icons.Outlined.LockOpen else Icons.Outlined.Lock, if (item.airLocked) "Unprotect" else "AirLock", onToggleAirLock)
                }
            }
        }
    }
}

@Composable
private fun SmallAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(17.dp)) },
    )
}

@Composable
private fun TypeMarker(type: DownloadType, problem: Boolean, size: Int = 27) {
    val color = if (problem) MaterialTheme.colorScheme.error else when (type) {
        DownloadType.TORRENT -> MaterialTheme.colorScheme.primary
        DownloadType.WEB -> MaterialTheme.colorScheme.secondary
    }
    Box(
        modifier = Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = .14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (type == DownloadType.TORRENT) Icons.Outlined.Storage else Icons.Outlined.Public,
            contentDescription = type.label(),
            modifier = Modifier.size((size - 10).dp),
            tint = color,
        )
    }
}

@Composable
private fun QueueList(
    queue: List<QueuedDownload>,
    density: DownloadDensity,
    wide: Boolean,
    listState: LazyListState,
    searchOrFilterActive: Boolean,
    onStart: (QueuedDownload) -> Unit,
    onDelete: (QueuedDownload) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<QueuedDownload?>(null) }
    var detailsItem by remember { mutableStateOf<QueuedDownload?>(null) }
    if (queue.isEmpty()) {
        EmptyDownloadsState(DownloadTab.QUEUE, searchOrFilterActive)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
        if (wide) {
            item(key = "queue-heading", contentType = "column-headings") {
                Row(Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(38.dp))
                    Text("NAME", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("QUEUED", Modifier.width(190.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(88.dp))
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        items(queue, key = { "queue-${it.type}-${it.id}" }, contentType = { "queue-${density.name}" }) { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(
                        min = when (density) {
                            DownloadDensity.COMPACT -> 70.dp
                            DownloadDensity.COZY -> 88.dp
                            DownloadDensity.DETAILED -> 112.dp
                        },
                    )
                    .clickable { detailsItem = item }
                    .padding(start = 12.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TypeMarker(item.type, false)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (density == DownloadDensity.COMPACT) compactDisplayName(item.name) else item.name,
                        style = if (density == DownloadDensity.DETAILED) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = if (density == DownloadDensity.DETAILED) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            item.type.label(),
                            if (wide) null else formatInstant(item.queuedAt)?.let { "Queued $it" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    if (density == DownloadDensity.DETAILED && !item.source.isNullOrBlank()) {
                        Text(
                            item.source,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (wide) {
                    Text(
                        formatInstant(item.queuedAt) ?: "–",
                        modifier = Modifier.width(190.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                IconButton(onClick = { onStart(item) }, modifier = Modifier.size(44.dp)) { Icon(Icons.Outlined.PlayArrow, "Start now") }
                IconButton(onClick = { detailsItem = item }, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.MoreVert, "Queued download details and actions")
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .42f))
        }
    }
    detailsItem?.let { item ->
        AlertDialog(
            onDismissRequest = { detailsItem = null },
            icon = { Icon(if (item.type == DownloadType.TORRENT) Icons.Outlined.Storage else Icons.Outlined.Public, contentDescription = null) },
            title = { Text(item.name) },
            text = {
                SelectionContainer {
                    Column(
                        modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        QueueDetail("Type", item.type.label())
                        QueueDetail("Queue ID", item.id)
                        QueueDetail("Queued", formatInstant(item.queuedAt) ?: "Unknown")
                        QueueDetail("Source", item.source?.takeIf(String::isNotBlank) ?: "Not provided")
                    }
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            detailsItem = null
                            pendingDelete = item
                        },
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { detailsItem = null }) { Text("Close") }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        detailsItem = null
                        onStart(item)
                    },
                ) { Text("Start now") }
            },
        )
    }
    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete queued download?") },
            text = { Text("“${item.name}” will be removed from the queue.") },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(item)
                    },
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
        )
    }
}

@Composable
private fun QueueDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EmptyDownloadsState(tab: DownloadTab, filtered: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (filtered) Icons.Outlined.FilterList else tab.emptyIcon(),
            contentDescription = null,
            modifier = Modifier.size(43.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Text(if (filtered) "Nothing matches" else tab.emptyTitle(), style = MaterialTheme.typography.titleMedium)
        Text(
            if (filtered) "Try clearing search or filters." else tab.emptyMessage(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(10.dp))
        Text("Couldn’t load downloads", style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun LoadingRows(density: DownloadDensity) {
    val height = when (density) {
        DownloadDensity.COMPACT -> 76.dp
        DownloadDensity.COZY -> 104.dp
        DownloadDensity.DETAILED -> 148.dp
    }
    LazyColumn(Modifier.fillMaxSize().semantics { contentDescription = "Loading downloads" }) {
        items(8) { index ->
            Row(Modifier.fillMaxWidth().height(height).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(if (index % 3 == 0) .58f else .78f).height(11.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant))
                    Spacer(Modifier.height(9.dp))
                    Box(Modifier.fillMaxWidth(.42f).height(8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .7f)))
                }
            }
        }
    }
}

private fun DownloadSort.queueComparator(): Comparator<QueuedDownload> = when (this) {
    DownloadSort.NEWEST, DownloadSort.LARGEST -> compareByDescending<QueuedDownload> { it.queuedAt ?: Instant.MIN }.thenBy { it.name.lowercase() }
    DownloadSort.OLDEST -> compareBy<QueuedDownload> { it.queuedAt ?: Instant.MAX }.thenBy { it.name.lowercase() }
    DownloadSort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
}

@Composable
private fun DownloadProgressIndicator(item: DownloadItem, modifier: Modifier = Modifier) {
    val color = stateColor(item)
    val fraction = displayProgressFraction(item)
    if (fraction == null) {
        LinearProgressIndicator(
            modifier = modifier,
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    } else {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = modifier,
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
private fun stateColor(item: DownloadItem): Color = when {
    item.isProblem -> MaterialTheme.colorScheme.error
    item.isReady -> TorBoxColors.Green
    else -> MaterialTheme.colorScheme.primary
}

private fun detailedActiveMetadata(item: DownloadItem): String = buildList {
    formatBytes(item.totalSize)?.let(::add)
    formatRate(item.downloadSpeed)?.let { add("↓ $it") }
    formatRate(item.uploadSpeed)?.let { add("↑ $it") }
    formatEta(item.etaSeconds)?.let { add("ETA $it") }
    if (item.seeds != null) add("${item.seeds} seeds")
    if (item.peers != null) add("${item.peers} peers")
    item.ratio?.let { add("Ratio %.2f".format(it)) }
}.joinToString(" · ")

private fun finishedMetadata(item: DownloadItem, tab: DownloadTab): String = buildList {
    formatBytes(item.totalSize)?.let(::add)
    item.fileCount?.let { add(if (it == 1) "1 file" else "$it files") }
    if (item.cached) add("Cached")
    if (tab == DownloadTab.AIRLOCK || item.airLocked) add("AirLocked") else formatExpiry(item.expiresAt)?.let(::add)
}.joinToString(" · ")

private fun detailedFinishedMetadata(item: DownloadItem, tab: DownloadTab): String = buildList {
    add(item.friendlyState)
    formatBytes(item.totalSize)?.let(::add)
    item.fileCount?.let { add(if (it == 1) "1 file" else "$it files") }
    if (item.cached) add("Cached")
    if (tab == DownloadTab.AIRLOCK || item.airLocked) add("Protected by AirLock") else formatExpiry(item.expiresAt)?.let(::add)
    if (item.tags.isNotEmpty()) add(item.tags.joinToString(", "))
}.joinToString(" · ")

private fun DownloadType.label(): String = if (this == DownloadType.TORRENT) "Torrent" else "Web"

private fun DownloadTab.label(): String = when (this) {
    DownloadTab.ACTIVE -> "ACTIVE"
    DownloadTab.FINISHED -> "FINISHED"
    DownloadTab.QUEUE -> "QUEUE"
    DownloadTab.AIRLOCK -> "AIRLOCK"
}

private fun DownloadSort.label(): String = when (this) {
    DownloadSort.NEWEST -> "Newest first"
    DownloadSort.OLDEST -> "Oldest first"
    DownloadSort.LARGEST -> "Largest first"
    DownloadSort.NAME -> "Name"
}

internal fun sortOptionsFor(tab: DownloadTab): List<DownloadSort> =
    DownloadSort.entries.filter { tab != DownloadTab.QUEUE || it != DownloadSort.LARGEST }

internal fun DownloadSort.effectiveFor(tab: DownloadTab): DownloadSort =
    if (tab == DownloadTab.QUEUE && this == DownloadSort.LARGEST) DownloadSort.NEWEST else this

internal fun DownloadFilter.isActiveFor(tab: DownloadTab): Boolean =
    if (tab == DownloadTab.QUEUE) type != null else isActive

internal fun DownloadFilter.clearedFor(tab: DownloadTab): DownloadFilter =
    if (tab == DownloadTab.QUEUE) copy(type = null) else DownloadFilter()

private fun DownloadTab.emptyTitle(): String = when (this) {
    DownloadTab.ACTIVE -> "Nothing active"
    DownloadTab.FINISHED -> "No finished downloads"
    DownloadTab.QUEUE -> "Queue is empty"
    DownloadTab.AIRLOCK -> "Nothing in AirLock"
}

private fun DownloadTab.emptyMessage(): String = when (this) {
    DownloadTab.ACTIVE -> "New and unfinished downloads appear here."
    DownloadTab.FINISHED -> "Ready downloads appear here automatically."
    DownloadTab.QUEUE -> "Queued torrents and web downloads appear here."
    DownloadTab.AIRLOCK -> "Protect a finished item to keep it longer."
}

private fun DownloadTab.emptyIcon(): ImageVector = when (this) {
    DownloadTab.ACTIVE -> Icons.Outlined.CloudDownload
    DownloadTab.FINISHED -> Icons.Outlined.CheckCircle
    DownloadTab.QUEUE -> Icons.AutoMirrored.Outlined.ViewList
    DownloadTab.AIRLOCK -> Icons.Outlined.Lock
}

private fun DownloadDensity.label(): String = when (this) {
    DownloadDensity.COMPACT -> "Compact"
    DownloadDensity.COZY -> "Cozy"
    DownloadDensity.DETAILED -> "Detailed"
}

private fun DownloadDensity.description(): String = when (this) {
    DownloadDensity.COMPACT -> "See the most items"
    DownloadDensity.COZY -> "More breathing room"
    DownloadDensity.DETAILED -> "More information and actions"
}

private fun DownloadDensity.icon(): ImageVector = when (this) {
    DownloadDensity.COMPACT -> Icons.Outlined.TableRows
    DownloadDensity.COZY -> Icons.AutoMirrored.Outlined.ViewList
    DownloadDensity.DETAILED -> Icons.Outlined.ViewAgenda
}
