package app.jabs.torboxdrop.discover

import app.jabs.torboxdrop.data.CachedDownload
import app.jabs.torboxdrop.data.Json
import app.jabs.torboxdrop.data.JsonValue
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class DiscoveryFailure(message: String, val retryAfterSeconds: Long = 0) : IOException(message)

/** This client has no token provider, cookie jar, or TorBox-authenticated interceptors. */
class DiscoveryClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build(),
    private val catalogBase: HttpUrl = "https://v3-cinemeta.strem.io/".toHttpUrl(),
    private val streamsBase: HttpUrl = "https://torrentio.strem.fun/".toHttpUrl(),
) : DiscoverySource {
    init {
        for (base in listOf(catalogBase, streamsBase)) {
            require(base.isHttps || base.host in setOf("localhost", "127.0.0.1", "::1"))
            require(base.username.isEmpty() && base.password.isEmpty() && base.query == null)
        }
    }

    override suspend fun catalogs(): List<DiscoveryCatalog> = DiscoveryWire.catalogs(get(catalogBase, "Cinemeta", "manifest.json"))

    override suspend fun titles(filter: DiscoveryFilter, catalog: DiscoveryCatalog, offset: Int): DiscoveryPage {
        require(catalog.kind == filter.kind && catalog.id == filter.catalogId && offset >= 0)
        require(catalog.id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val extras = mutableListOf<String>()
        if (filter.query.isNotBlank()) {
            if (!catalog.searchable) throw DiscoveryFailure("This catalog does not support search. Choose Popular.")
            extras += "search=${encode(filter.query.take(160))}"
        }
        if (filter.genre.isNotBlank()) extras += "genre=${encode(filter.genre)}"
        if (offset > 0) {
            if (!catalog.pageable) return DiscoveryPage(emptyList(), 0)
            extras += "skip=$offset"
        }
        if (catalog.genreRequired && filter.genre.isBlank()) throw DiscoveryFailure("Choose a year or genre for this catalog.")
        val path = "catalog/${filter.kind.wire}/${catalog.id}" +
            if (extras.isEmpty()) ".json" else "/${extras.joinToString("&")}.json"
        return DiscoveryWire.titles(get(catalogBase, "Cinemeta", path))
    }

    override suspend fun candidates(filter: DiscoveryFilter, title: DiscoveryTitle): List<TorrentCandidate> =
        DiscoveryWire.candidates(get(streamsBase, "Torrentio", "stream/${filter.kind.wire}/${filter.streamId(title.id)}.json"))

    private suspend fun get(base: HttpUrl, source: String, path: String): JsonValue.Object = withContext(Dispatchers.IO) {
        val url = base.resolve(path) ?: throw DiscoveryFailure("Invalid catalog request.")
        require(url.host == base.host && url.scheme == base.scheme)
        val call = http.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
        val response = try {
            suspendCancellableCoroutine<Response> { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    }
                })
            }
        } catch (_: IOException) {
            throw DiscoveryFailure("$source could not be reached. Check your connection and try again.")
        }
        response.use {
            if (it.code == 429) {
                val delay = it.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 3600) ?: 60
                throw DiscoveryFailure("$source is rate-limiting requests. Try again after $delay seconds.", delay)
            }
            if (!it.isSuccessful) throw DiscoveryFailure("$source returned HTTP ${it.code}. Availability is unknown, not uncached.")
            val body = it.body ?: throw DiscoveryFailure("$source returned an empty response.")
            val sourceBytes = body.source()
            if (sourceBytes.request(MAX_RESPONSE_BYTES + 1L)) throw DiscoveryFailure("$source returned an oversized response.")
            val text = sourceBytes.readUtf8()
            try {
                Json.parse(text) as? JsonValue.Object ?: throw DiscoveryFailure("$source returned invalid data.")
            } catch (failure: DiscoveryFailure) {
                throw failure
            } catch (_: Exception) {
                throw DiscoveryFailure("$source returned invalid data.")
            }
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private companion object { const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024L }
}

