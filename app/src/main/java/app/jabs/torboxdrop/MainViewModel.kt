package app.jabs.torboxdrop

import android.app.Application
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jabs.torboxdrop.browser.BrowserNavigationPolicy
import app.jabs.torboxdrop.data.AppPreferences
import app.jabs.torboxdrop.data.TorBoxBadTokenException
import app.jabs.torboxdrop.data.TorBoxException
import app.jabs.torboxdrop.data.TorBoxOfflineException
import app.jabs.torboxdrop.data.TorBoxRateLimitException
import app.jabs.torboxdrop.model.AccountInfo
import app.jabs.torboxdrop.model.AddOptions
import app.jabs.torboxdrop.model.AddResult
import app.jabs.torboxdrop.model.Bookmark
import app.jabs.torboxdrop.model.DownloadDensity
import app.jabs.torboxdrop.model.DownloadFile
import app.jabs.torboxdrop.model.DownloadFilter
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadSort
import app.jabs.torboxdrop.model.DownloadTab
import app.jabs.torboxdrop.model.DownloadType
import app.jabs.torboxdrop.model.DownloadsUiState
import app.jabs.torboxdrop.model.IncomingAdd
import app.jabs.torboxdrop.model.QueuedDownload
import app.jabs.torboxdrop.model.RecentSend
import app.jabs.torboxdrop.notifications.CompletionMonitorRunner
import app.jabs.torboxdrop.notifications.CompletionMonitorScheduler
import app.jabs.torboxdrop.notifications.CompletionMonitorService
import app.jabs.torboxdrop.notifications.CompletionMonitorServiceLocator
import app.jabs.torboxdrop.notifications.CompletionNotifications
import app.jabs.torboxdrop.notifications.CompletionNotificationChannels
import app.jabs.torboxdrop.notifications.NotificationCapabilities
import app.jabs.torboxdrop.notifications.StartResult
import app.jabs.torboxdrop.ui.screens.SettingsUiState
import app.jabs.torboxdrop.ui.screens.TokenValidationStatus
import app.jabs.torboxdrop.util.InputParser
import app.jabs.torboxdrop.util.RecentAdditions
import app.jabs.torboxdrop.util.inferredMimeType
import app.jabs.torboxdrop.util.TorrentPayloadReader
import app.jabs.torboxdrop.util.TorrentPayload
import app.jabs.torboxdrop.util.UrlSafety
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.supervisorScope

enum class AppDestination { DOWNLOADS, ADD, BROWSER, SETTINGS }

