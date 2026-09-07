package app.jabs.torboxdrop.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(onAddCached: (String) -> Unit, onOpenSettings: () -> Unit) {
    val model: DiscoveryViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.activate()
                Lifecycle.Event.ON_STOP -> model.deactivate()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.activate()
        onDispose { lifecycle.removeObserver(observer); model.deactivate() }
    }
    var query by rememberSaveable { mutableStateOf(state.filter.query) }
    var season by rememberSaveable { mutableStateOf(state.filter.season.toString()) }
    var episode by rememberSaveable { mutableStateOf(state.filter.episode.toString()) }
    LaunchedEffect(state.filter.season, state.filter.episode) {
        season = state.filter.season.toString()
        episode = state.filter.episode.toString()
    }
    LaunchedEffect(state.filter.query) { query = state.filter.query }
    var density by rememberSaveable { mutableStateOf("Compact") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var quality by rememberSaveable { mutableStateOf("All quality") }
    val catalog = state.catalogs.find { it.kind == state.filter.kind && it.id == state.filter.catalogId }
    val validEpisode = (season.toIntOrNull() in 0..999) && (episode.toIntOrNull() in 1..9999)
    fun search() {
        if (state.filter.kind == MediaKind.TV && !validEpisode) return
        selectedId = null
        model.change(state.filter.copy(query = query.trim().take(160),
            season = season.toIntOrNull() ?: 1, episode = episode.toIntOrNull() ?: 1))
    }
    val rows = state.rows.mapNotNull { row ->
        val releases = row.releases.filter { quality == "All quality" || it.quality == quality }
        row.copy(releases = releases).takeIf { releases.isNotEmpty() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Discover cached", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = model::refresh, enabled = !state.busy && state.adding == null) { Text("Refresh") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MediaKind.entries.forEach { kind ->
                FilterChip(selected = state.filter.kind == kind, label = { Text(kind.label) }, onClick = {
                    selectedId = null; query = ""
                    model.change(DiscoveryFilter(kind = kind))
                }, enabled = state.adding == null)
            }
            Choice(density, listOf("Compact", "Cozy", "Detailed")) { density = it }
            Choice(quality, listOf("All quality", "4K", "1080p", "720p", "SD", "Unknown quality")) { quality = it }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Choice(catalog?.name ?: "Catalog", state.catalogs.filter { it.kind == state.filter.kind }.map { it.name }, enabled = state.adding == null) { name ->
                state.catalogs.firstOrNull { it.kind == state.filter.kind && it.name == name }?.let {
                    query = ""; selectedId = null
                    model.change(state.filter.copy(catalogId = it.id, query = "", genre = ""))
                }
            }
            if (!catalog?.genres.isNullOrEmpty()) {
                Choice(state.filter.genre.ifBlank { "All genres / years" }, listOf("All genres / years") + catalog!!.genres, enabled = state.adding == null) {
                    model.change(state.filter.copy(genre = it.takeUnless { it == "All genres / years" }.orEmpty()))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it.take(160) }, Modifier.weight(1f), singleLine = true,
                label = { Text("Find a title") }, enabled = catalog?.searchable != false && state.adding == null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }))
            TextButton(onClick = { search() }, enabled = state.adding == null && (state.filter.kind == MediaKind.MOVIE || validEpisode)) { Text("Search") }
        }
        if (state.filter.kind == MediaKind.TV) {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(season, { season = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), label = { Text("Season") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = season.toIntOrNull() !in 0..999)
                OutlinedTextField(episode, { episode = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), label = { Text("Episode") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = episode.toIntOrNull() !in 1..9999)
                TextButton(onClick = { search() }, enabled = validEpisode && state.adding == null) { Text("Check") }
            }
            Text("Showing ${state.filter.scope} only, not complete-series availability.", style = MaterialTheme.typography.labelSmall)
        }
        Text("${rows.size} cached titles • ${state.checked} titles checked. Not TorBox's full cache.",
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 6.dp))
        if (state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = model::cancel) { Text("Stop checking") }
        }
        state.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = model::loadMore, enabled = !state.busy && state.adding == null) { Text("Retry") }
                TextButton(onClick = onOpenSettings, enabled = state.adding == null) { Text("Settings") }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (rows.isEmpty() && !state.busy && state.error == null) item {
                Text(if (state.rows.isNotEmpty()) "No results match this quality filter." else
                    "No cached releases found among the titles checked. Try another title, genre, or the next batch.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp))
            }
            items(rows, key = { it.title.id }) { row ->
                Column(Modifier.fillMaxWidth().clickable { if (state.busy) model.cancel(); selectedId = row.title.id }
                    .padding(vertical = if (density == "Compact") 7.dp else 12.dp)) {
                    Text(row.title.title, style = MaterialTheme.typography.titleSmall,
                        maxLines = if (density == "Compact") 1 else 2, overflow = TextOverflow.Ellipsis)
                    Text(listOf(row.title.year, "${row.releases.size} releases", row.releases.map { it.quality }.distinct().joinToString(" / "))
                        .filter { it.isNotBlank() }.joinToString(" • "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (density == "Detailed") {
                        Text(row.title.genres.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        Text("${row.scope} • ${row.releases.mapNotNull { it.size }.minOrNull()?.let(DiscoveryRules::sizeLabel) ?: "Size unknown"} and up",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
            }
            item {
                if (state.hasMore) OutlinedButton(onClick = model::loadMore, enabled = !state.busy && state.adding == null,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Check next 12 titles") }
                else Text("End of this catalog's results.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                Text("Cinemeta supplies titles; Torrentio supplies torrent candidates. Only hashes reported cached by TorBox appear here. Up to 200 candidates per title are checked. These providers see your IP and title requests, never your TorBox key.",
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 10.dp))
            }
        }
    }
    val selected = state.rows.firstOrNull { it.title.id == selectedId }
    if (selected != null) {
        var sort by rememberSaveable(selected.title.id) { mutableStateOf("Smallest") }
        ModalBottomSheet(onDismissRequest = { if (state.adding != null) model.cancel(); selectedId = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).fillMaxHeight(0.85f)) {
                Text(selected.title.title, style = MaterialTheme.typography.titleLarge)
                Text("${selected.scope} • Sizes are whole torrents, including season packs.", style = MaterialTheme.typography.bodySmall)
                Text("Quality and codec are inferred from release labels. Cached-only is enforced when adding.", style = MaterialTheme.typography.labelSmall)
                Choice(sort, listOf("Smallest", "Largest", "Quality")) { sort = it }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                val releases = selected.releases.filter { quality == "All quality" || it.quality == quality }.let { list ->
                    when (sort) {
                        "Largest" -> list.sortedByDescending { it.size ?: -1L }
                        "Quality" -> list.sortedBy { listOf("4K", "1080p", "720p", "SD", "Unknown quality").indexOf(it.quality) }
                        else -> list.sortedBy { it.size ?: Long.MAX_VALUE }
                    }
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(releases, key = { it.hash }) { release ->
                        Column(Modifier.padding(vertical = 8.dp)) {
                            Text(release.name, style = MaterialTheme.typography.bodyMedium)
                            Text(listOfNotNull(release.quality, DiscoveryRules.sizeLabel(release.size), release.codec).joinToString(" • "), style = MaterialTheme.typography.labelLarge)
                            Text("TorBox reported cached at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(release.checkedAt))}", style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = { model.add(release, onAddCached) }, enabled = !state.busy && state.adding == null) {
                                Text(if (state.adding == release.hash) "Rechecking…" else "Add cached torrent")
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun Choice(label: String, options: List<String>, enabled: Boolean = true, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled && options.isNotEmpty()) { Text("$label ▾") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelect(option) }) }
        }
    }
}
