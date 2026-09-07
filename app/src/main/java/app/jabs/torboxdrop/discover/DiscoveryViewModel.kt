package app.jabs.torboxdrop.discover

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.jabs.torboxdrop.TorBoxDropApplication
import app.jabs.torboxdrop.data.TorBoxRateLimitException
import app.jabs.torboxdrop.data.TorBoxBadTokenException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Results are session-only. No account token, search history, or torrent URLs are written to disk. */
data class DiscoveryState(
    val filter: DiscoveryFilter = DiscoveryFilter(),
    val catalogs: List<DiscoveryCatalog> = emptyList(),
    val rows: List<CachedTitle> = emptyList(),
    val checked: Int = 0,
    val busy: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
    val adding: String? = null,
    val cooldownUntil: Long = 0,
)

class DiscoveryViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as TorBoxDropApplication).container
    private val repository = DiscoveryRepository(DiscoveryClient()) { container.api.checkTorrentCached(it) }
    private val mutable = MutableStateFlow(DiscoveryState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var generation = 0
    private var accountFingerprint: String? = null
    private var catalogOffset = 0
    private val pending = ArrayDeque<DiscoveryTitle>()
    private val seen = mutableSetOf<String>()
    private var exhausted = false
    private var visible = false

    fun activate() {
        visible = true
        val fingerprint = fingerprint()
        if (fingerprint != accountFingerprint) {
            cancel()
            accountFingerprint = fingerprint
            reset(DiscoveryFilter())
        }
        if (fingerprint == null) {
            mutable.update { it.copy(error = "Connect your TorBox account in Settings to check cached titles.") }
        } else if (mutable.value.catalogs.isEmpty() || (mutable.value.checked == 0 && mutable.value.error == null)) {
            loadMore()
        }
    }

    fun deactivate() { visible = false; cancel() }
    fun cancel() {
        generation++
        work?.cancel()
        work = null
        mutable.update { it.copy(busy = false, adding = null) }
    }
    private fun reset(filter: DiscoveryFilter) {
        pending.clear(); seen.clear(); catalogOffset = 0; exhausted = false
        mutable.value = DiscoveryState(filter = filter, catalogs = mutable.value.catalogs,
            cooldownUntil = mutable.value.cooldownUntil)
    }
    fun change(filter: DiscoveryFilter) {
        cancel()
        reset(filter)
        loadMore()
    }
    fun refresh() = change(mutable.value.filter)

    fun loadMore() {
        if (!visible || mutable.value.busy || mutable.value.adding != null) return
        if (fingerprint() == null || fingerprint() != accountFingerprint) { activate(); return }
        if (System.currentTimeMillis() < mutable.value.cooldownUntil) {
            mutable.update { it.copy(error = "Requests are paused after a rate limit. Try again shortly.") }; return
        }
        val run = ++generation
        mutable.update { it.copy(busy = true, error = null) }
        work = viewModelScope.launch {
            try {
                if (mutable.value.catalogs.isEmpty()) {
                    val catalogs = repository.catalogs()
                    if (!isCurrent(run)) return@launch
                    mutable.update { it.copy(catalogs = catalogs) }
                }
                val requested = mutable.value.filter
                val catalog = mutable.value.catalogs.find { it.kind == requested.kind && it.id == requested.catalogId }
                    ?: mutable.value.catalogs.firstOrNull { it.kind == requested.kind }
                    ?: throw DiscoveryFailure("No catalog is available for this media type.")
                val filter = requested.copy(catalogId = catalog.id)
                mutable.update { it.copy(filter = filter) }
                var attempted = 0
                // Never crawl a whole catalog automatically. Each press checks at most 12 titles.
                while (attempted < BATCH_SIZE) {
                    if (pending.isEmpty() && !exhausted) {
                        val page = repository.titles(filter, catalog, catalogOffset)
                        if (!isCurrent(run)) return@launch
                        catalogOffset += page.sourceCount
                        val newTitles = page.titles.filter { seen.add(it.id) }
                        exhausted = page.sourceCount == 0 || newTitles.isEmpty() || !catalog.pageable
                        pending.addAll(newTitles)
                    }
                    val title = pending.firstOrNull() ?: break
                    val releases = repository.releases(filter, title)
                    if (!isCurrent(run)) return@launch
                    pending.removeFirst() // A failed lookup remains first, so Retry does not skip it.
                    attempted++
                    mutable.update { current ->
                        current.copy(checked = current.checked + 1, rows =
                            if (releases.isEmpty()) current.rows else current.rows + CachedTitle(title, filter.scope, releases))
                    }
                    if (attempted < BATCH_SIZE) delay(600)
                }
                mutable.update { it.copy(hasMore = pending.isNotEmpty() || !exhausted) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (isCurrent(run)) report(failure)
            } finally {
                if (run == generation) mutable.update { it.copy(busy = false) }
            }
        }
    }

    fun add(release: CachedRelease, onReady: (String) -> Unit) {
        if (!visible || mutable.value.busy || mutable.value.adding != null) return
        if (fingerprint() != accountFingerprint || fingerprint() == null) { activate(); return }
        if (System.currentTimeMillis() < mutable.value.cooldownUntil) {
            mutable.update { it.copy(error = "Requests are paused after a rate limit. Try again shortly.") }
            return
        }
        val run = ++generation
        mutable.update { it.copy(adding = release.hash, error = null) }
        work = viewModelScope.launch {
            try {
                val stillCached = repository.recheck(release)
                if (!isCurrent(run)) return@launch
                if (!stillCached) {
                    mutable.update { state -> state.copy(rows = state.rows.mapNotNull { row ->
                        val remaining = row.releases.filterNot { it.hash == release.hash }
                        row.copy(releases = remaining).takeIf { remaining.isNotEmpty() }
                    }) }
                    throw DiscoveryFailure("TorBox no longer reports this torrent as cached. It was not added.")
                }
                if (isCurrent(run)) onReady(release.magnet)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (isCurrent(run)) report(failure)
            } finally {
                if (run == generation) mutable.update { it.copy(adding = null) }
            }
        }
    }

    private fun isCurrent(run: Int) = visible && run == generation && fingerprint() == accountFingerprint
    private fun fingerprint(): String? = container.tokenStore.read()?.takeIf { it.isNotBlank() }?.let {
        MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) }
    }
    private fun report(failure: Exception) {
        // Do not expose arbitrary HTTP exception strings that might contain a credential-bearing URL.
        val detail = when (failure) {
            is DiscoveryFailure -> failure
            is TorBoxRateLimitException -> DiscoveryFailure(
                "TorBox is rate-limiting cache checks. Try again shortly.",
                failure.retryAfterSeconds?.coerceIn(1, 86_400) ?: 60,
            )
            is TorBoxBadTokenException -> DiscoveryFailure("TorBox rejected your API key. Update it in Settings.")
            else -> null
        }
        mutable.update { it.copy(
            error = detail?.message ?: "TorBox could not verify cache availability. Check your account and connection, then retry.",
            cooldownUntil = if ((detail?.retryAfterSeconds ?: 0) > 0) {
                System.currentTimeMillis() + detail!!.retryAfterSeconds * 1000
            } else it.cooldownUntil,
        ) }
    }
    private companion object { const val BATCH_SIZE = 12 }
}
