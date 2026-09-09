package app.jabs.torboxdrop.drive

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface GoogleDriveAuthorizationResult {
    /** Short-lived bearer token. It must be used immediately and never persisted. */
    class Authorized(val accessToken: String) : GoogleDriveAuthorizationResult {
        override fun toString(): String = "Authorized([redacted])"
    }

    /** Interactive consent/account selection is required and must be launched from visible UI. */
    data class NeedsResolution(val pendingIntent: PendingIntent) : GoogleDriveAuthorizationResult

    data class Failed(val message: String) : GoogleDriveAuthorizationResult
}

/**
 * Thin boundary around Google Identity Services authorization for the narrow Drive file scope.
 *
 * Access tokens are deliberately returned to the caller only. This class never writes them to
 * preferences, the database, logs, diagnostics, or saved instance state.
 */
fun interface GoogleDriveAuthorizer {
    suspend fun authorize(): GoogleDriveAuthorizationResult
}

class GoogleDriveAuthorizationManager(context: Context) : GoogleDriveAuthorizer {
    private val appContext = context.applicationContext
    private val client get() = Identity.getAuthorizationClient(appContext)

    override suspend fun authorize(): GoogleDriveAuthorizationResult = try {
        toResult(client.authorize(AUTHORIZATION_REQUEST).awaitCancellable())
    } catch (error: CancellationException) {
        throw error
    } catch (error: ApiException) {
        GoogleDriveAuthorizationResult.Failed(
            if (error.statusCode == 10) {
                "Google Drive setup is not configured for this app's signing certificate. Register its Android OAuth client first."
            } else "Google Drive authorization was rejected. Try connecting again.",
        )
    } catch (_: Exception) {
        GoogleDriveAuthorizationResult.Failed("Google Drive authorization is unavailable right now.")
    }

    fun completeFromIntent(data: Intent?): GoogleDriveAuthorizationResult = try {
        toResult(client.getAuthorizationResultFromIntent(data))
    } catch (_: ApiException) {
        GoogleDriveAuthorizationResult.Failed("Google Drive authorization was not completed.")
    } catch (_: Exception) {
        GoogleDriveAuthorizationResult.Failed("Google Drive authorization could not be completed.")
    }

    private fun toResult(result: AuthorizationResult): GoogleDriveAuthorizationResult {
        if (result.hasResolution()) {
            return result.pendingIntent?.let(GoogleDriveAuthorizationResult::NeedsResolution)
                ?: GoogleDriveAuthorizationResult.Failed("Google Drive requires authorization.")
        }
        if (DRIVE_FILE_SCOPE !in result.grantedScopes) {
            return GoogleDriveAuthorizationResult.Failed("Google Drive file permission was not granted.")
        }
        val token = result.accessToken?.trim().orEmpty()
        return if (token.isNotEmpty()) {
            GoogleDriveAuthorizationResult.Authorized(token)
        } else {
            GoogleDriveAuthorizationResult.Failed("Google Drive did not return an access token.")
        }
    }

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private val AUTHORIZATION_REQUEST: AuthorizationRequest = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .build()
    }
}

private suspend fun <T> Task<T>.awaitCancellable(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> if (continuation.isActive) continuation.resume(result) }
    addOnFailureListener { error -> if (continuation.isActive) continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}
