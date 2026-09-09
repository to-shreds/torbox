package app.jabs.torboxdrop.drive

import app.jabs.torboxdrop.data.Json
import app.jabs.torboxdrop.data.JsonValue
import java.io.IOException
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

data class GoogleDriveFolder(val id: String, val name: String)

/** Only folder metadata crosses this boundary. Media bytes never pass through Android. */
class GoogleDriveApiClient(
    httpClient: OkHttpClient,
    baseUrl: HttpUrl = DEFAULT_BASE_URL,
) {
    private val httpClient = httpClient.newBuilder().retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build()
    private val apiBaseUrl = baseUrl.newBuilder().apply {
        if (!baseUrl.encodedPath.endsWith('/')) addPathSegment("")
    }.build()

    init {
        require(apiBaseUrl.isHttps || apiBaseUrl.host in setOf("localhost", "127.0.0.1", "::1")) {
            "Google Drive API base URL must use HTTPS"
        }
    }

    /** Persist this ID before calling ensureDestinationFolder, including across process death. */
    suspend fun generateFolderId(accessToken: String): String {
        val url = apiBaseUrl.newBuilder().addPathSegments("files/generateIds")
            .addQueryParameter("count", "1").addQueryParameter("space", "drive").build()
        val root = execute(Request.Builder().url(url).get(), accessToken)
        val id = (root.array("ids")?.values?.singleOrNull() as? JsonValue.StringValue)?.value
            ?: throw GoogleDriveApiException("Google Drive did not return a reserved folder ID.")
        requireFolderId(id)
        return id
    }

    suspend fun requireWritableFolder(id: String, accessToken: String): GoogleDriveFolder {
        requireFolderId(id)
        val url = apiBaseUrl.newBuilder().addPathSegment("files").addPathSegment(id)
            .addQueryParameter("fields", "id,name,mimeType,trashed,capabilities(canAddChildren)").build()
        val root = execute(Request.Builder().url(url).get(), accessToken)
        if (root.string("id") != id || root.string("mimeType") != FOLDER_MIME_TYPE ||
            root.boolean("trashed") != false || root.obj("capabilities")?.boolean("canAddChildren") != true
        ) throw GoogleDriveApiException("This Google account cannot upload into the saved Drive folder.", 403)
        return GoogleDriveFolder(id, root.string("name") ?: "TorBox Drop")
    }

    suspend fun ensureDestinationFolder(id: String, name: String, accessToken: String): GoogleDriveFolder {
        requireFolderId(id)
        val normalized = name.trim().takeIf(String::isNotEmpty)?.take(200)
            ?: throw IllegalArgumentException("Enter a Google Drive folder name.")
        try {
            return requireWritableFolder(id, accessToken)
        } catch (error: GoogleDriveApiException) {
            if (error.statusCode != 404) throw error
        }
        val body = Json.stringify(Json.obj("id" to Json.string(id), "name" to Json.string(normalized),
            "mimeType" to Json.string(FOLDER_MIME_TYPE)))
        val url = apiBaseUrl.newBuilder().addPathSegment("files").addQueryParameter("fields", "id,name").build()
        try {
            val root = execute(Request.Builder().url(url).post(driveJsonBody(body)), accessToken)
            if (root.string("id") != id) throw GoogleDriveApiException("Google Drive returned a different folder ID.")
        } catch (error: GoogleDriveApiException) {
            // A previous create may have succeeded before its response was lost. Reuse its ID.
            if (error.statusCode != 409) throw error
        }
        return requireWritableFolder(id, accessToken)
    }

    private suspend fun execute(builder: Request.Builder, token: String): JsonValue.Object {
        require(token.isNotBlank()) { "Google Drive authorization is required." }
        val response = try {
            httpClient.newCall(builder.header("Authorization", "Bearer $token")
                .header("Accept", "application/json").header("Cache-Control", "no-store").build()).awaitDriveApi()
        } catch (_: IOException) {
            throw GoogleDriveApiException("Google Drive could not be reached. No new folder ID will be generated on retry.")
        }
        response.use {
            if (!it.isSuccessful) throw GoogleDriveApiException(
                when (it.code) {
                    401, 403, 404 -> "This Google account cannot access the saved Drive folder. Reconnect or choose a new destination."
                    429 -> "Google Drive is rate limiting requests. Try again shortly."
                    else -> "Google Drive rejected the folder operation (HTTP ${it.code})."
                }, it.code,
            )
            val body = withContext(Dispatchers.IO) { it.body?.string().orEmpty() }
            return runCatching { Json.parse(body) as? JsonValue.Object }.getOrNull()
                ?: throw GoogleDriveApiException("Google Drive returned an unreadable folder response.")
        }
    }

    private fun requireFolderId(id: String) {
        require(Regex("[A-Za-z0-9_-]{1,200}").matches(id)) { "Invalid Google Drive folder ID." }
    }

    companion object {
        val DEFAULT_BASE_URL: HttpUrl = "https://www.googleapis.com/drive/v3/".toHttpUrl()
        private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
    }
}

class GoogleDriveApiException(message: String, val statusCode: Int? = null) : Exception(message)

private suspend fun Call.awaitDriveApi(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}
