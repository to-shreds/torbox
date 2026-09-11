package app.jabs.torboxdrop.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.jabs.torboxdrop.TorBoxDropApplication
import app.jabs.torboxdrop.drive.GoogleDriveApiException
import app.jabs.torboxdrop.drive.GoogleDriveAuthorizationResult
import app.jabs.torboxdrop.drive.ManualDriveCoordinator
import app.jabs.torboxdrop.drive.ManualDriveException
import app.jabs.torboxdrop.drive.ManualDriveTarget
import app.jabs.torboxdrop.model.DownloadType
import app.jabs.torboxdrop.notifications.CompletionMonitorScheduler
import app.jabs.torboxdrop.notifications.CompletionMonitorService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Merely opening/dismissing this prompt performs no authorization, folder creation or upload. */
@Composable
fun ManualDriveDialog(target: ManualDriveTarget, onDismiss: () -> Unit, onMessage: (String) -> Unit,
    onSettings: () -> Unit) {
    val app = LocalContext.current.applicationContext as TorBoxDropApplication
    val c = app.container
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(target.accountScope, target.item.id) { mutableStateOf(c.preferences.googleDriveFolderName) }
    var error by rememberSaveable(target.accountScope, target.item.id) { mutableStateOf<String?>(null) }
    var awaitingConsent by rememberSaveable(target.accountScope, target.item.id) { mutableStateOf(false) }
    var busy by remember(target.accountScope, target.item.id) { mutableStateOf(awaitingConsent) }
    var consentName by rememberSaveable(target.accountScope, target.item.id) { mutableStateOf<String?>(null) }
    val connected = c.preferences.driveConfiguredFor(target.accountScope)

    suspend fun enqueue(token: String, confirmedName: String) {
        try {
            val result = ManualDriveCoordinator(c.preferences, c.tokenStore::read, c.googleDriveApi, c.driveStore,
                loadTorrent = { c.api.getDownload(DownloadType.TORRENT, it, bypassCache = true) },
                schedule = {
                    CompletionMonitorScheduler.scheduleFallback(app)
                    CompletionMonitorScheduler.runSoon(app)
                },
            ).enqueue(target, confirmedName, token)
            // The durable worker is already queued. A foreground-service failure cannot undo it.
            runCatching { CompletionMonitorService.startFromUserAction(app) }
            onMessage(when {
                !result.monitoringScheduled -> "Drive request saved. Open Settings and use Check transfers to start monitoring."
                !result.added -> "This torrent already has a Drive request for ${result.folderName}. Check transfers in Settings."
                else -> "Drive request queued for ${result.folderName}. Progress is in Settings → Google Drive."
            })
            onDismiss()
        } catch (e: CancellationException) { throw e
        } catch (e: ManualDriveException) { error = e.message
        } catch (e: GoogleDriveApiException) { error = e.message
        } catch (_: Exception) { error = "Could not queue this Drive request. Check your accounts and retry; existing uploads are not resent."
        } finally { busy = false; consentName = null }
    }

    val authorizationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        awaitingConsent = false
        val confirmedName = consentName
        if (result.resultCode != Activity.RESULT_OK || confirmedName == null) {
            busy = false; consentName = null; error = "Google authorization was cancelled. Nothing was sent."
        } else when (val auth = c.googleDriveAuthorization.completeFromIntent(result.data)) {
            is GoogleDriveAuthorizationResult.Authorized -> scope.launch { enqueue(auth.accessToken, confirmedName) }
            else -> { busy = false; consentName = null; error = "Google Drive permission was not granted. Nothing was sent." }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Send to Google Drive") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(target.item.name, style = MaterialTheme.typography.titleSmall)
                Text("Copy this torrent's files individually. The originals stay in TorBox.")
                OutlinedTextField(value = name, onValueChange = { name = it.take(200); error = null },
                    enabled = !busy, label = { Text("Destination folder name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Text("Creates or reuses a folder managed by this app in My Drive. Your automatic-upload default stays unchanged.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Different folders wait their turn. While these uploads run, do not start Drive uploads or change the Drive destination in another TorBox app or website.",
                    style = MaterialTheme.typography.bodySmall)
                if (!connected) Text("Connect Google Drive in Settings first.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) CircularProgressIndicator()
            }
        },
        confirmButton = {
            if (!connected) TextButton(onClick = onSettings) { Text("Open Settings") }
            else TextButton(enabled = !busy && name.isNotBlank(), onClick = {
                if (!busy) {
                    val confirmed = runCatching { ManualDriveCoordinator.normalizeFolderName(name) }
                    if (confirmed.isFailure) error = confirmed.exceptionOrNull()?.message
                    else {
                        busy = true; error = null; consentName = confirmed.getOrThrow()
                        scope.launch {
                            try {
                                when (val auth = c.googleDriveAuthorization.authorize()) {
                                    is GoogleDriveAuthorizationResult.Authorized -> enqueue(auth.accessToken, confirmed.getOrThrow())
                                    is GoogleDriveAuthorizationResult.NeedsResolution -> {
                                        awaitingConsent = true
                                        authorizationLauncher.launch(IntentSenderRequest.Builder(auth.pendingIntent.intentSender).build())
                                    }
                                    is GoogleDriveAuthorizationResult.Failed -> { error = auth.message; busy = false; consentName = null }
                                }
                            } catch (e: CancellationException) { busy = false; throw e
                            } catch (_: Exception) { error = "Could not open Google authorization."; busy = false; consentName = null; awaitingConsent = false }
                        }
                    }
                }
            }) { Text(if (busy) "Queuing…" else "Send to Drive") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
