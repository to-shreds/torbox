package app.jabs.torboxdrop.drive

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

data class GoogleDriveFolder(val id: String, val name: String)

/**
 * Minimal Google Drive API client used only to create a user-named destination folder.
 *
 * The app deliberately requests only drive.file. Creating the folder with this OAuth client makes
 * that folder user-authorized for the same short-lived token TorBox receives for its upload job,
 * without requesting broad access to the user's existing Drive contents.
 */
class GoogleDriveApiClient(private val httpClient: OkHttpClient) {
    suspend fun createDestinationFolder(name: String, accessToken: String): GoogleDriveFolder {
        val normalized = name.trim().takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("Enter a Google Drive folder name.")
        require(accessToken.isNotBlank()) { "Google Drive access token is required." }
        val body = JSONObject()
            .put("name", normalized.take(MAX_FOLDER_NAME_LENGTH))
            .put("mimeType", FOLDER_MIME_TYPE)
            .toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?fields=id%2Cname")
            .post(body)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .build()
        return execute(request, accessToken) { source ->
            val json = JSONObject(source)
            val id = json.optString("id").trim().takeIf(String::isNotEmpty)
                ?: throw GoogleDriveApiException("Google Drive did not return the new folder ID.")
            GoogleDriveFolder(id = id, name = json.optString("name", normalized).ifBlank { normalized })
        }
    }

    private suspend fun <T> execute(
        request: Request,
        accessToken: String,
        parse: (String) -> T,
    ): T {
        val response = try {
            httpClient.newCall(request).awaitDriveApi()
        } catch (_: IOException) {
            throw GoogleDriveApiException("Google Drive could not be reached.")
        }
        response.use {
            val body = withContext(Dispatchers.IO) { it.body?.string().orEmpty() }
            if (!it.isSuccessful) {
                val safe = sanitizeGoogleError(body, accessToken)
                throw GoogleDriveApiException(
                    when (it.code) {
                        401, 403 -> "Google Drive authorization does not allow that folder operation."
                        429 -> "Google Drive is rate limiting requests. Try again shortly."
                        else -> safe ?: "Google Drive rejected the folder operation."
                    },
                )
            }
            return try {
                parse(body)
            } catch (error: GoogleDriveApiException) {
                throw error
            } catch (_: Exception) {
                throw GoogleDriveApiException("Google Drive returned an unreadable folder response.")
            }
        }
    }

    private fun sanitizeGoogleError(body: String, token: String): String? {
        var value = runCatching {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message")
        }.getOrNull()?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (token.isNotEmpty()) value = value.replace(token, "[redacted]", ignoreCase = false)
        value = Regex("(?i)\\bbearer\\s+[^\\s,;]+").replace(value, "Bearer [redacted]")
        return value.take(300)
    }

    private companion object {
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        const val MAX_FOLDER_NAME_LENGTH = 200
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

class GoogleDriveApiException(message: String) : Exception(message)

private suspend fun Call.awaitDriveApi(): Response = suspendCancellableCoroutine { continuation ->
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
