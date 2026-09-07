package app.jabs.torboxdrop.data

import app.jabs.torboxdrop.model.AccountInfo
import app.jabs.torboxdrop.model.AddOptions
import app.jabs.torboxdrop.model.AddResult
import app.jabs.torboxdrop.model.DownloadFile
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import app.jabs.torboxdrop.model.QueuedDownload
import app.jabs.torboxdrop.util.TorBoxStateMapper
import app.jabs.torboxdrop.util.UrlSafety
import java.io.IOException
import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

enum class TorrentControlOperation(val wireValue: String) {
    REANNOUNCE("reannounce"),
    PAUSE("pause"),
    RESUME("resume"),
    DELETE("delete"),
}

enum class QueueControlOperation(val wireValue: String) {
    START("start"),
    DELETE("delete"),
}

data class CachedDownload(
    val hash: String,
    val name: String? = null,
    val size: Long? = null,
    val files: List<CachedFile> = emptyList(),
)

data class CachedFile(
    val id: Long?,
    val name: String,
    val size: Long? = null,
    val mimeType: String? = null,
)

data class DownloadEdit(
    /** Null means preserve the server value. */
    val name: String? = null,
    /** Null means preserve. An empty list deliberately clears all tags. */
    val tags: List<String>? = null,
    /** Null means preserve the current AirLock state. */
    val airLocked: Boolean? = null,
)

/**
 * Native TorBox Main API client.
 *
 * The default base is HTTPS. Cleartext is accepted only for a loopback host so MockWebServer can
 * exercise the exact authentication boundary. No logging interceptor is installed here.
 */