internal object DiscoveryWire {
    fun catalogs(root: JsonValue.Object): List<DiscoveryCatalog> {
        val entries = root.array("catalogs") ?: throw DiscoveryFailure("Cinemeta's catalog list is unavailable.")
        val result = entries.values.mapNotNull { value ->
            val obj = value as? JsonValue.Object ?: return@mapNotNull null
            val kind = MediaKind.entries.find { it.wire == obj.string("type") } ?: return@mapNotNull null
            val id = obj.string("id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,80}")) } ?: return@mapNotNull null
            val extras = obj.array("extra")?.values.orEmpty().mapNotNull { it as? JsonValue.Object }
            if (extras.any { it.boolean("isRequired") == true && it.string("name") != "genre" }) return@mapNotNull null
            val genre = extras.find { it.string("name") == "genre" }
            DiscoveryCatalog(
                id, obj.string("name") ?: id, kind,
                genre?.array("options")?.values.orEmpty().mapNotNull { (it as? JsonValue.StringValue)?.value },
                extras.any { it.string("name") == "search" }, extras.any { it.string("name") == "skip" },
                genre?.boolean("isRequired") == true,
            )
        }.distinctBy { it.kind to it.id }
        if (result.isEmpty()) throw DiscoveryFailure("No compatible movie or TV catalogs are available.")
        return result
    }

    fun titles(root: JsonValue.Object): DiscoveryPage {
        val entries = root.array("metas") ?: throw DiscoveryFailure("Cinemeta returned no recognizable title list.")
        val titles = entries.values.mapNotNull { value ->
            val obj = value as? JsonValue.Object ?: return@mapNotNull null
            val id = obj.string("id")?.takeIf(DiscoveryRules::validImdb) ?: return@mapNotNull null
            val name = obj.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DiscoveryTitle(id, name.take(300), obj.string("releaseInfo").orEmpty().take(40),
                obj.array("genres")?.values.orEmpty().mapNotNull { (it as? JsonValue.StringValue)?.value }.take(8))
        }.distinctBy { it.id }
        if (entries.values.isNotEmpty() && titles.isEmpty()) throw DiscoveryFailure("Cinemeta returned no usable IMDb title IDs.")
        return DiscoveryPage(titles, entries.values.size)
    }

    fun candidates(root: JsonValue.Object): List<TorrentCandidate> {
        val entries = root.array("streams") ?: throw DiscoveryFailure("Torrentio returned no recognizable torrent list.")
        val parsed = entries.values.mapNotNull { value ->
            val obj = value as? JsonValue.Object ?: return@mapNotNull null
            val hash = DiscoveryRules.hash(obj.string("infoHash")) ?: return@mapNotNull null
            val name = obj.obj("behaviorHints")?.string("filename")
                ?: obj.string("title")?.lineSequence()?.firstOrNull()
                ?: "Torrent $hash"
            TorrentCandidate(hash, name.take(500), obj.string("name").orEmpty().take(100))
        }
        // A message-only service/error placeholder must not become a false empty-cache claim.
        if (entries.values.isNotEmpty() && parsed.isEmpty()) throw DiscoveryFailure("Torrentio returned no usable torrent hashes. Availability is unknown.")
        return DiscoveryRules.deduplicate(parsed)
    }
}

/** Bounded candidate lookup plus TorBox's existing, authenticated Main API cache checker. */
class DiscoveryRepository(
    private val source: DiscoverySource,
    private val checkCache: suspend (Collection<String>) -> Map<String, CachedDownload>,
) {
    suspend fun catalogs() = source.catalogs()
    suspend fun titles(filter: DiscoveryFilter, catalog: DiscoveryCatalog, offset: Int) = source.titles(filter, catalog, offset)
    suspend fun releases(filter: DiscoveryFilter, title: DiscoveryTitle): List<CachedRelease> {
        val candidates = source.candidates(filter, title).take(MAX_CANDIDATES)
        if (candidates.isEmpty()) return emptyList()
        val cache = linkedMapOf<String, CachedDownload>()
        candidates.map { it.hash }.chunked(100).forEach { cache.putAll(checkCache(it)) }
        val now = System.currentTimeMillis()
        return confirmed(candidates, cache, now)
    }
    suspend fun recheck(release: CachedRelease): Boolean =
        confirmed(listOf(TorrentCandidate(release.hash, release.name, "")), checkCache(listOf(release.hash)), System.currentTimeMillis()).isNotEmpty()

    companion object {
        const val MAX_CANDIDATES = 200
        internal fun confirmed(candidates: List<TorrentCandidate>, cache: Map<String, CachedDownload>, now: Long): List<CachedRelease> {
            val normalized = cache.mapNotNull { (key, value) ->
                DiscoveryRules.hash(key)?.takeIf { DiscoveryRules.hash(value.hash) == it }?.let { it to value }
            }.toMap()
            return DiscoveryRules.deduplicate(candidates).mapNotNull { candidate ->
                val cached = normalized[candidate.hash] ?: return@mapNotNull null
                val label = candidate.name + " " + candidate.sourceLabel
                CachedRelease(candidate.hash, candidate.name, cached.size?.takeIf { it > 0 },
                    DiscoveryRules.quality(label), DiscoveryRules.codec(label), now)
            }
        }
    }
}
