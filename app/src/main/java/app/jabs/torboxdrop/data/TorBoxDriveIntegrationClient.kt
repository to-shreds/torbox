package app.jabs.torboxdrop.data

import app.jabs.torboxdrop.drive.TorBoxIntegrationJob
import app.jabs.torboxdrop.drive.driveJsonBody
import java.io.IOException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

interface TorBoxDriveGateway {
    suspend fun updateGoogleDriveFolderId(folderId: String?)
    suspend fun queueGoogleDrive(torrentId: String, fileId: Long, googleAccessToken: String)
    suspend fun getJobsByHash(hash: String): List<TorBoxIntegrationJob>
    suspend fun getJob(jobId: Long): TorBoxIntegrationJob?
}

/** Narrow TorBox boundary for the documented cloud-integration endpoints. */
class TorBoxDriveIntegrationClient(
    httpClient: OkHttpClient,
    private val tokenProvider: () -> String?,
    baseUrl: HttpUrl = DEFAULT_BASE_URL,
) : TorBoxDriveGateway {
    private val httpClient = httpClient.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()

    private val apiBaseUrl = baseUrl.newBuilder().apply {
        if (!baseUrl.encodedPath.endsWith('/')) addPathSegment("")
    }.build()

    init {
        require(
            apiBaseUrl.isHttps || apiBaseUrl.host in setOf("localhost", "127.0.0.1", "::1"),
        ) { "TorBox API base URL must use HTTPS" }
    }

    /** Updates TorBox's documented account-level destination folder for Drive integrations. */
    override suspend fun updateGoogleDriveFolderId(folderId: String?) {
        val normalized = folderId?.trim()?.takeIf(String::isNotEmpty)
        execute(
            Request.Builder()
                .url(endpoint("user/settings/editsettings"))
                .put(
                    Json.stringify(
                        Json.obj(
                            "google_drive_folder_id" to (normalized?.let(JsonValue::StringValue) ?: JsonValue.Null),
                        ),
                    ).toRequestBody(JSON_MEDIA_TYPE),
                )
                .header("Accept", "application/json"),
        )
    }

    /**
     * Queues one individual torrent file for TorBox's server-side Google Drive uploader.
     * The Google bearer token is sent only in this POST body and is never retained by this client.
     */
    override suspend fun queueGoogleDrive(torrentId: String, fileId: Long, googleAccessToken: String) {
        val numericTorrentId = torrentId.toLongOrNull()
            ?: throw IllegalArgumentException("TorBox torrent ID is not numeric.")
        require(numericTorrentId >= 0) { "TorBox torrent ID must be non-negative." }
        require(fileId >= 0) { "TorBox file ID must be non-negative." }
        require(googleAccessToken.isNotBlank()) { "Google Drive access token is required." }
        val body = Json.obj(
            "id" to Json.number(numericTorrentId),
            "file_id" to Json.number(fileId),
            "zip" to Json.boolean(false),
            "type" to Json.string("torrent"),
            "google_token" to Json.string(googleAccessToken),
        )
        execute(
            Request.Builder()
                .url(endpoint("integration/googledrive"))
                .post(driveJsonBody(Json.stringify(body)))
                .header("Accept", "application/json"),
            extraSecrets = listOf(googleAccessToken),
        )
    }

    override suspend fun getJobsByHash(hash: String): List<TorBoxIntegrationJob> {
        require(Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}").matches(hash)) { "Invalid torrent hash." }
        val envelope = execute(
            Request.Builder()
                .url(endpoint("integration/jobs/${hash.trim()}"))
                .get()
                .header("Accept", "application/json"),
        )
        val objects = when (val data = envelope.data) {
            is JsonValue.Array -> data.values.map { it as? JsonValue.Object
                ?: throw TorBoxInvalidResponseException("Invalid integration job entry.") }
            else -> throw TorBoxInvalidResponseException("TorBox did not return an integration job list.")
        }
        return objects.map(::parseJob)
    }

    override suspend fun getJob(jobId: Long): TorBoxIntegrationJob? {
        require(jobId >= 0) { "Invalid integration job ID." }
        val envelope = execute(
            Request.Builder()
                .url(endpoint("integration/job/$jobId"))
                .get()
                .header("Accept", "application/json"),
        )
        val objectValue = envelope.data as? JsonValue.Object ?: return null
        return parseJob(objectValue)
    }

    private suspend fun execute(
        requestBuilder: Request.Builder,
        extraSecrets: List<String> = emptyList(),
    ): ApiEnvelope {
        val apiToken = requireToken()
        requestBuilder
            .header("Authorization", "Bearer $apiToken")
            .header("Cache-Control", "no-store")
        val response = try {
            httpClient.newCall(requestBuilder.build()).awaitDrive()
        } catch (failure: IOException) {
            throw TorBoxOfflineException(failure)
        }
        response.use {
            val text = withContext(Dispatchers.IO) { it.body?.string().orEmpty() }
            val envelope = parseEnvelope(text, listOf(apiToken) + extraSecrets)
            val apiCode = envelope?.error?.uppercase(Locale.ROOT)
                ?.takeIf { it in setOf("BAD_TOKEN", "NO_AUTH") }
            if (it.code == 429) {
                throw TorBoxRateLimitException(
                    retryAfterSeconds = it.header("Retry-After")?.toLongOrNull(),
                    message = "TorBox is rate limiting requests. Try again shortly.",
                )
            }
            if ((it.code == 401 && envelope?.error.isNullOrBlank()) || apiCode == "BAD_TOKEN" || apiCode == "NO_AUTH") {
                throw TorBoxBadTokenException(
                    apiCode = apiCode ?: "NO_AUTH",
                    statusCode = it.code,
                    message = "The TorBox API token is invalid or expired.",
                )
            }
            if (!it.isSuccessful || envelope?.success == false || !envelope?.error.isNullOrBlank()) {
                throw TorBoxApiException(
                    statusCode = it.code,
                    apiCode = apiCode,
                    userDetail = "TorBox rejected the Google Drive integration request (HTTP ${it.code}).",
                )
            }
            if (text.isNotBlank() && (envelope == null || envelope.success != true)) {
                throw TorBoxInvalidResponseException("TorBox returned an unreadable integration response.")
            }
            return envelope ?: ApiEnvelope(success = true)
        }
    }

    private fun parseEnvelope(source: String, secrets: List<String>): ApiEnvelope? {
        if (source.isBlank()) return null
        val root = runCatching { Json.parse(source) as? JsonValue.Object }.getOrNull() ?: return null
        return ApiEnvelope(
            success = root.boolean("success"),
            error = sanitize(root["error"].asStringOrNull(), secrets),
            detail = sanitize(
                root["detail"].asStringOrNull() ?: root["message"].asStringOrNull(),
                secrets,
            ),
            data = root["data"],
        )
    }

    private fun parseJob(source: JsonValue.Object): TorBoxIntegrationJob = TorBoxIntegrationJob(
        id = source.long("id"),
        fileId = source.long("file_id"),
        hash = source.string("hash"),
        integration = source.string("integration"),
        progress = source.double("progress"),
        status = source.string("status"),
        type = source.string("type"),
        // Job details are untrusted and can echo Google credentials from older requests.
        detail = null,
        createdAt = parseInstant(source.string("created_at")),
        updatedAt = parseInstant(source.string("updated_at")),
        zip = source.boolean("zip") ?: false,
    )

    private fun sanitize(value: String?, secrets: Collection<String>): String? {
        var safe = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        safe = Regex("(?i)\\bbearer\\s+[^\\s,;]+").replace(safe, "Bearer [redacted]")
        safe = Regex("(?i)([?&](?:token|api[_-]?key)=)[^&#\\s]+").replace(safe) {
            "${it.groupValues[1]}[redacted]"
        }
        secrets.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .sortedByDescending(String::length)
            .forEach { secret -> safe = safe.replace(secret, "[redacted]", ignoreCase = false) }
        return safe.take(500)
    }

    private fun parseInstant(value: String?): Instant? {
        val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return runCatching { Instant.parse(text) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
    }

    private fun requireToken(): String = tokenProvider()?.trim()?.takeIf(String::isNotEmpty)
        ?: throw TorBoxMissingTokenException()

    private fun endpoint(path: String): HttpUrl = apiBaseUrl.resolve(path.trimStart('/'))
        ?: throw IllegalArgumentException("Invalid TorBox integration API path")

    private data class ApiEnvelope(
        val success: Boolean? = null,
        val error: String? = null,
        val detail: String? = null,
        val data: JsonValue? = null,
    )

    companion object {
        val DEFAULT_BASE_URL: HttpUrl = "https://api.torbox.app/v1/api/".toHttpUrl()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private fun JsonValue?.asObjects(): List<JsonValue.Object> = when (this) {
    is JsonValue.Object -> listOf(this)
    is JsonValue.Array -> values.mapNotNull { it as? JsonValue.Object }
    else -> emptyList()
}

private suspend fun Call.awaitDrive(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        },
    )
}