data class FileSheetState(
    val download: DownloadItem,
    val files: List<DownloadFile> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

data class MainUiState(
    val destination: AppDestination = AppDestination.DOWNLOADS,
    val downloads: DownloadsUiState = DownloadsUiState(),
    val addCandidate: String = "",
    val pendingTorrentName: String? = null,
    val addOptions: AddOptions = AddOptions(),
    val addSubmitting: Boolean = false,
    val addResult: AddResult? = null,
    val addError: String? = null,
    val recentSends: List<RecentSend> = emptyList(),
    val settings: SettingsUiState = SettingsUiState(appVersion = BuildConfig.VERSION_NAME),
    val bookmarks: List<Bookmark> = emptyList(),
    val selectedDownload: DownloadItem? = null,
    val fileSheet: FileSheetState? = null,
    val actionInProgress: Boolean = false,
    val watchedKeys: Set<String> = emptySet(),
    val loadedFiles: Map<String, List<DownloadFile>> = emptyMap(),
    val cachedFileMatchKeys: Set<String> = emptySet(),
    val showBookmarks: Boolean = false,
    val browserGeneration: Int = 0,
    val browserRequestedUrl: String? = null,
    val pendingBrowserMagnet: String? = null,
)

sealed interface PendingNotificationAction {
    data class Watch(val item: DownloadItem) : PendingNotificationAction
    data class AddDefaults(val options: AddOptions) : PendingNotificationAction
    data class Settings(val state: SettingsUiState) : PendingNotificationAction
}

sealed interface MainEvent {
    data class Message(val text: String) : MainEvent
    data class ShareText(
        val text: String,
        val title: String,
        val requiresTorBoxUrlSafety: Boolean = false,
    ) : MainEvent
    data class CopyText(
        val text: String,
        val label: String,
        val requiresTorBoxUrlSafety: Boolean = false,
    ) : MainEvent
    data class DownloadToDevice(
        val url: String,
        val fileName: String,
        val mimeType: String?,
        val requestHeaders: Map<String, String> = emptyMap(),
        val requiresTorBoxUrlSafety: Boolean = false,
    ) : MainEvent
    data class OpenUri(
        val url: String,
        val mimeType: String? = null,
        val chooserTitle: String? = null,
        val requiresTorBoxUrlSafety: Boolean = false,
    ) : MainEvent
    data object ClearBrowserData : MainEvent
    data object OpenNotificationSettings : MainEvent
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as TorBoxDropApplication
    private val container = app.container
    private val preferences = container.preferences
    private val repository = container.repository
    private val localStore = container.localStore

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val eventChannel = Channel<MainEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private var foregroundPollingJob: Job? = null
    private val refreshMutex = Mutex()
    private var pollingDelayMillis = ACTIVE_REFRESH_MILLIS
    private var accountWorkJob = SupervisorJob(viewModelScope.coroutineContext[Job])
    private var accountScope = CoroutineScope(viewModelScope.coroutineContext + accountWorkJob)
    private val tokenReplaceMutex = Mutex()
    private var isForeground = false
    private val incomingFingerprints = LinkedHashSet<String>()
    private var pendingTorrentPayload: TorrentPayload? = null
    private var pendingTorrentSource: String = "file"
    private var fileLoadJob: Job? = null
    private var fileLoadGeneration = 0L
    private var torrentReadJob: Job? = null
    private var torrentReadGeneration = 0L
    private var fileSearchJob: Job? = null
    private var fileSearchGeneration = 0L
    private var pendingNotificationPermissionAction: PendingNotificationAction? = null
    private var tokenRejected = false
    // Deliberately transient: retain history across UI recreation without persisting browsing data.
    private var browserWebViewSnapshot: Pair<Int, Bundle>? = null
    private val recentlyAddedDownloads = LinkedHashMap<String, DownloadItem>()
    private val recentlyQueuedDownloads = LinkedHashMap<String, QueuedDownload>()

    init {
        _uiState.update { state ->
            val defaults = defaultAddOptions()
            state.copy(
                addOptions = defaults,
                downloads = state.downloads.copy(
                    density = preferences.density,
                    selectedTab = preferences.downloadsTab,
                    sort = preferences.sort,
                ),
                settings = settingsState(defaults = defaults),
            )
        }
        launchAccountWork { loadInitialState() }
    }

    fun navigate(destination: AppDestination) {
        _uiState.update { it.copy(destination = destination, selectedDownload = null) }
        updatePolling()
    }

    fun setForeground(value: Boolean) {
        isForeground = value
        updatePolling()
    }

    fun consumeIncomingFingerprint(fingerprint: String): Boolean {
        if (!incomingFingerprints.add(fingerprint)) return false
        while (incomingFingerprints.size > 32) {
            incomingFingerprints.remove(incomingFingerprints.first())
        }
        return true
    }

    fun receiveText(value: String, source: String, remainInBrowser: Boolean = false) {
        val parsed = InputParser.firstLink(value)
        if (parsed == null) {
            emitMessage("TorBox Drop could not find a magnet or HTTP link in that text.")
            return
        }
        val configuredToken = container.tokenStore.read()
        if (!configuredToken.isNullOrBlank() && UrlSafety.containsApiToken(parsed.value, configuredToken)) {
            emitMessage("TorBox Drop refused a link containing your API token.")
            return
        }
        if (!remainInBrowser) {
            dismissDownloadSurfacesForIncoming()
            torrentReadGeneration++
            torrentReadJob?.cancel()
            torrentReadJob = null
            pendingTorrentPayload = null
        }
        val shouldAutoSend = when (source) {
            // Browser magnets may auto-send only through receiveTrustedBrowserMagnet().
            "browser-magnet" -> false
            "browser", "browser-download" -> true
            "clipboard" -> parsed.kind == InputParser.Kind.MAGNET && preferences.autoSendClipboardMagnets
            else -> !preferences.confirmBeforeSending
        }
        if (shouldAutoSend && container.tokenStore.hasToken()) {
            submitText(parsed, defaultAddOptions(), source, remainInBrowser)
        } else {
            _uiState.update {
                it.copy(
                    destination = if (remainInBrowser) it.destination else AppDestination.ADD,
                    addCandidate = parsed.value,
                    pendingTorrentName = if (remainInBrowser) it.pendingTorrentName else null,
                    addResult = null,
                    addError = if (!container.tokenStore.hasToken()) {
                        "Connect your TorBox account in Settings before sending."
                    } else null,
                )
            }
            updatePolling()
        }
    }

    /** Called only after the browser controller has verified a native user gesture. */
    fun receiveTrustedBrowserMagnet(value: String) {
        val parsed = InputParser.classify(value)
        if (parsed?.kind != InputParser.Kind.MAGNET) {
            emitMessage("The browser blocked an invalid magnet link.")
            return
        }
        val configuredToken = container.tokenStore.read()
        if (!configuredToken.isNullOrBlank() && UrlSafety.containsApiToken(parsed.value, configuredToken)) {
            emitMessage("TorBox Drop refused a link containing your API token.")
            return
        }
        if (
            BrowserNavigationPolicy.shouldAutoSendBrowserMagnet(
                autoSendBrowserMagnets = preferences.autoSendBrowserMagnets,
                confirmBeforeSending = preferences.confirmBeforeSending,
            ) && container.tokenStore.hasToken()
        ) {
            submitText(parsed, defaultAddOptions(), "browser-magnet", remainInBrowser = true)
        } else {
            _uiState.update { it.copy(pendingBrowserMagnet = parsed.value) }
        }
    }

    fun confirmBrowserMagnet() {
        val value = _uiState.value.pendingBrowserMagnet ?: return
        _uiState.update { it.copy(pendingBrowserMagnet = null) }
        val parsed = InputParser.classify(value)
        if (parsed?.kind == InputParser.Kind.MAGNET) {
            submitText(parsed, defaultAddOptions(), "browser-magnet", remainInBrowser = true)
        } else {
            emitMessage("The browser blocked an invalid magnet link.")
        }
    }

    fun dismissBrowserMagnet() = _uiState.update { it.copy(pendingBrowserMagnet = null) }

    fun browserWebViewState(generation: Int): Bundle? = browserWebViewSnapshot
        ?.takeIf { (savedGeneration, _) -> savedGeneration == generation }
        ?.second
        ?.let(::Bundle)

    fun saveBrowserWebViewState(generation: Int, state: Bundle) {
        if (generation != _uiState.value.browserGeneration) return
        browserWebViewSnapshot?.second?.clear()
        browserWebViewSnapshot = generation to Bundle(state)
    }

    fun receiveTorrentUri(
        uri: Uri,
        source: String,
        options: AddOptions = _uiState.value.addOptions,
    ) {
        dismissDownloadSurfacesForIncoming()
        pendingTorrentPayload = null
        torrentReadJob?.cancel()
        val ingestionGeneration = ++torrentReadGeneration
        _uiState.update {
            it.copy(
                destination = AppDestination.ADD,
                selectedDownload = null,
                fileSheet = null,
                pendingTorrentName = null,
                addCandidate = "",
                addResult = null,
                addError = null,
                addSubmitting = true,
            )
        }
        updatePolling()
        torrentReadJob = viewModelScope.launch {
            try {
                val payload = TorrentPayloadReader.read(app.contentResolver, uri)
                if (ingestionGeneration != torrentReadGeneration) return@launch
                pendingTorrentPayload = payload
                pendingTorrentSource = source
                _uiState.update {
                    it.copy(
                        pendingTorrentName = payload.fileName,
                        addCandidate = "",
                        addSubmitting = false,
                        addError = if (!container.tokenStore.hasToken()) {
                            "Connect your TorBox account in Settings, then return here to upload this torrent."
                        } else null,
                    )
                }
                val explicitlyChosenHere = source == "file-picker"
                if (
                    container.tokenStore.hasToken() &&
                    !explicitlyChosenHere &&
                    !preferences.confirmBeforeSending
                ) {
                    submitPendingTorrent(options)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (ingestionGeneration == torrentReadGeneration) finishFailedAdd(error)
            }
        }
    }

    fun submitPendingTorrent(options: AddOptions = _uiState.value.addOptions) {
        val payload = pendingTorrentPayload
        if (payload == null) {
            emitMessage("Choose a .torrent file first.")
            return
        }
        if (!container.tokenStore.hasToken()) {
            _uiState.update { it.copy(addError = "Connect your TorBox account in Settings first.") }
            return
        }
        val source = pendingTorrentSource
        _uiState.update { it.copy(addSubmitting = true, addResult = null, addError = null) }
        launchAccountWork {
            try {
                val result = repository.createTorrent(payload.bytes, payload.fileName, options, source)
                pendingTorrentPayload = null
                finishSuccessfulAdd(
                    result = result,
                    type = DownloadType.TORRENT,
                    options = options,
                    fallbackName = payload.fileName,
                    navigateAfterSuccess = true,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                finishFailedAdd(error)
            }
        }
    }

    fun discardPendingTorrent() {
        torrentReadGeneration++
        torrentReadJob?.cancel()
        torrentReadJob = null
        pendingTorrentPayload = null
        _uiState.update {
            it.copy(pendingTorrentName = null, addCandidate = "", addError = null, addResult = null)
        }
    }

    fun onClipboardText(value: String) {
        val parsed = InputParser.firstLink(value) ?: return
        // The Add destination is always explicit: detecting clipboard content may populate the
        // field, but only its Add button is allowed to submit it.
        if (_uiState.value.destination == AppDestination.ADD) {
            if (_uiState.value.addCandidate.isBlank()) {
                _uiState.update { it.copy(addCandidate = parsed.value, addResult = null, addError = null) }
            }
            return
        }
        if (parsed.kind == InputParser.Kind.MAGNET && preferences.autoSendClipboardMagnets) {
            receiveText(parsed.value, "clipboard")
        } else if (_uiState.value.addCandidate.isBlank()) {
            _uiState.update { it.copy(addCandidate = parsed.value) }
        }
    }

    fun setAddCandidate(value: String) {
        if (value.isNotBlank()) {
            torrentReadGeneration++
            torrentReadJob?.cancel()
            torrentReadJob = null
            pendingTorrentPayload = null
        }
        _uiState.update {
            it.copy(
                addCandidate = value,
                pendingTorrentName = if (value.isNotBlank()) null else it.pendingTorrentName,
                addResult = null,
                addError = null,
            )
        }
    }

    fun setAddOptions(options: AddOptions) = _uiState.update { it.copy(addOptions = options) }

    fun submit(incoming: IncomingAdd, options: AddOptions = _uiState.value.addOptions) {
        when (incoming) {
            is IncomingAdd.Text -> {
                val parsed = InputParser.firstLink(incoming.value)
                if (parsed == null) {
                    _uiState.update { it.copy(addError = "Enter a valid magnet or HTTP link.") }
                } else {
                    submitText(parsed, options, incoming.source, remainInBrowser = false)
                }
            }
            is IncomingAdd.TorrentFile -> receiveTorrentUri(Uri.parse(incoming.uri), "file", options)
        }
    }

    fun dismissAddMessage() = _uiState.update { it.copy(addResult = null, addError = null) }

    fun refresh(manual: Boolean = true) {
        launchAccountWork { refreshSnapshot(bypassCache = manual, showSpinner = manual) }
    }

    fun selectTab(tab: DownloadTab) {
        preferences.downloadsTab = tab
        _uiState.update { it.copy(downloads = it.downloads.copy(selectedTab = tab)) }
        updatePolling()
    }

    fun setDensity(density: DownloadDensity) {
        preferences.density = density
        _uiState.update {
            it.copy(
                downloads = it.downloads.copy(density = density),
                settings = it.settings.copy(density = density),
            )
        }
    }

    fun setSearch(query: String) {
        val generation = ++fileSearchGeneration
        fileSearchJob?.cancel()
        _uiState.update {
            it.copy(
                downloads = it.downloads.copy(search = query),
                cachedFileMatchKeys = if (query.isBlank()) emptySet() else it.cachedFileMatchKeys,
            )
        }
        if (query.isBlank()) return
        fileSearchJob = viewModelScope.launch {
            delay(FILE_SEARCH_DEBOUNCE_MILLIS)
            val matches = localStore.searchCachedFileDownloadKeys(query)
            if (generation == fileSearchGeneration && _uiState.value.downloads.search == query) {
                _uiState.update { it.copy(cachedFileMatchKeys = matches) }
            }
        }
    }

    fun rememberNotificationPermissionAction(action: PendingNotificationAction) {
        pendingNotificationPermissionAction = action
    }

    fun completeNotificationPermission(granted: Boolean) {
        val pending = pendingNotificationPermissionAction
        pendingNotificationPermissionAction = null
        if (!granted) {
            emitMessage("Notification permission is required for completion alerts.")
            return
        }
        when (pending) {
            is PendingNotificationAction.Watch -> toggleWatch(pending.item)
            is PendingNotificationAction.AddDefaults -> setAddOptions(pending.options)
            is PendingNotificationAction.Settings -> updateSettings(pending.state)
            null -> Unit
        }
    }

    fun setSort(sort: DownloadSort) {
        preferences.sort = sort
        _uiState.update { it.copy(downloads = it.downloads.copy(sort = sort)) }
    }

    fun setFilter(filter: DownloadFilter) = _uiState.update {
        it.copy(downloads = it.downloads.copy(filter = filter))
    }

    fun openDetail(item: DownloadItem) = _uiState.update {
        it.copy(selectedDownload = item, fileSheet = null)
    }

    fun closeDetail() = _uiState.update { it.copy(selectedDownload = null) }

    fun openFiles(item: DownloadItem, shareIfSingle: Boolean = false) {
        val requestGeneration = ++fileLoadGeneration
        fileLoadJob?.cancel()
        _uiState.update { it.copy(fileSheet = FileSheetState(download = item), actionInProgress = false) }
        fileLoadJob = launchAccountWork {
            try {
                val files = repository.getFiles(item.type, item.id)
                if (requestGeneration != fileLoadGeneration ||
                    _uiState.value.fileSheet?.download?.key != item.key
                ) return@launchAccountWork
                _uiState.update { state ->
                    val updatedMap = state.loadedFiles + (item.key to files)
                    state.copy(
                        fileSheet = state.fileSheet?.takeIf { it.download.key == item.key }?.copy(
                            files = files,
                            loading = false,
                        ),
                        loadedFiles = updatedMap,
                    )
                }
                if (shareIfSingle && files.size == 1 && !files.first().infected &&
                    requestGeneration == fileLoadGeneration &&
                    _uiState.value.fileSheet?.download?.key == item.key
                ) {
                    shareFile(files.first())
                    if (_uiState.value.fileSheet?.download?.key == item.key) closeFiles()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (requestGeneration != fileLoadGeneration) return@launchAccountWork
                recordOperationalFailure(error)
                _uiState.update { state ->
                    state.copy(
                        fileSheet = state.fileSheet?.takeIf { it.download.key == item.key }?.copy(
                            loading = false,
                            error = safeMessage(error),
                        ),
                    )
                }
            }
        }
    }

    fun closeFiles() {
        fileLoadGeneration++
        fileLoadJob?.cancel()
        fileLoadJob = null
        _uiState.update { it.copy(fileSheet = null) }
    }

    fun openFilesFromNotification(type: DownloadType, id: String) {
        preferences.downloadsTab = DownloadTab.FINISHED
        _uiState.update {
            it.copy(
                destination = AppDestination.DOWNLOADS,
                downloads = it.downloads.copy(selectedTab = DownloadTab.FINISHED),
            )
        }
        updatePolling()
        launchAccountWork {
            try {
                val item = repository.getCachedDownload(type, id)
                    ?: repository.getDownload(type, id, bypassCache = true)
                if (item == null) {
                    emitMessage("That download is no longer available.")
                } else {
                    openFiles(item)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                emitOperationalFailure(error)
            }
        }
    }

    fun retryFiles() {
        val item = _uiState.value.fileSheet?.download ?: return
        val requestGeneration = ++fileLoadGeneration
        fileLoadJob?.cancel()
        _uiState.update { it.copy(fileSheet = it.fileSheet?.copy(loading = true, error = null)) }
        fileLoadJob = launchAccountWork {
            try {
                val files = repository.getFiles(item.type, item.id, forceRefresh = true)
                if (requestGeneration != fileLoadGeneration) return@launchAccountWork
                _uiState.update { state ->
                    if (state.fileSheet?.download?.key != item.key) state else state.copy(
                        fileSheet = state.fileSheet.copy(files = files, loading = false),
                        loadedFiles = state.loadedFiles + (item.key to files),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (requestGeneration != fileLoadGeneration) return@launchAccountWork
                recordOperationalFailure(error)
                _uiState.update { state ->
                    if (state.fileSheet?.download?.key != item.key) state else state.copy(
                        fileSheet = state.fileSheet.copy(loading = false, error = safeMessage(error)),
                    )
                }
            }
        }
    }

    fun shareFile(file: DownloadFile) = requestFileUrl(file, FileUrlAction.SHARE, infectedConfirmed = false)

    fun copyTemporaryLink(file: DownloadFile) = requestFileUrl(file, FileUrlAction.COPY, infectedConfirmed = false)

    fun downloadFile(file: DownloadFile, infectedConfirmed: Boolean = false) {
        if (file.infected && !infectedConfirmed) {
            emitMessage("TorBox marked this file as infected. Confirm from the warning before downloading.")
            return
        }
        requestFileUrl(file, FileUrlAction.DOWNLOAD, infectedConfirmed)
    }

    fun openFile(file: DownloadFile) {
        if (file.infected) {
            emitMessage("TorBox marked this file as infected, so TorBox Drop will not open it.")
            return
        }
        requestFileUrl(file, FileUrlAction.OPEN, infectedConfirmed = false)
    }

    fun downloadZip(item: DownloadItem) {
        if (item.type != DownloadType.TORRENT || item.allowZip != true) {
            emitMessage("A whole-torrent ZIP is not available for this download.")
            return
        }
        requestZipUrl(item)
    }

    fun toggleWatch(item: DownloadItem) {
        launchAccountWork {
            if (item.key in _uiState.value.watchedKeys) {
                localStore.disarmSubscription(item.type, item.id)
                refreshWatchedState()
                stopMonitoringIfEmpty()
                emitMessage("Completion alert turned off for ${item.name}.")
            } else {
                if (!notificationsAllowed()) {
                    emitMessage("Allow notifications before arming a completion alert.")
                    return@launchAccountWork
                }
                if (item.isReady) {
                    emitMessage("${item.name} is already ready.")
                    return@launchAccountWork
                }
                localStore.armSubscription(item)
                refreshWatchedState()
                val startResult = CompletionMonitorService.startFromUserAction(app)
                emitMessage(
                    if (startResult == StartResult.STARTED) {
                        "Live completion monitoring is on for ${item.name}."
                    } else {
                        "Completion alert armed for ${item.name}; Android will check periodically."
                    },
                )
            }
        }
    }

    fun startQueued(item: QueuedDownload) = performAction("Starting ${item.name}") {
        repository.startQueued(item.id)
        refreshSnapshot(bypassCache = true, showSpinner = false)
    }

    fun deleteQueued(item: QueuedDownload) = performAction("Deleting queued download") {
        repository.deleteQueued(item.id)
        localStore.disarmQueuedSubscription(item.type, item.id)
        refreshWatchedState()
        stopMonitoringIfEmpty()
        refreshSnapshot(bypassCache = true, showSpinner = false)
    }

    fun setAirLock(item: DownloadItem, enabled: Boolean) = performDownloadEdit(item) {
        repository.setAirLock(item.type, item.id, enabled)
    }

    fun rename(item: DownloadItem, name: String) = performDownloadEdit(item) {
        repository.rename(item.type, item.id, name.trim())
    }

    fun updateTags(item: DownloadItem, tags: List<String>) = performDownloadEdit(item) {
        repository.setTags(item.type, item.id, tags.map(String::trim).filter(String::isNotBlank).distinct())
    }

    fun reannounce(item: DownloadItem) = performAction("Reannouncing torrent") {
        if (item.type != DownloadType.TORRENT) return@performAction
        repository.reannounceTorrent(item.id)
        refreshSnapshot(bypassCache = true, showSpinner = false)
    }

    fun resume(item: DownloadItem) = performAction("Resuming torrent") {
        if (item.type != DownloadType.TORRENT) return@performAction
        repository.resumeTorrent(item.id)
        refreshSnapshot(bypassCache = true, showSpinner = false)
    }

    fun pause(item: DownloadItem) = performAction("Pausing torrent") {
        if (item.type != DownloadType.TORRENT) return@performAction
        repository.pauseTorrent(item.id)
        refreshSnapshot(bypassCache = true, showSpinner = false)
    }

    fun delete(item: DownloadItem) = performAction("Deleting download") {
        if (item.type == DownloadType.TORRENT) repository.deleteTorrent(item.id)
        else repository.deleteWebDownload(item.id)
        localStore.disarmSubscription(item.type, item.id)
        _uiState.update { it.copy(selectedDownload = null, fileSheet = null) }
        refreshSnapshot(bypassCache = true, showSpinner = false)
    }

    fun validateCurrentToken() {
        _uiState.update {
            it.copy(settings = it.settings.copy(tokenValidationStatus = TokenValidationStatus.VALIDATING))
        }
        launchAccountWork {
            try {
                val account = repository.validateAccount()
                updateValidatedAccount(account)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (error is TorBoxBadTokenException) updateTokenFailure(error)
                else updateTokenUnverified(error)
            }
        }
    }

    fun replaceToken(candidate: String) {
        val trimmed = candidate.trim()
        if (trimmed.isBlank()) {
            emitMessage("Enter a TorBox API token.")
            return
        }
        val previousValidationStatus = _uiState.value.settings.tokenValidationStatus
        _uiState.update {
            it.copy(settings = it.settings.copy(tokenValidationStatus = TokenValidationStatus.VALIDATING))
        }
        viewModelScope.launch {
            tokenReplaceMutex.withLock {
                var accountWorkSuspended = false
                try {
                    val account = container.validateToken(trimmed)
                    val current = container.tokenStore.read()
                    if (current != trimmed) {
                        val preserveUnassignedTorrent = current == null &&
                            (pendingTorrentPayload != null || torrentReadJob?.isActive == true)
                        suspendAccountWorkAndMonitoring(
                            preservePendingTorrent = preserveUnassignedTorrent,
                        )
                        accountWorkSuspended = true
                        CompletionNotifications.cancelAccountNotifications(app)
                        localStore.clearAccountScopedData()
                        container.tokenStore.save(trimmed)
                        preferences.relayUserId = account.userId
                        restartAccountWork()
                        accountWorkSuspended = false
                        resetAccountUi(account, preservePendingTorrent = preserveUnassignedTorrent)
                        if (preserveUnassignedTorrent) {
                            _uiState.update { it.copy(addError = null) }
                        }
                        launchAccountWork { refreshSnapshot(bypassCache = false, showSpinner = true) }
                    } else {
                        updateValidatedAccount(account)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    val hasCurrentToken = container.tokenStore.hasToken()
                    _uiState.update { state ->
                        state.copy(
                            settings = state.settings.copy(
                                tokenConfigured = hasCurrentToken,
                                tokenValidationStatus = if (hasCurrentToken) {
                                    previousValidationStatus.takeUnless {
                                        it == TokenValidationStatus.VALIDATING || it == TokenValidationStatus.INVALID
                                    } ?: TokenValidationStatus.IDLE
                                } else {
                                    TokenValidationStatus.INVALID
                                },
                                tokenValidationMessage = "Replacement not saved: ${safeMessage(error)}",
                            ),
                        )
                    }
                    emitMessage("Replacement token was not saved. ${safeMessage(error)}")
                } finally {
                    if (accountWorkSuspended) {
                        restartAccountWork()
                        updatePolling()
                    }
                }
            }
        }
    }

    fun disconnectAccount() {
        viewModelScope.launch {
            tokenReplaceMutex.withLock {
                suspendAccountWorkAndMonitoring()
                try {
                    CompletionNotifications.cancelAccountNotifications(app)
                    container.tokenStore.clear()
                    preferences.relayUserId = null
                    localStore.clearAccountScopedData()
                    _uiState.update {
                        it.copy(
                            settings = settingsState(defaults = defaultAddOptions()),
                            watchedKeys = emptySet(),
                            downloads = DownloadsUiState(
                                density = preferences.density,
                                selectedTab = preferences.downloadsTab,
                                sort = preferences.sort,
                                initialLoading = false,
                                error = "Connect your TorBox account to refresh.",
                            ),
                            selectedDownload = null,
                            fileSheet = null,
                            loadedFiles = emptyMap(),
                            cachedFileMatchKeys = emptySet(),
                            addSubmitting = false,
                            actionInProgress = false,
                        )
                    }
                } finally {
                    restartAccountWork()
                    updatePolling()
                }
            }
        }
    }

    fun updateSettings(next: SettingsUiState) {
        val previousCookies = preferences.thirdPartyCookiesEnabled
        val normalizedHomePage = BrowserNavigationPolicy.normalizeHomePage(
            input = next.browserHomePage,
            defaultHomePage = AppPreferences.DEFAULT_HOME,
        )
        preferences.confirmBeforeSending = !next.autoSendSharedLinks
        preferences.autoSendClipboardMagnets = next.autoSendClipboardMagnets
        preferences.autoSendBrowserMagnets = next.autoSendBrowserMagnets
        preferences.queueByDefault = next.defaultAddOptions.queued
        preferences.cachedOnlyByDefault = next.defaultAddOptions.cachedOnly
        preferences.notifyNewByDefault = next.defaultAddOptions.notifyWhenComplete
        preferences.seedPreference = next.defaultAddOptions.seed
        preferences.allowZipByDefault = next.defaultAddOptions.allowZip
        preferences.density = next.density
        preferences.adBlockingEnabled = next.blockAdsAndTrackers
        preferences.thirdPartyCookiesEnabled = next.allowThirdPartyCookies
        preferences.browserHomePage = normalizedHomePage
        if (previousCookies != next.allowThirdPartyCookies) discardBrowserWebViewState()
        _uiState.update {
            it.copy(
                settings = next.copy(
                    account = it.settings.account,
                    tokenConfigured = it.settings.tokenConfigured,
                    tokenHint = it.settings.tokenHint,
                    tokenValidationStatus = it.settings.tokenValidationStatus,
                    tokenValidationMessage = it.settings.tokenValidationMessage,
                    monitoredDownloadCount = it.watchedKeys.size,
                    appVersion = BuildConfig.VERSION_NAME,
                    browserHomePage = normalizedHomePage,
                ),
                addOptions = next.defaultAddOptions,
                downloads = it.downloads.copy(density = next.density),
                browserGeneration = it.browserGeneration + if (previousCookies != next.allowThirdPartyCookies) 1 else 0,
            )
        }
    }

    fun clearRecentHistory() = viewModelScope.launch {
        localStore.clearRecentSends()
        _uiState.update { it.copy(recentSends = emptyList()) }
    }

    fun clearCachedMetadata() = viewModelScope.launch {
        refreshMutex.withLock {
            localStore.clearDownloadCache()
            _uiState.update {
                it.copy(
                    downloads = it.downloads.copy(downloads = emptyList(), queue = emptyList(), stale = false),
                    loadedFiles = emptyMap(),
                    cachedFileMatchKeys = emptySet(),
                    selectedDownload = null,
                    fileSheet = null,
                )
            }
        }
    }

    fun clearBrowserData() {
        discardBrowserWebViewState()
        _uiState.update { it.copy(browserGeneration = it.browserGeneration + 1) }
        eventChannel.trySend(MainEvent.ClearBrowserData)
    }

    fun resetSettings() {
        fileSearchGeneration++
        fileSearchJob?.cancel()
        val relayUserId = preferences.relayUserId
        preferences.resetAll()
        preferences.relayUserId = relayUserId
        discardBrowserWebViewState()
        val defaults = defaultAddOptions()
        _uiState.update {
            it.copy(
                addOptions = defaults,
                downloads = it.downloads.copy(
                    density = preferences.density,
                    selectedTab = preferences.downloadsTab,
                    sort = preferences.sort,
                    filter = DownloadFilter(),
                    search = "",
                ),
                settings = settingsState(it.settings.account, defaults),
                cachedFileMatchKeys = emptySet(),
                browserGeneration = it.browserGeneration + 1,
            )
        }
    }

    fun addOrRemoveBookmark(title: String, url: String) = viewModelScope.launch {
        val exists = _uiState.value.bookmarks.any { it.url == url }
        if (exists) localStore.removeBookmark(url) else localStore.addBookmark(title, url)
        _uiState.update { it.copy(bookmarks = localStore.bookmarks()) }
    }

    fun showBookmarks(show: Boolean) = _uiState.update { it.copy(showBookmarks = show) }

    fun openBookmark(url: String) {
        discardBrowserWebViewState()
        _uiState.update {
            it.copy(
                showBookmarks = false,
                destination = AppDestination.BROWSER,
                browserRequestedUrl = url,
                browserGeneration = it.browserGeneration + 1,
            )
        }
        updatePolling()
    }

    fun removeBookmark(url: String) = viewModelScope.launch {
        localStore.removeBookmark(url)
        _uiState.update { it.copy(bookmarks = localStore.bookmarks()) }
    }

    fun setAdBlocking(enabled: Boolean) {
        preferences.adBlockingEnabled = enabled
        _uiState.update { it.copy(settings = it.settings.copy(blockAdsAndTrackers = enabled)) }
    }

    fun setSiteException(host: String, disabled: Boolean) = preferences.setSiteException(host, disabled)

    fun emitMessage(message: String) {
        eventChannel.trySend(MainEvent.Message(message))
    }

    override fun onCleared() {
        discardBrowserWebViewState()
        super.onCleared()
    }

    fun openExternal(url: String, mimeType: String? = null, chooserTitle: String? = null) {
        eventChannel.trySend(MainEvent.OpenUri(url, mimeType, chooserTitle))
    }

    fun downloadBrowserUrl(
        url: String,
        fileName: String,
        mimeType: String?,
        userAgent: String? = null,
        cookies: String? = null,
        referrer: String? = null,
    ) {
        val parsed = InputParser.classify(url)
        if (parsed?.kind != InputParser.Kind.HTTP_URL || !url.startsWith("https://", true)) {
            emitMessage("Only secure HTTPS browser downloads can be sent to Android Downloads.")
            return
        }
        val headers = buildMap {
            userAgent.safeHttpHeaderValue()?.let { put("User-Agent", it) }
            cookies.safeHttpHeaderValue()?.let { put("Cookie", it) }
            referrer.safeHttpHeaderValue()?.takeIf { it.startsWith("https://", true) }
                ?.let { put("Referer", it) }
        }
        eventChannel.trySend(MainEvent.DownloadToDevice(url, fileName, mimeType, headers))
    }

    fun openNotificationSettings() {
        eventChannel.trySend(MainEvent.OpenNotificationSettings)
    }

    fun exportDiagnostics() {
        val state = _uiState.value
        val text = buildString {
            appendLine("TorBox Drop ${BuildConfig.VERSION_NAME}")
            appendLine("Token configured: ${state.settings.tokenConfigured}")
            appendLine("Cached downloads: ${state.downloads.downloads.size}")
            appendLine("Cached queue items: ${state.downloads.queue.size}")
            appendLine("Monitored downloads: ${state.settings.monitoredDownloadCount}")
            appendLine("Last refresh: ${state.downloads.lastUpdated ?: "never"}")
            append("Offline/stale: ${state.downloads.offline || state.downloads.stale}")
        }
        eventChannel.trySend(MainEvent.ShareText(text, "TorBox Drop diagnostics"))
    }

    private suspend fun loadInitialState() {
        val cached = repository.loadCached()
        val recent = localStore.recentSends()
        val bookmarks = localStore.bookmarks()
        val token = container.tokenStore.read()
        _uiState.update { state ->
            state.copy(
                downloads = state.downloads.copy(
                    downloads = cached.downloads,
                    queue = cached.queue,
                    initialLoading = token != null && cached.downloads.isEmpty() && cached.queue.isEmpty(),
                    stale = cached.downloads.isNotEmpty() || cached.queue.isNotEmpty(),
                    lastUpdated = cached.lastUpdated,
                    error = if (token == null && cached.downloads.isEmpty()) {
                        "Connect your TorBox account in Settings."
                    } else null,
                ),
                recentSends = recent,
                bookmarks = bookmarks,
                settings = settingsState(defaults = defaultAddOptions(), token = token),
            )
        }
        refreshWatchedState()
        if (token != null) {
            launchAccountValidation()
            refreshSnapshot(bypassCache = false, showSpinner = cached.downloads.isEmpty())
        }
    }

    private fun launchAccountValidation() = launchAccountWork {
        try {
            updateValidatedAccount(repository.validateAccount())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (error is TorBoxBadTokenException) updateTokenFailure(error)
            else updateTokenUnverified(error)
        }
    }

    private suspend fun refreshSnapshot(bypassCache: Boolean, showSpinner: Boolean) = refreshMutex.withLock {
        if (!container.tokenStore.hasToken()) {
            _uiState.update {
                it.copy(downloads = it.downloads.copy(initialLoading = false, refreshing = false, error = "Connect your TorBox account in Settings."))
            }
            return
        }
        if (showSpinner) _uiState.update { it.copy(downloads = it.downloads.copy(refreshing = true)) }
        try {
            if (bypassCache) requestRelayRefresh()
            val (downloadsResult, queueResult) = supervisorScope {
                val downloads = async { captureRefreshResult { repository.refreshDownloads(bypassCache) } }
                val queue = async { captureRefreshResult { repository.refreshQueue(bypassCache) } }
                downloads.await() to queue.await()
            }
            val rawDownloads = downloadsResult.getOrElse { throw it }
            val reconciledDownloads = reconcileRecentlyAddedDownloads(rawDownloads)
            val refreshedQueue = queueResult.getOrElse { _uiState.value.downloads.queue }
            val reconciledQueue = reconcileRecentlyQueuedDownloads(refreshedQueue)
            val refreshedAt = Instant.now()
            _uiState.update { state ->
                val merged = mergeStable(state.downloads.downloads, reconciledDownloads)
                state.copy(
                    downloads = state.downloads.copy(
                        downloads = merged,
                        queue = if (state.downloads.queue == reconciledQueue) state.downloads.queue else reconciledQueue,
                        refreshing = false,
                        initialLoading = false,
                        offline = false,
                        stale = false,
                        lastUpdated = refreshedAt,
                        error = queueResult.exceptionOrNull()?.let {
                            "Downloads refreshed, but the queue could not be refreshed: ${safeMessage(it)}"
                        },
                    ),
                    selectedDownload = state.selectedDownload?.let { selected ->
                        merged.firstOrNull { it.key == selected.key } ?: selected
                    },
                    fileSheet = state.fileSheet?.let { sheet ->
                        merged.firstOrNull { it.key == sheet.download.key }
                            ?.let { sheet.copy(download = it) }
                            ?: sheet
                    },
                )
            }
            pollingDelayMillis = ACTIVE_REFRESH_MILLIS
            runForegroundCompletionPass(reconciledDownloads, reconciledQueue)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            _uiState.update {
                it.copy(
                    downloads = it.downloads.copy(
                        refreshing = false,
                        initialLoading = false,
                        offline = error is TorBoxOfflineException,
                        stale = it.downloads.downloads.isNotEmpty() || it.downloads.queue.isNotEmpty(),
                        error = safeMessage(error),
                    ),
                )
            }
            if (error is TorBoxBadTokenException) updateTokenFailure(error)
        }
    }

    private suspend fun refreshActiveOnly() = refreshMutex.withLock {
        if (!container.tokenStore.hasToken()) return
        try {
            requestRelayRefresh()
            val monitored = localStore.monitoredSubscriptions()
            val downloads = reconcileRecentlyAddedDownloads(
                repository.refreshDownloads(bypassCache = true),
            )
            val queue = if (monitored.any { it.queueId != null }) {
                try {
                    reconcileRecentlyQueuedDownloads(repository.refreshQueue(bypassCache = true))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    reconcileRecentlyQueuedDownloads(_uiState.value.downloads.queue)
                }
            } else {
                reconcileRecentlyQueuedDownloads(_uiState.value.downloads.queue)
            }
            _uiState.update { state ->
                val merged = mergeStable(state.downloads.downloads, downloads)
                state.copy(
                    downloads = state.downloads.copy(
                        downloads = merged,
                        queue = if (state.downloads.queue == queue) state.downloads.queue else queue,
                        offline = false,
                        stale = false,
                        lastUpdated = Instant.now(),
                        error = null,
                    ),
                    selectedDownload = state.selectedDownload?.let { selected ->
                        merged.firstOrNull { it.key == selected.key } ?: selected
                    },
                    fileSheet = state.fileSheet?.let { sheet ->
                        merged.firstOrNull { it.key == sheet.download.key }
                            ?.let { sheet.copy(download = it) }
                            ?: sheet
                    },
                )
            }
            pollingDelayMillis = ACTIVE_REFRESH_MILLIS
            runForegroundCompletionPass(downloads, queue)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            pollingDelayMillis = when (error) {
                is TorBoxRateLimitException -> ((error.retryAfterSeconds ?: 30L) * 1_000L)
                    .coerceIn(15_000L, 15 * 60_000L)
                is TorBoxOfflineException -> 30_000L
                else -> 15_000L
            }
            _uiState.update {
                it.copy(
                    downloads = it.downloads.copy(
                        offline = error is TorBoxOfflineException,
                        stale = true,
                        error = safeMessage(error),
                    ),
                )
            }
            if (error is TorBoxBadTokenException) updateTokenFailure(error)
        }
    }

    private suspend fun requestRelayRefresh() {
        val torrentIds = _uiState.value.downloads.downloads.asSequence()
            .filter { it.type == DownloadType.TORRENT && !it.isReady }
            .map { it.id }
            .toList()
        if (torrentIds.isEmpty() || preferences.relayUserId.isNullOrBlank()) return
        // Relay is best-effort. The authenticated Main API response remains the only displayed truth.
        repository.requestLiveTorrentUpdates(preferences.relayUserId, torrentIds)
    }

    private fun updatePolling() {
        val state = _uiState.value
        val shouldPoll = isForeground && state.destination == AppDestination.DOWNLOADS &&
            state.downloads.selectedTab == DownloadTab.ACTIVE && container.tokenStore.hasToken() &&
            !tokenRejected
        if (!shouldPoll) {
            foregroundPollingJob?.cancel()
            foregroundPollingJob = null
            return
        }
        if (foregroundPollingJob?.isActive == true) return
        foregroundPollingJob = accountScope.launch {
            while (isActive) {
                delay(pollingDelayMillis)
                refreshActiveOnly()
            }
        }
    }

    private suspend fun runForegroundCompletionPass(
        freshDownloads: List<DownloadItem>,
        currentQueue: List<QueuedDownload>,
    ) {
        val pass = CompletionMonitorRunner(
            app.monitoringDependencies(freshDownloads, currentQueue),
        ) { claim -> CompletionNotifications.postCompletion(app, claim) }.runOnce()
        refreshWatchedState()
        if (pass.authBlocked) {
            updateTokenFailure(
                TorBoxBadTokenException(
                    apiCode = "BAD_TOKEN",
                    statusCode = 401,
                    message = "TorBox rejected the configured API token. Replace it in Settings.",
                ),
            )
        }
        if (!pass.hasArmedDownloads) stopMonitoringIfEmpty()
    }

    private suspend fun refreshWatchedState() {
        val monitored = localStore.monitoredSubscriptions()
        val keys = monitored.filter { it.queueId == null }.map { key(it.type, it.downloadId) }.toSet()
        val count = monitored.size
        _uiState.update {
            it.copy(
                watchedKeys = keys,
                settings = it.settings.copy(
                    monitoredDownloadCount = count,
                    completionMonitoringStatus = when {
                        count == 0 -> "No downloads are currently armed"
                        count == 1 -> "Watching 1 unfinished download"
                        else -> "Watching $count unfinished downloads"
                    },
                ),
            )
        }
        updatePolling()
    }

    private suspend fun stopMonitoringIfEmpty() {
        if (localStore.monitoredSubscriptions().isNotEmpty()) return
        app.stopService(android.content.Intent(app, CompletionMonitorService::class.java))
        CompletionMonitorScheduler.cancelFallback(app)
    }

    private fun submitText(
        parsed: InputParser.ParsedLink,
        options: AddOptions,
        source: String,
        remainInBrowser: Boolean,
    ) {
        val configuredToken = container.tokenStore.read()
        if (!configuredToken.isNullOrBlank() && UrlSafety.containsApiToken(parsed.value, configuredToken)) {
            emitMessage("TorBox Drop refused a link containing your API token.")
            return
        }
        _uiState.update {
            it.copy(
                destination = if (remainInBrowser) it.destination else AppDestination.ADD,
                addCandidate = parsed.value,
                addSubmitting = true,
                addResult = null,
                addError = null,
            )
        }
        updatePolling()
        launchAccountWork {
            try {
                requireToken()
                val result = when (parsed.kind) {
                    InputParser.Kind.MAGNET -> repository.createMagnet(parsed.value, options, source)
                    InputParser.Kind.HTTP_URL -> repository.createWebDownload(parsed.value, options, source)
                }
                finishSuccessfulAdd(
                    result = result,
                    type = parsed.downloadType,
                    options = options,
                    fallbackName = parsed.value,
                    navigateAfterSuccess = !remainInBrowser,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                finishFailedAdd(error)
                if (remainInBrowser) emitMessage(safeMessage(error))
            }
        }
    }

    private suspend fun finishSuccessfulAdd(
        result: AddResult,
        type: DownloadType,
        options: AddOptions,
        fallbackName: String,
        navigateAfterSuccess: Boolean,
    ) {
        val displayName = result.name ?: fallbackDisplayName(type, fallbackName)
        val targetTab = when {
            result.id != null -> DownloadTab.ACTIVE
            result.queuedId != null || result.queued -> DownloadTab.QUEUE
            else -> DownloadTab.ACTIVE
        }
        val provisionalDownload = result.id?.let { id ->
            DownloadItem(
                id = id,
                type = type,
                name = displayName,
                rawState = "waiting_for_torbox",
                friendlyState = "Waiting for TorBox",
                createdAt = Instant.now(),
                hash = result.sourceHash,
            )
        }
        val provisionalQueue = result.queuedId?.let { id ->
            QueuedDownload(
                id = id,
                type = type,
                name = displayName,
                queuedAt = Instant.now(),
                source = fallbackName,
            )
        }
        provisionalDownload?.let { recentlyAddedDownloads[it.key] = it }
        provisionalQueue?.let { recentlyQueuedDownloads[it.key] = it }
        if (navigateAfterSuccess) preferences.downloadsTab = targetTab
        val recentSends = localStore.recentSends()
        _uiState.update {
            val downloads = provisionalDownload?.let { added ->
                RecentAdditions.mergeDownloads(it.downloads.downloads, listOf(added))
            } ?: it.downloads.downloads
            val queue = provisionalQueue?.let { added ->
                RecentAdditions.mergeQueue(it.downloads.queue, listOf(added))
            } ?: it.downloads.queue
            it.copy(
                destination = if (navigateAfterSuccess) AppDestination.DOWNLOADS else it.destination,
                addSubmitting = false,
                addCandidate = "",
                addResult = null,
                addError = null,
                pendingTorrentName = null,
                recentSends = recentSends,
                downloads = it.downloads.copy(
                    downloads = downloads,
                    queue = queue,
                    selectedTab = if (navigateAfterSuccess) targetTab else it.downloads.selectedTab,
                    search = if (navigateAfterSuccess) "" else it.downloads.search,
                    filter = if (navigateAfterSuccess) DownloadFilter() else it.downloads.filter,
                ),
            )
        }
        emitMessage(result.detail)
        updatePolling()
        if (options.notifyWhenComplete && !notificationsAllowed()) {
            emitMessage("Download added, but its completion alert was not armed because notifications are disabled.")
        } else if (options.notifyWhenComplete && result.id != null) {
            val remote = try {
                repository.getDownload(type, result.id, bypassCache = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                recordOperationalFailure(error)
                emitMessage("Download added. Live status is not available yet: ${safeMessage(error)}")
                null
            }
            val item = remote ?: DownloadItem(
                id = result.id,
                type = type,
                name = result.name ?: fallbackDisplayName(type, fallbackName),
            )
            localStore.armSubscription(item, hasBeenSeen = remote != null)
            refreshWatchedState()
            val startResult = CompletionMonitorService.startFromUserAction(app)
            if (startResult == StartResult.FALLBACK_SCHEDULED) {
                emitMessage("Completion alert armed; Android will check periodically.")
            }
        } else if (options.notifyWhenComplete && result.queuedId != null) {
            localStore.armQueuedSubscription(
                type = type,
                queuedId = result.queuedId,
                name = result.name ?: fallbackDisplayName(type, fallbackName),
                sourceHash = result.sourceHash,
                sourceValue = fallbackName,
            )
            refreshWatchedState()
            val startResult = CompletionMonitorService.startFromUserAction(app)
            emitMessage(
                if (startResult == StartResult.STARTED) {
                    "Queued download added; its completion alert is armed."
                } else {
                    "Queued download added; Android will check it periodically."
                },
            )
        } else if (options.notifyWhenComplete && result.queued) {
            emitMessage("Added to the queue, but TorBox did not return a queue ID to monitor.")
        } else if (options.notifyWhenComplete) {
            emitMessage("Download added, but TorBox did not return an active ID to monitor yet.")
        }
        try {
            refreshSnapshot(bypassCache = true, showSpinner = false)
        } catch (error: CancellationException) {
            throw error
        }
    }

    private suspend fun reconcileRecentlyAddedDownloads(
        serverDownloads: List<DownloadItem>,
    ): List<DownloadItem> {
        val serverKeys = serverDownloads.mapTo(HashSet()) { it.key }
        recentlyAddedDownloads.keys.removeAll(serverKeys)
        val pendingKeys = recentlyAddedDownloads.keys.toList()
        pendingKeys.forEach { pendingKey ->
            val pending = recentlyAddedDownloads[pendingKey] ?: return@forEach
            val current = try {
                repository.getDownload(pending.type, pending.id, bypassCache = true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                null
            }
            if (current != null) recentlyAddedDownloads[pendingKey] = current
        }
        return RecentAdditions.mergeDownloads(serverDownloads, recentlyAddedDownloads.values)
    }

    private fun reconcileRecentlyQueuedDownloads(
        serverQueue: List<QueuedDownload>,
    ): List<QueuedDownload> {
        val serverKeys = serverQueue.mapTo(HashSet()) { it.key }
        recentlyQueuedDownloads.keys.removeAll(serverKeys)
        return RecentAdditions.mergeQueue(serverQueue, recentlyQueuedDownloads.values)
    }

    private suspend fun <T> captureRefreshResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private fun finishFailedAdd(error: Throwable) {
        if (error is CancellationException) throw error
        _uiState.update { it.copy(addSubmitting = false, addResult = null, addError = safeMessage(error)) }
        if (error is TorBoxBadTokenException) updateTokenFailure(error)
    }

    private fun requestFileUrl(
        file: DownloadFile,
        action: FileUrlAction,
        infectedConfirmed: Boolean,
    ) {
        val item = _uiState.value.downloads.downloads.firstOrNull {
            it.type == file.downloadType && it.id == file.downloadId
        } ?: _uiState.value.fileSheet?.download?.takeIf {
            it.type == file.downloadType && it.id == file.downloadId
        }
        if (item == null) {
            emitMessage("This download is no longer available in the current list.")
            return
        }
        if (file.infected && action != FileUrlAction.DOWNLOAD) {
            emitMessage("TorBox marked this file as infected. Opening and sharing are disabled.")
            return
        }
        _uiState.update { it.copy(actionInProgress = true) }
        launchAccountWork {
            try {
                val freshItem = repository.getDownload(item.type, item.id, bypassCache = true)
                    ?: error("That download no longer exists.")
                if (!freshItem.isReady) error("That content is not complete and present yet.")
                val freshFiles = repository.getFiles(item.type, item.id, forceRefresh = true)
                val freshFile = freshFiles.firstOrNull { it.id == file.id }
                    ?: error("That file is no longer available.")
                _uiState.update { state ->
                    state.copy(
                        loadedFiles = state.loadedFiles + (item.key to freshFiles),
                        fileSheet = state.fileSheet?.takeIf { it.download.key == item.key }
                            ?.copy(download = freshItem, files = freshFiles, loading = false),
                    )
                }
                if (freshFile.infected && action != FileUrlAction.DOWNLOAD) {
                    error("TorBox now marks this file as infected. Opening and sharing are disabled.")
                }
                if (freshFile.infected && !infectedConfirmed) {
                    error("TorBox now marks this file as infected. Review the warning before downloading it.")
                }
                val url = repository.requestTemporaryDownloadUrl(
                    freshItem.type,
                    freshItem.id,
                    fileId = freshFile.id,
                    zip = false,
                    appendName = true,
                )
                val safeUrl = UrlSafety.requireSafeToShare(url, container.tokenStore.read())
                val resolvedMimeType = freshFile.inferredMimeType()
                when (action) {
                    FileUrlAction.SHARE -> eventChannel.send(
                        MainEvent.ShareText(safeUrl, "Share ${freshFile.name}", requiresTorBoxUrlSafety = true),
                    )
                    FileUrlAction.COPY -> eventChannel.send(
                        MainEvent.CopyText(safeUrl, "Temporary TorBox link", requiresTorBoxUrlSafety = true),
                    )
                    FileUrlAction.DOWNLOAD -> eventChannel.send(
                        MainEvent.DownloadToDevice(
                            safeUrl,
                            freshFile.name,
                            resolvedMimeType,
                            requiresTorBoxUrlSafety = true,
                        ),
                    )
                    FileUrlAction.OPEN -> eventChannel.send(
                        MainEvent.OpenUri(
                            safeUrl,
                            resolvedMimeType,
                            "Open ${freshFile.name}",
                            requiresTorBoxUrlSafety = true,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                emitOperationalFailure(error)
            } finally {
                _uiState.update { it.copy(actionInProgress = false) }
            }
        }
    }

    private fun requestZipUrl(item: DownloadItem) {
        _uiState.update { it.copy(actionInProgress = true) }
        launchAccountWork {
            try {
                val freshItem = repository.getDownload(item.type, item.id, bypassCache = true)
                    ?: error("That download no longer exists.")
                if (!freshItem.isReady || freshItem.type != DownloadType.TORRENT || freshItem.allowZip != true) {
                    error("A whole-torrent ZIP is not available for this download.")
                }
                val freshFiles = repository.getFiles(item.type, item.id, forceRefresh = true)
                if (freshFiles.any { it.infected }) {
                    error("ZIP download is disabled because TorBox flagged at least one file as infected.")
                }
                val url = repository.requestTemporaryDownloadUrl(
                    freshItem.type,
                    freshItem.id,
                    zip = true,
                    appendName = true,
                )
                val safeUrl = UrlSafety.requireSafeToShare(url, container.tokenStore.read())
                eventChannel.send(
                    MainEvent.DownloadToDevice(
                        safeUrl,
                        "${freshItem.name}.zip",
                        "application/zip",
                        requiresTorBoxUrlSafety = true,
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                emitOperationalFailure(error)
            } finally {
                _uiState.update { it.copy(actionInProgress = false) }
            }
        }
    }

    private fun performDownloadEdit(item: DownloadItem, operation: suspend () -> DownloadItem) {
        _uiState.update { it.copy(actionInProgress = true) }
        launchAccountWork {
            try {
                val updated = operation()
                _uiState.update { state ->
                    state.copy(
                        actionInProgress = false,
                        selectedDownload = state.selectedDownload?.let { selected ->
                            if (selected.key == item.key) updated else selected
                        },
                        downloads = state.downloads.copy(
                            downloads = state.downloads.downloads.map { if (it.key == item.key) updated else it },
                        ),
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update { it.copy(actionInProgress = false) }
                emitOperationalFailure(error)
            }
        }
    }

    private fun performAction(label: String, operation: suspend () -> Unit) {
        _uiState.update { it.copy(actionInProgress = true) }
        launchAccountWork {
            try {
                operation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                recordOperationalFailure(error)
                emitMessage("$label failed: ${safeMessage(error)}")
            } finally {
                _uiState.update { it.copy(actionInProgress = false) }
            }
        }
    }

    private fun updateValidatedAccount(account: AccountInfo) {
        tokenRejected = false
        preferences.relayUserId = account.userId
        val token = container.tokenStore.read()
        _uiState.update {
            it.copy(
                settings = it.settings.copy(
                    account = account,
                    tokenConfigured = token != null,
                    tokenHint = token?.let(::maskToken),
                    tokenValidationStatus = TokenValidationStatus.VALID,
                    tokenValidationMessage = "Connected",
                ),
            )
        }
        if (_uiState.value.watchedKeys.isNotEmpty()) CompletionMonitorScheduler.scheduleFallback(app)
        updatePolling()
    }

    private fun updateTokenUnverified(error: Throwable) {
        _uiState.update {
            it.copy(
                settings = it.settings.copy(
                    tokenConfigured = container.tokenStore.hasToken(),
                    tokenValidationStatus = TokenValidationStatus.IDLE,
                    tokenValidationMessage = "Could not verify now: ${safeMessage(error)}",
                ),
            )
        }
        emitMessage("TorBox account could not be verified right now. ${safeMessage(error)}")
    }

    private suspend fun suspendAccountWorkAndMonitoring(
        preservePendingTorrent: Boolean = false,
    ) {
        foregroundPollingJob?.cancel()
        foregroundPollingJob = null
        fileLoadGeneration++
        fileLoadJob?.cancel()
        fileLoadJob = null
        fileSearchGeneration++
        fileSearchJob?.cancel()
        fileSearchJob = null
        if (!preservePendingTorrent) {
            torrentReadGeneration++
            torrentReadJob?.cancelAndJoin()
            torrentReadJob = null
            pendingTorrentPayload = null
        }
        app.stopService(android.content.Intent(app, CompletionMonitorService::class.java))
        CompletionMonitorScheduler.cancelFallback(app)
        accountWorkJob.cancelAndJoin()
        CompletionMonitorRunner.awaitIdle()
        _uiState.update {
            it.copy(
                pendingTorrentName = if (preservePendingTorrent) it.pendingTorrentName else null,
                addSubmitting = preservePendingTorrent && torrentReadJob?.isActive == true,
                addError = if (preservePendingTorrent) it.addError else null,
                actionInProgress = false,
            )
        }
    }

    private fun restartAccountWork() {
        accountWorkJob = SupervisorJob(viewModelScope.coroutineContext[Job])
        accountScope = CoroutineScope(viewModelScope.coroutineContext + accountWorkJob)
    }

    private fun resetAccountUi(
        account: AccountInfo,
        preservePendingTorrent: Boolean = false,
    ) {
        _uiState.update { state ->
            state.copy(
                downloads = DownloadsUiState(
                    density = preferences.density,
                    selectedTab = preferences.downloadsTab,
                    sort = preferences.sort,
                    initialLoading = true,
                ),
                settings = settingsState(account = account, defaults = defaultAddOptions()),
                selectedDownload = null,
                fileSheet = null,
                loadedFiles = emptyMap(),
                cachedFileMatchKeys = emptySet(),
                watchedKeys = emptySet(),
                pendingTorrentName = if (preservePendingTorrent) state.pendingTorrentName else null,
                addSubmitting = preservePendingTorrent && state.addSubmitting,
                actionInProgress = false,
            )
        }
        updateValidatedAccount(account)
    }

    private fun updateTokenFailure(error: Throwable) {
        tokenRejected = true
        foregroundPollingJob?.cancel()
        foregroundPollingJob = null
        app.stopService(android.content.Intent(app, CompletionMonitorService::class.java))
        CompletionMonitorScheduler.cancelFallback(app)
        _uiState.update {
            it.copy(
                settings = it.settings.copy(
                    tokenConfigured = container.tokenStore.hasToken(),
                    tokenValidationStatus = TokenValidationStatus.INVALID,
                    tokenValidationMessage = safeMessage(error),
                ),
                downloads = it.downloads.copy(
                    stale = it.downloads.downloads.isNotEmpty() || it.downloads.queue.isNotEmpty(),
                    refreshing = false,
                    initialLoading = false,
                    error = "TorBox rejected the configured API token. Replace it in Settings.",
                ),
            )
        }
    }

    private fun launchAccountWork(block: suspend CoroutineScope.() -> Unit): Job = accountScope.launch(block = block)

    private fun settingsState(
        account: AccountInfo? = null,
        defaults: AddOptions = defaultAddOptions(),
        token: String? = container.tokenStore.read(),
    ) = SettingsUiState(
        account = account,
        tokenConfigured = token != null,
        tokenHint = token?.let(::maskToken),
        defaultAddOptions = defaults,
        density = preferences.density,
        autoSendSharedLinks = !preferences.confirmBeforeSending,
        autoSendClipboardMagnets = preferences.autoSendClipboardMagnets,
        autoSendBrowserMagnets = preferences.autoSendBrowserMagnets,
        blockAdsAndTrackers = preferences.adBlockingEnabled,
        allowThirdPartyCookies = preferences.thirdPartyCookiesEnabled,
        browserHomePage = preferences.browserHomePage,
        completionMonitoringStatus = "No downloads are currently armed",
        appVersion = BuildConfig.VERSION_NAME,
    )

    private fun defaultAddOptions() = AddOptions(
        queued = preferences.queueByDefault,
        cachedOnly = preferences.cachedOnlyByDefault,
        notifyWhenComplete = preferences.notifyNewByDefault,
        seed = preferences.seedPreference,
        allowZip = preferences.allowZipByDefault,
    )

    private fun discardBrowserWebViewState() {
        browserWebViewSnapshot?.second?.clear()
        browserWebViewSnapshot = null
    }

    private fun dismissDownloadSurfacesForIncoming() {
        fileLoadGeneration++
        fileLoadJob?.cancel()
        fileLoadJob = null
        _uiState.update { it.copy(selectedDownload = null, fileSheet = null, pendingTorrentName = null) }
    }

    private fun requireToken() {
        if (!container.tokenStore.hasToken()) throw IllegalStateException("Connect your TorBox account in Settings first.")
    }

    private fun safeMessage(error: Throwable): String {
        val message = if (error is TorBoxException) error.message else error.message
        return UrlSafety.redact(message ?: "The action could not be completed.", container.tokenStore.read())
            .ifBlank { "The action could not be completed." }
    }

    private fun recordOperationalFailure(error: Throwable) {
        if (error is TorBoxBadTokenException) updateTokenFailure(error)
    }

    private fun emitOperationalFailure(error: Throwable) {
        recordOperationalFailure(error)
        emitMessage(safeMessage(error))
    }

    private fun mergeStable(old: List<DownloadItem>, fresh: List<DownloadItem>): List<DownloadItem> {
        val previous = old.associateBy { it.key }
        return fresh.map { next -> previous[next.key]?.takeIf { it == next } ?: next }
    }

    private val DownloadItem.key: String get() = key(type, id)
    private val DownloadFile.key: String get() = key(downloadType, downloadId)
    private val QueuedDownload.key: String get() = key(type, id)
    private fun key(type: DownloadType, id: String) = "${type.name}:$id"

    private fun maskToken(token: String): String = "•••• ${token.takeLast(4)}"

    private fun fallbackDisplayName(type: DownloadType, source: String): String = when (type) {
        DownloadType.TORRENT -> runCatching {
            Uri.parse(source).getQueryParameter("dn")?.takeIf(String::isNotBlank)
        }.getOrNull() ?: source.takeIf { it.endsWith(".torrent", ignoreCase = true) } ?: "New torrent"
        DownloadType.WEB -> runCatching {
            Uri.parse(source).lastPathSegment?.takeIf(String::isNotBlank)
        }.getOrNull() ?: "New web download"
    }

    private fun String?.safeHttpHeaderValue(): String? = this
        ?.takeIf { it.isNotBlank() && it.length <= 8_192 && '\r' !in it && '\n' !in it }

    private fun notificationsAllowed(): Boolean {
        CompletionNotificationChannels.ensureCreated(app)
        return NotificationCapabilities.current(app).canPostCompletion
    }

    private enum class FileUrlAction { SHARE, COPY, DOWNLOAD, OPEN }

    private companion object {
        const val ACTIVE_REFRESH_MILLIS = 5_000L
        const val FILE_SEARCH_DEBOUNCE_MILLIS = 120L
    }
}