class TorBoxApiClient(
    private val httpClient: OkHttpClient,
    private val tokenProvider: () -> String?,
    baseUrl: HttpUrl = DEFAULT_BASE_URL,
    relayBaseUrl: HttpUrl = DEFAULT_RELAY_BASE_URL,
) {
    private val apiBaseUrl = normalizeBaseUrl(baseUrl)
    private val relayBaseUrl = normalizeBaseUrl(relayBaseUrl)

    init {
        require(
            apiBaseUrl.isHttps || apiBaseUrl.host == "localhost" || apiBaseUrl.host == "127.0.0.1" ||
                apiBaseUrl.host == "::1",
        ) { "TorBox API base URL must use HTTPS" }
        require(
            this.relayBaseUrl.isHttps || this.relayBaseUrl.host == "localhost" ||
                this.relayBaseUrl.host == "127.0.0.1" || this.relayBaseUrl.host == "::1",
        ) { "TorBox Relay base URL must use HTTPS" }
    }

    suspend fun getCurrentUser(includeSettings: Boolean = false): AccountInfo = withContext(Dispatchers.IO) {
        val envelope = get(
            "user/me",
            query = listOf("settings" to includeSettings.toString()),
        )
        val data = envelope.data as? JsonValue.Object
            ?: throw TorBoxInvalidResponseException("TorBox returned invalid account data.")
        val plan = data["plan"].asIntOrNull()?.let(::planName) ?: data.string("plan")
        AccountInfo(
            userId = data["id"].asStringOrNull()
                ?: data["auth_id"].asStringOrNull()
                ?: data["user_id"].asStringOrNull(),
            email = data.string("email") ?: data.string("base_email"),
            plan = plan,
            subscriptionExpiresAt = parseInstant(data["subscription_expires_at"]),
            premiumExpiresAt = parseInstant(data["premium_expires_at"]),
            totalDownloaded = data.long("total_bytes_downloaded") ?: data.long("total_downloaded"),
            activeTorrentSlots = data.int("active_torrent_slots"),
            activeWebSlots = data.int("active_web_slots"),
            sourceJson = compactObjectJson(
                data,
                "id",
                "created_at",
                "updated_at",
                "plan",
                "customer",
                "is_subscribed",
                "premium_expires_at",
                "cooldown_until",
                "total_bytes_downloaded",
                "total_bytes_uploaded",
                "torrents_downloaded",
                "web_downloads_downloaded",
                "additional_concurrent_slots",
                "long_term_seeding",
                "long_term_storage",
            ),
        )
    }

    suspend fun getDownloads(bypassCache: Boolean = false): List<DownloadItem> =
        withContext(Dispatchers.IO) {
            (getDownloadRecords(DownloadType.TORRENT, bypassCache) +
                getDownloadRecords(DownloadType.WEB, bypassCache))
                .map(ApiDownloadRecord::item)
        }

    suspend fun getTorrentDownloads(bypassCache: Boolean = false): List<DownloadItem> =
        withContext(Dispatchers.IO) {
            getDownloadRecords(DownloadType.TORRENT, bypassCache).map(ApiDownloadRecord::item)
        }

    suspend fun getWebDownloads(bypassCache: Boolean = false): List<DownloadItem> =
        withContext(Dispatchers.IO) {
            getDownloadRecords(DownloadType.WEB, bypassCache).map(ApiDownloadRecord::item)
        }

    suspend fun getDownload(
        type: DownloadType,
        id: String,
        bypassCache: Boolean = false,
    ): DownloadItem? = getDownloadRecord(type, id, bypassCache, includeFiles = false)?.item

    suspend fun getFiles(type: DownloadType, id: String, bypassCache: Boolean = false): List<DownloadFile> =
        getDownloadRecord(type, id, bypassCache, includeFiles = true)?.files
            ?: throw TorBoxApiException(404, "ITEM_NOT_FOUND", "That download no longer exists.")

    suspend fun getQueuedDownloads(bypassCache: Boolean = false): List<QueuedDownload> =
        withContext(Dispatchers.IO) {
            // Deliberately request only the two product types supported by this client.
            val torrents = getQueuedType(DownloadType.TORRENT, "torrent", bypassCache)
            val webDownloads = getQueuedType(DownloadType.WEB, "webdl", bypassCache)
            torrents + webDownloads
        }

    /**
     * Asks TorBox Relay to refresh one torrent's server-side stats before the next `mylist` read.
     * The current official Relay route is credential-free. Never attach the TorBox API token.
     */
    suspend fun requestLiveTorrentUpdate(userId: String, torrentId: String) = withContext(Dispatchers.IO) {
        require(userId.isNotBlank()) { "TorBox user ID is required" }
        require(torrentId.isNotBlank()) { "Torrent ID is required" }
        val url = relayBaseUrl.newBuilder()
            .addPathSegment(userId.trim())
            .addPathSegment(torrentId.trim())
            .build()
        val response = try {
            httpClient.newCall(
                Request.Builder()
                    .url(url)
                    .get()
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-store")
                    .build(),
            ).await()
        } catch (failure: IOException) {
            throw TorBoxOfflineException(failure)
        }
        response.use {
            if (!it.isSuccessful) {
                throw TorBoxApiException(
                    statusCode = it.code,
                    apiCode = "RELAY_UPDATE_FAILED",
                    userDetail = "TorBox could not request a live torrent update.",
                )
            }
        }
    }

    suspend fun createMagnet(magnet: String, options: AddOptions = AddOptions()): AddResult {
        require(magnet.trim().startsWith("magnet:", ignoreCase = true)) { "A magnet URI is required" }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("magnet", magnet.trim())
            .applyTorrentOptions(options)
            .build()
        return createDownload("torrents/createtorrent", body, DownloadType.TORRENT, options)
    }

    suspend fun createTorrent(
        bytes: ByteArray,
        fileName: String,
        options: AddOptions = AddOptions(),
    ): AddResult {
        require(bytes.isNotEmpty()) { "The torrent file is empty" }
        val safeName = fileName.substringAfterLast('/').ifBlank { "upload.torrent" }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                safeName,
                bytes.toRequestBody(TORRENT_MEDIA_TYPE),
            )
            .applyTorrentOptions(options)
            .build()
        return createDownload("torrents/createtorrent", body, DownloadType.TORRENT, options)
    }

    suspend fun createWebDownload(url: String, options: AddOptions = AddOptions()): AddResult {
        val parsed = runCatching { url.trim().toHttpUrl() }.getOrNull()
        require(parsed?.scheme == "https" || parsed?.scheme == "http") { "An HTTP or HTTPS URL is required" }
        val body = FormBody.Builder()
            .add("link", parsed.toString())
            .add("as_queued", options.queued.toString())
            .add("add_only_if_cached", options.cachedOnly.toString())
            .apply { options.customName?.trim()?.takeIf(String::isNotEmpty)?.let { add("name", it) } }
            .build()
        return createDownload("webdl/createwebdownload", body, DownloadType.WEB, options)
    }

    suspend fun controlTorrent(id: String, operation: TorrentControlOperation) {
        require(id.isNotBlank()) { "Torrent ID is required" }
        postJson(
            "torrents/controltorrent",
            Json.obj(
                "torrent_id" to numericIdOrString(id),
                "operation" to Json.string(operation.wireValue),
                "all" to Json.boolean(false),
            ),
        )
    }

    suspend fun deleteWebDownload(id: String) {
        require(id.isNotBlank()) { "Web download ID is required" }
        postJson(
            "webdl/controlwebdownload",
            Json.obj(
                "webdl_id" to numericIdOrString(id),
                "operation" to Json.string("delete"),
                "all" to Json.boolean(false),
            ),
        )
    }

    suspend fun controlQueue(id: String, operation: QueueControlOperation) {
        require(id.isNotBlank()) { "Queued download ID is required" }
        postJson(
            "queued/controlqueued",
            Json.obj(
                "queued_id" to numericIdOrString(id),
                "operation" to Json.string(operation.wireValue),
                "all" to Json.boolean(false),
            ),
        )
    }

    suspend fun editDownload(type: DownloadType, id: String, edit: DownloadEdit): DownloadItem {
        require(id.isNotBlank()) { "Download ID is required" }
        val current = getDownloadRecord(type, id, bypassCache = true, includeFiles = false)
            ?: throw TorBoxApiException(404, "ITEM_NOT_FOUND", "That download no longer exists.")
        val requestedName = edit.name?.trim()?.also { require(it.isNotEmpty()) { "Name cannot be blank" } }
        val name = requestedName ?: current.editable.name
        val tags = (edit.tags ?: current.editable.tags).map(String::trim).filter(String::isNotEmpty).distinct()
        val airLocked = edit.airLocked ?: current.editable.airLocked
        val idField = if (type == DownloadType.TORRENT) "torrent_id" else "webdl_id"
        val path = if (type == DownloadType.TORRENT) "torrents/edittorrent" else "webdl/editwebdownload"
        val body = Json.obj(
            idField to numericIdOrString(id),
            "name" to Json.string(name),
            "tags" to Json.strings(tags),
            "alternative_hashes" to Json.strings(current.editable.alternativeHashes),
            "airlocked" to Json.boolean(airLocked),
        )
        putJson(path, body)
        return current.item.copy(
            name = name ?: current.item.name,
            tags = tags,
            airLocked = airLocked,
            sourceJson = editableSourceJson(name, tags, current.editable.alternativeHashes, airLocked),
        )
    }

    suspend fun requestTemporaryDownloadUrl(
        type: DownloadType,
        id: String,
        fileId: Long? = null,
        zip: Boolean = false,
        appendName: Boolean = false,
    ): String {
        require(id.isNotBlank()) { "Download ID is required" }
        require(fileId != null || zip) { "Choose a file or request the whole-torrent ZIP" }
        val apiToken = requireToken()
        val path = if (type == DownloadType.TORRENT) "torrents/requestdl" else "webdl/requestdl"
        val idName = if (type == DownloadType.TORRENT) "torrent_id" else "web_id"
        val url = endpoint(path).newBuilder()
            .addQueryParameter("token", apiToken)
            .addQueryParameter(idName, id)
            .apply { fileId?.let { addQueryParameter("file_id", it.toString()) } }
            .addQueryParameter("zip_link", zip.toString())
            .addQueryParameter("redirect", "false")
            .addQueryParameter("append_name", appendName.toString())
            .build()
        val envelope = executeEnvelope(
            Request.Builder().url(url).get().header("Accept", "application/json").header("Cache-Control", "no-store"),
            auth = Auth.NONE,
            sanitizingToken = apiToken,
        )
        val temporaryUrl = envelope.data.asStringOrNull()
            ?: (envelope.data as? JsonValue.Object)?.let {
                it.string("url") ?: it.string("download_url") ?: it.string("link")
            }
            ?: throw TorBoxInvalidResponseException("TorBox did not return a temporary download URL.")
        return validateTemporaryUrl(temporaryUrl, apiToken)
    }

    suspend fun checkTorrentCached(
        hashes: Collection<String>,
        includeFiles: Boolean = false,
    ): Map<String, CachedDownload> = checkCached("torrents/checkcached", hashes, includeFiles)

    suspend fun checkWebCached(
        md5Hashes: Collection<String>,
        includeFiles: Boolean = false,
    ): Map<String, CachedDownload> = checkCached(
        "webdl/checkcached",
        md5Hashes.map { it.trim().lowercase(Locale.ROOT) },
        includeFiles,
    )

    internal suspend fun getDownloadRecords(
        type: DownloadType,
        bypassCache: Boolean = false,
    ): List<ApiDownloadRecord> = withContext(Dispatchers.IO) {
        val path = listPath(type)
        val records = LinkedHashMap<String, ApiDownloadRecord>()
        var offset = 0
        repeat(MAX_PAGES) {
            val envelope = get(
                path,
                query = listOf(
                    "bypass_cache" to bypassCache.toString(),
                    "offset" to offset.toString(),
                    "limit" to PAGE_SIZE.toString(),
                ),
                itemNotFoundAsEmpty = true,
            )
            val page = envelope.data.asObjectList()
            page.mapNotNull { parseDownload(type, it, includeFiles = false) }
                .forEach { records["${it.item.type}:${it.item.id}"] = it }
            if (page.size < PAGE_SIZE) return@withContext records.values.toList()
            offset += PAGE_SIZE
        }
        records.values.toList()
    }

    internal suspend fun getDownloadRecord(
        type: DownloadType,
        id: String,
        bypassCache: Boolean,
        includeFiles: Boolean,
    ): ApiDownloadRecord? = withContext(Dispatchers.IO) {
        val envelope = get(
            listPath(type),
            query = listOf(
                "id" to id,
                "bypass_cache" to bypassCache.toString(),
            ),
            itemNotFoundAsEmpty = true,
        )
        envelope.data.asObjectList().firstOrNull()?.let { parseDownload(type, it, includeFiles) }
    }

    private suspend fun getQueuedType(
        type: DownloadType,
        wireType: String,
        bypassCache: Boolean,
    ): List<QueuedDownload> = withContext(Dispatchers.IO) {
        val queued = LinkedHashMap<String, QueuedDownload>()
        var offset = 0
        repeat(MAX_PAGES) {
            val envelope = get(
                "queued/getqueued",
                query = listOf(
                    "type" to wireType,
                    "bypass_cache" to bypassCache.toString(),
                    "offset" to offset.toString(),
                    "limit" to PAGE_SIZE.toString(),
                ),
                itemNotFoundAsEmpty = true,
            )
            val page = envelope.data.asObjectList()
            page.mapNotNull { parseQueue(type, it) }.forEach { queued["${it.type}:${it.id}"] = it }
            if (page.size < PAGE_SIZE) return@withContext queued.values.toList()
            offset += PAGE_SIZE
        }
        queued.values.toList()
    }

    private fun parseDownload(
        type: DownloadType,
        source: JsonValue.Object,
        includeFiles: Boolean,
    ): ApiDownloadRecord? {
        val id = source["id"].asStringOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val rawName = source.string("name")?.trim()?.takeIf(String::isNotEmpty)
        val hash = source.string("hash")?.trim()?.takeIf(String::isNotEmpty)
        val rawState = source.string("download_state") ?: source.string("state") ?: ""
        val downloadFinished = source.boolean("download_finished") ?: false
        val downloadPresent = source.boolean("download_present") ?: false
        val tags = source.array("tags").stringValues()
        val alternativeHashes = source.array("alternative_hashes").stringValues()
        val airLocked = source.boolean("airlocked") ?: false
        val fileArray = source.array("files")
        val files = if (includeFiles) {
            fileArray?.values.orEmpty().mapIndexedNotNull { index, value ->
                (value as? JsonValue.Object)?.let { parseFile(type, id, it, index) }
            }
        } else {
            emptyList()
        }
        val name = rawName ?: hash ?: "Unnamed ${if (type == DownloadType.TORRENT) "torrent" else "web download"}"
        val error = source["error"].asStringOrNull()
            ?: source.obj("error")?.let { it.string("detail") ?: it.string("message") }
        val sanitizingToken = tokenProvider()?.trim()?.takeIf(String::isNotEmpty)
        val item = DownloadItem(
            id = id,
            type = type,
            name = name,
            rawState = rawState,
            friendlyState = TorBoxStateMapper.friendly(rawState, downloadFinished, downloadPresent),
            progress = source.double("progress")?.takeIf(Double::isFinite)?.coerceAtLeast(0.0),
            totalSize = source.long("size")?.takeIf { it >= 0 },
            downloadedBytes = source.long("total_downloaded")?.takeIf { it >= 0 },
            downloadSpeed = source.long("download_speed")?.takeIf { it >= 0 },
            uploadSpeed = source.long("upload_speed")?.takeIf { it >= 0 },
            etaSeconds = source.long("eta")?.takeIf { it >= 0 },
            seeds = source.int("seeds")?.takeIf { it >= 0 },
            peers = source.int("peers")?.takeIf { it >= 0 },
            ratio = source.double("ratio")?.takeIf { it >= 0 },
            availability = source.double("availability")?.takeIf { it >= 0 },
            createdAt = parseInstant(source["created_at"]),
            updatedAt = parseInstant(source["updated_at"]),
            cachedAt = parseInstant(source["cached_at"]),
            expiresAt = parseInstant(source["expires_at"]),
            tags = tags,
            airLocked = airLocked,
            downloadFinished = downloadFinished,
            downloadPresent = downloadPresent,
            cached = source.boolean("cached") ?: false,
            privateTorrent = if (type == DownloadType.TORRENT) source.boolean("private") else null,
            hash = hash,
            trackerMessage = TorBoxErrorSanitizer.sanitize(
                source.string("tracker_message") ?: source.string("tracker"),
                sanitizingToken,
            ),
            originalSource = if (type == DownloadType.TORRENT) {
                source.string("magnet")
            } else {
                source.string("original_url")
            },
            fileCount = fileArray?.values?.size ?: source.int("file_count"),
            allowZip = if (type == DownloadType.TORRENT) source.boolean("allow_zipped") else null,
            error = TorBoxErrorSanitizer.sanitize(error, sanitizingToken),
            // Store only editable metadata, not the potentially enormous file array.
            sourceJson = editableSourceJson(rawName, tags, alternativeHashes, airLocked),
        )
        return ApiDownloadRecord(
            item = item,
            files = files,
            editable = EditableState(rawName, tags, alternativeHashes, airLocked),
        )
    }

    private fun parseFile(
        type: DownloadType,
        downloadId: String,
        source: JsonValue.Object,
        index: Int,
    ): DownloadFile? {
        val fileId = source.long("id") ?: return null
        val fullName = source.string("name")?.trim()?.takeIf(String::isNotEmpty)
        val shortName = source.string("short_name")?.trim()?.takeIf(String::isNotEmpty)
        val absolutePath = source.string("absolute_path")?.trim()?.takeIf(String::isNotEmpty)
        val displayName = shortName ?: fullName?.substringAfterLast('/') ?: absolutePath?.substringAfterLast('/')
            ?: "File ${index + 1}"
        val path = absolutePath ?: fullName?.takeIf { it != displayName }
        return DownloadFile(
            id = fileId,
            downloadId = downloadId,
            downloadType = type,
            name = displayName,
            path = path,
            size = source.long("size")?.takeIf { it >= 0 },
            mimeType = source.string("mimetype") ?: source.string("mime_type"),
            infected = source.boolean("infected") ?: false,
        )
    }

    private fun parseQueue(type: DownloadType, source: JsonValue.Object): QueuedDownload? {
        val id = source["id"].asStringOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val rawSource = if (type == DownloadType.TORRENT) {
            source.string("magnet") ?: source.string("torrent_file")
        } else {
            source.string("link") ?: source.string("original_url") ?: source.string("url")
        }
        return QueuedDownload(
            id = id,
            type = type,
            name = source.string("name_override")?.takeIf(String::isNotBlank)
                ?: source.string("name")?.takeIf(String::isNotBlank)
                ?: source.string("hash")
                ?: "Queued ${if (type == DownloadType.TORRENT) "torrent" else "web download"}",
            queuedAt = parseInstant(source["created_at"] ?: source["queued_at"]),
            source = rawSource,
            sourceJson = compactObjectJson(
                source,
                "id",
                "created_at",
                "hash",
                "name",
                "name_override",
                "type",
                "seed_torrent_override",
                "magnet",
                "torrent_file",
                "link",
                "original_url",
            ),
        )
    }

    private suspend fun createDownload(
        path: String,
        body: RequestBody,
        type: DownloadType,
        options: AddOptions,
    ): AddResult = withContext(Dispatchers.IO) {
        val envelope = executeEnvelope(Request.Builder().url(endpoint(path)).post(body))
        val data = envelope.data as? JsonValue.Object
        val activeId = when (type) {
            DownloadType.TORRENT -> data?.get("torrent_id").asStringOrNull()
            DownloadType.WEB -> data?.get("webdownload_id").asStringOrNull()
        }
        val queuedId = data?.get("queued_id").asStringOrNull()
        AddResult(
            id = activeId,
            queuedId = queuedId,
            sourceHash = data?.get("hash").asStringOrNull(),
            name = options.customName?.trim()?.takeIf(String::isNotEmpty),
            detail = envelope.detail ?: if (options.queued) "Added to the TorBox queue." else "Added to TorBox.",
            queued = queuedId != null || options.queued,
        )
    }

    private suspend fun checkCached(
        path: String,
        rawHashes: Collection<String>,
        includeFiles: Boolean,
    ): Map<String, CachedDownload> = withContext(Dispatchers.IO) {
        val hashes = rawHashes.map(String::trim).filter(String::isNotEmpty).distinct()
        if (hashes.isEmpty()) return@withContext emptyMap()
        val body = Json.obj("hashes" to Json.strings(hashes))
        val envelope = postJson(
            path,
            body,
            query = listOf(
                "format" to "object",
                "list_files" to includeFiles.toString(),
            ),
        )
        val result = linkedMapOf<String, CachedDownload>()
        when (val data = envelope.data) {
            is JsonValue.Object -> data.values.forEach { (key, value) ->
                val entry = value as? JsonValue.Object ?: return@forEach
                parseCached(entry, fallbackHash = key)?.let { result[it.hash] = it }
            }
            is JsonValue.Array -> data.values.forEach { value ->
                parseCached(value as? JsonValue.Object ?: return@forEach, fallbackHash = null)
                    ?.let { result[it.hash] = it }
            }
            else -> Unit
        }
        result
    }

    private fun parseCached(source: JsonValue.Object, fallbackHash: String?): CachedDownload? {
        val hash = source.string("hash") ?: fallbackHash ?: return null
        val files = source.array("files")?.values.orEmpty().mapNotNull { value ->
            val file = value as? JsonValue.Object ?: return@mapNotNull null
            CachedFile(
                id = file.long("id"),
                name = file.string("short_name") ?: file.string("name") ?: return@mapNotNull null,
                size = file.long("size"),
                mimeType = file.string("mimetype"),
            )
        }
        return CachedDownload(
            hash = hash,
            name = source.string("name"),
            size = source.long("size"),
            files = files,
        )
    }

    private suspend fun get(
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        itemNotFoundAsEmpty: Boolean = false,
    ): ApiEnvelope {
        val url = endpoint(path).newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        return executeEnvelope(
            Request.Builder().url(url).get().header("Accept", "application/json"),
            itemNotFoundAsEmpty = itemNotFoundAsEmpty,
        )
    }

    private suspend fun postJson(
        path: String,
        body: JsonValue.Object,
        query: List<Pair<String, String>> = emptyList(),
    ): ApiEnvelope {
        val url = endpoint(path).newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        return executeEnvelope(
            Request.Builder()
                .url(url)
                .post(Json.stringify(body).toRequestBody(JSON_MEDIA_TYPE))
                .header("Accept", "application/json"),
        )
    }

    private suspend fun putJson(path: String, body: JsonValue.Object): ApiEnvelope = executeEnvelope(
        Request.Builder()
            .url(endpoint(path))
            .put(Json.stringify(body).toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json"),
    )

    private suspend fun executeEnvelope(
        requestBuilder: Request.Builder,
        auth: Auth = Auth.BEARER,
        sanitizingToken: String? = null,
        itemNotFoundAsEmpty: Boolean = false,
    ): ApiEnvelope {
        val apiToken = when (auth) {
            Auth.BEARER -> requireToken()
            Auth.NONE -> sanitizingToken
        }
        if (auth == Auth.BEARER) requestBuilder.header("Authorization", "Bearer $apiToken")
        // Do not allow intermediary caches to retain authenticated API material.
        requestBuilder.header("Cache-Control", "no-store")
        val response = try {
            httpClient.newCall(requestBuilder.build()).await()
        } catch (failure: IOException) {
            throw TorBoxOfflineException(failure)
        }
        response.use {
            val parsed = withContext(Dispatchers.IO) {
                val text = it.body?.string().orEmpty()
                ParsedBody(
                    wasBlank = text.isBlank(),
                    envelope = parseEnvelopeOrNull(text, apiToken),
                )
            }
            val envelope = parsed.envelope
            val apiCode = envelope?.error?.uppercase(Locale.ROOT)
            if (it.code == 429) {
                val retryAfter = parseRetryAfter(it.header("Retry-After"))
                val message = envelope?.detail ?: "TorBox is rate limiting requests. Try again shortly."
                throw TorBoxRateLimitException(retryAfter, message)
            }
            if (it.code >= 500) {
                throw TorBoxApiException(
                    statusCode = it.code,
                    apiCode = apiCode,
                    userDetail = envelope?.detail ?: defaultErrorMessage(it.code, apiCode),
                )
            }
            if (itemNotFoundAsEmpty && apiCode == "ITEM_NOT_FOUND") {
                return (envelope ?: ApiEnvelope()).copy(
                    success = true,
                    error = null,
                    data = JsonValue.Array(emptyList()),
                )
            }
            if (it.code == 401 || apiCode == "BAD_TOKEN" || apiCode == "NO_AUTH") {
                throw TorBoxBadTokenException(
                    apiCode = apiCode ?: "NO_AUTH",
                    statusCode = it.code,
                    message = envelope?.detail ?: "The TorBox API token is invalid or expired.",
                )
            }
            if (!it.isSuccessful) {
                throw TorBoxApiException(
                    statusCode = it.code,
                    apiCode = apiCode,
                    userDetail = envelope?.detail ?: defaultErrorMessage(it.code, apiCode),
                )
            }
            if (!parsed.wasBlank && envelope == null) {
                throw TorBoxInvalidResponseException("TorBox returned an unreadable response.")
            }
            val successfulEnvelope = envelope ?: ApiEnvelope(success = true)
            if (successfulEnvelope.success == false || !successfulEnvelope.error.isNullOrBlank()) {
                throw TorBoxApiException(
                    statusCode = it.code,
                    apiCode = apiCode,
                    userDetail = successfulEnvelope.detail ?: defaultErrorMessage(it.code, apiCode),
                )
            }
            return successfulEnvelope
        }
    }

    private fun parseEnvelopeOrNull(source: String, token: String?): ApiEnvelope? {
        if (source.isBlank()) return null
        val root = try {
            Json.parse(source) as? JsonValue.Object
        } catch (_: JsonParseException) {
            null
        } ?: return null
        val error = TorBoxErrorSanitizer.sanitize(root["error"].asStringOrNull(), token)
        val rawDetail = root["detail"].asStringOrNull()
            ?: root["message"].asStringOrNull()
            ?: root.obj("detail")?.let { it.string("message") ?: it.string("detail") }
        return ApiEnvelope(
            success = root.boolean("success"),
            error = error,
            detail = TorBoxErrorSanitizer.sanitize(rawDetail, token),
            data = root["data"],
        )
    }

    private fun requireToken(): String = tokenProvider()?.trim()?.takeIf(String::isNotEmpty)
        ?: throw TorBoxMissingTokenException()

    private fun endpoint(path: String): HttpUrl = apiBaseUrl.resolve(path.trimStart('/'))
        ?: throw IllegalArgumentException("Invalid TorBox API path")

    private fun validateTemporaryUrl(rawUrl: String, apiToken: String): String {
        val candidate = rawUrl.trim()
        val url = runCatching { candidate.toHttpUrl() }.getOrNull()
            ?: throw TorBoxUnsafeDownloadUrlException("TorBox returned an invalid temporary download URL.")
        if (!url.isHttps) {
            throw TorBoxUnsafeDownloadUrlException("TorBox returned a non-HTTPS temporary download URL.")
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw TorBoxUnsafeDownloadUrlException("TorBox returned a URL containing user credentials.")
        }
        val normalizedHost = url.host.trimEnd('.').lowercase(Locale.ROOT)
        val configuredApiHost = apiBaseUrl.host.trimEnd('.').lowercase(Locale.ROOT)
        val isConfiguredApiOrigin = normalizedHost == configuredApiHost && url.port == apiBaseUrl.port
        if (normalizedHost == DEFAULT_BASE_URL.host || isConfiguredApiOrigin) {
            throw TorBoxUnsafeDownloadUrlException("TorBox returned an API URL instead of a temporary CDN URL.")
        }
        val decodedCandidates = generateSequence(candidate) { previous ->
            runCatching { URLDecoder.decode(previous, Charsets.UTF_8.name()) }
                .getOrNull()
                ?.takeUnless { it == previous }
        }.take(MAX_DECODE_PASSES).toList()
        val encodedToken = java.net.URLEncoder.encode(apiToken, Charsets.UTF_8.name())
        val containsCredential = decodedCandidates.any { it.contains(apiToken) || it.contains(encodedToken) }
        if (containsCredential && !UrlSafety.isExpectedTorBoxCredentialDownloadUrl(candidate, apiToken)) {
            throw TorBoxUnsafeDownloadUrlException("TorBox returned a URL containing the API credential.")
        }
        return url.toString()
    }

    private fun MultipartBody.Builder.applyTorrentOptions(options: AddOptions): MultipartBody.Builder = apply {
        require(options.seed in 1..3) { "Torrent seeding preference must be Auto, Always, or Never" }
        addFormDataPart("seed", options.seed.toString())
        addFormDataPart("allow_zip", options.allowZip.toString())
        addFormDataPart("as_queued", options.queued.toString())
        addFormDataPart("add_only_if_cached", options.cachedOnly.toString())
        options.customName?.trim()?.takeIf(String::isNotEmpty)?.let { addFormDataPart("name", it) }
    }

    private fun parseInstant(value: JsonValue?): Instant? {
        value.asLongOrNull()?.let { numeric ->
            return runCatching {
                if (numeric.absoluteValueSafe() < EPOCH_MILLIS_THRESHOLD) {
                    Instant.ofEpochSecond(numeric)
                } else {
                    Instant.ofEpochMilli(numeric)
                }
            }.getOrNull()
        }
        val text = value.asStringOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) }.getOrNull()
    }

    private fun parseRetryAfter(value: String?): Long? {
        val trimmed = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        trimmed.toLongOrNull()?.let { return it.coerceAtLeast(0) }
        return runCatching {
            val retryAt = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            (retryAt.epochSecond - Instant.now().epochSecond).coerceAtLeast(0)
        }.getOrNull()
    }

    private fun Long.absoluteValueSafe(): Long = if (this == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(this)

    private fun editableSourceJson(
        name: String?,
        tags: List<String>,
        alternativeHashes: List<String>,
        airLocked: Boolean,
    ): String = Json.stringify(
        Json.obj(
            "name" to Json.string(name),
            "tags" to Json.strings(tags),
            "alternative_hashes" to Json.strings(alternativeHashes),
            "airlocked" to Json.boolean(airLocked),
        ),
    )

    private fun compactObjectJson(source: JsonValue.Object, vararg names: String): String {
        val selected = linkedMapOf<String, JsonValue>()
        names.forEach { name -> source[name]?.let { selected[name] = it } }
        return Json.stringify(JsonValue.Object(selected))
    }

    private fun numericIdOrString(id: String): JsonValue = id.toLongOrNull()?.let {
        JsonValue.NumberValue(it.toString())
    } ?: JsonValue.StringValue(id)

    private fun listPath(type: DownloadType): String = when (type) {
        DownloadType.TORRENT -> "torrents/mylist"
        DownloadType.WEB -> "webdl/mylist"
    }

    private fun defaultErrorMessage(status: Int, code: String?): String = when {
        code == "AUTH_ERROR" -> "TorBox could not verify the request. Try again shortly."
        code == "ITEM_NOT_FOUND" -> "That TorBox item no longer exists."
        code == "UNSUPPORTED_SITE" -> "TorBox does not support that web host."
        code == "DOWNLOAD_NOT_CACHED" -> "That download is not currently cached by TorBox."
        code == "PLAN_RESTRICTED_FEATURE" -> "This feature is not available on the current TorBox plan."
        status >= 500 -> "TorBox is temporarily unavailable. Try again shortly."
        else -> "TorBox rejected the request${code?.let { " ($it)" }.orEmpty()}."
    }

    private fun planName(plan: Int): String = when (plan) {
        0 -> "Free"
        1 -> "Essential"
        2 -> "Pro"
        3 -> "Standard"
        else -> "Plan $plan"
    }

    private fun normalizeBaseUrl(baseUrl: HttpUrl): HttpUrl = baseUrl.newBuilder().apply {
        if (!baseUrl.encodedPath.endsWith('/')) addPathSegment("")
    }.build()

    private enum class Auth { BEARER, NONE }

    internal data class ApiDownloadRecord(
        val item: DownloadItem,
        val files: List<DownloadFile>,
        val editable: EditableState,
    )

    internal data class EditableState(
        val name: String?,
        val tags: List<String>,
        val alternativeHashes: List<String>,
        val airLocked: Boolean,
    )

    private data class ApiEnvelope(
        val success: Boolean? = null,
        val error: String? = null,
        val detail: String? = null,
        val data: JsonValue? = null,
    )

    private data class ParsedBody(
        val wasBlank: Boolean,
        val envelope: ApiEnvelope?,
    )

    companion object {
        val DEFAULT_BASE_URL: HttpUrl = "https://api.torbox.app/v1/api/".toHttpUrl()
        val DEFAULT_RELAY_BASE_URL: HttpUrl =
            "https://relay.torbox.app/v1/inactivecheck/torrent/".toHttpUrl()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val TORRENT_MEDIA_TYPE = "application/x-bittorrent".toMediaType()
        private const val PAGE_SIZE = 1_000
        private const val MAX_PAGES = 100
        private const val MAX_DECODE_PASSES = 4
        private const val EPOCH_MILLIS_THRESHOLD = 100_000_000_000L
    }
}

private fun JsonValue?.asObjectList(): List<JsonValue.Object> = when (this) {
    is JsonValue.Object -> listOf(this)
    is JsonValue.Array -> values.mapNotNull { it as? JsonValue.Object }
    else -> emptyList()
}

private fun JsonValue.Array?.stringValues(): List<String> = this?.values.orEmpty()
    .mapNotNull { it.asStringOrNull() }
    .map(String::trim)
    .filter(String::isNotEmpty)

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) {
                    continuation.resume(response)
                } else {
                    response.close()
                }
            }
        },
    )
}
