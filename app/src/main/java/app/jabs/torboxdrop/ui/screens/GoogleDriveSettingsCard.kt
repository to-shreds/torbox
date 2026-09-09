package app.jabs.torboxdrop.ui.screens

import android.app.Activity
import androidx.compose.runtime.LaunchedEffect
import app.jabs.torboxdrop.drive.DriveConnectionCoordinator
import app.jabs.torboxdrop.drive.DriveFileState
import app.jabs.torboxdrop.drive.GoogleDriveApiException
import app.jabs.torboxdrop.notifications.AccountSensitiveWorkGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.withLock

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.jabs.torboxdrop.TorBoxDropApplication
import app.jabs.torboxdrop.data.AppPreferences
import app.jabs.torboxdrop.drive.GoogleDriveAuthorizationResult
import app.jabs.torboxdrop.drive.GoogleDriveFolder
import app.jabs.torboxdrop.drive.driveAccountScope
import app.jabs.torboxdrop.notifications.CompletionMonitorScheduler
import app.jabs.torboxdrop.notifications.CompletionMonitorService
import kotlinx.coroutines.launch

/**
 * First-class Drive setup without routing a Google access token through Compose state.
 *
 * Only non-secret connection/folder state is remembered. A Google bearer token exists only on the
 * coroutine stack long enough to create/configure the destination and is then discarded.
 */
@Composable
fun GoogleDriveSettingsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as TorBoxDropApplication
    val container = app.container
    val preferences = container.preferences
    val scope = rememberCoroutineScope()

    var connected by remember { mutableStateOf(false) }
    var defaultEnabled by remember { mutableStateOf(preferences.googleDriveByDefault) }
    var folderDraft by rememberSaveable { mutableStateOf(preferences.googleDriveFolderName) }
    var pendingFolderName by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingAccount by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(pendingFolderName != null) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var transferRows by remember { mutableStateOf(emptyList<String>()) }

    fun refreshLocalState() {
        connected = preferences.driveConfiguredFor(driveAccountScope(container.tokenStore.read()))
        defaultEnabled = preferences.googleDriveByDefault
    }

    LaunchedEffect(Unit) {
        while (isActive) {
            refreshLocalState()
            val account = driveAccountScope(container.tokenStore.read())
            transferRows = if (account == null) emptyList() else container.driveStore.recentWatches(account)
                .filterNot { it.queueId?.startsWith("ADMISSION:") == true && it.state.name == "FAILED" }
                .map { watch ->
                    val files = container.driveStore.fileTransfers(watch.watchKey)
                    val completed = files.count { it.state == DriveFileState.COMPLETE }
                    val state = when (watch.state.name) {
                        "WAITING" -> "Waiting for TorBox"
                        "TRANSFERRING" -> "Queued / transferring"
                        "AUTH_REQUIRED" -> "Reconnect Drive"
                        "NEEDS_REVIEW" -> "Unconfirmed; check before resending"
                        "COMPLETE" -> "Complete"
                        "PARTIAL_FAILURE" -> "Some files failed"
                        else -> "Stopped / failed"
                    }
                    "${watch.name}: $state" + (if (files.isEmpty()) "" else " ($completed/${files.size} complete)") +
                        (watch.lastError?.let { "\n$it" } ?: "")
                }
            delay(2_000)
        }
    }

    suspend fun finishConnection(accessToken: String, requestedName: String, expectedAccount: String) {
        try {
            val folder = DriveConnectionCoordinator(preferences, container.tokenStore::read,
                container.googleDriveApi, container.driveIntegration, container.driveStore)
                .connect(expectedAccount, requestedName, accessToken)
            val scheduled = runCatching {
                CompletionMonitorScheduler.scheduleFallback(app)
                CompletionMonitorScheduler.runSoon(app)
                CompletionMonitorService.startFromUserAction(app)
            }.isSuccess
            messageIsError = !scheduled
            message = if (scheduled) "Connected. Ready torrent files will be sent to ${folder.name}."
                else "Drive is connected, but Android could not start monitoring. Use Check transfers below."
        } catch (error: CancellationException) {
            throw error
        } catch (error: GoogleDriveApiException) {
            messageIsError = true
            message = error.message
        } catch (_: Exception) {
            messageIsError = true
            message = "Drive setup did not finish. Check the TorBox account and Google OAuth setup, then reconnect. The reserved folder is kept for a safe retry."
        } finally {
            busy = false
            pendingFolderName = null
            pendingAccount = null
            refreshLocalState()
        }
    }

    val authorizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val requestedName = pendingFolderName
        val expectedAccount = pendingAccount
        if (result.resultCode != Activity.RESULT_OK || requestedName == null || expectedAccount == null) {
            busy = false
            pendingFolderName = null
            pendingAccount = null
            messageIsError = true
            message = "Google Drive setup was cancelled. Nothing was connected."
        } else when (val authorization = container.googleDriveAuthorization.completeFromIntent(result.data)) {
            is GoogleDriveAuthorizationResult.Authorized -> scope.launch {
                finishConnection(authorization.accessToken, requestedName, expectedAccount)
            }
            else -> {
                busy = false
                pendingFolderName = null
                pendingAccount = null
                messageIsError = true
                message = "Google Drive permission was not granted. Try Connect again."
            }
        }
    }

    fun authorizeAndConfigure(requestedFolderName: String) {
        if (busy) return
        val expectedAccount = driveAccountScope(container.tokenStore.read())
        if (expectedAccount == null) {
            messageIsError = true
            message = "Connect your TorBox account first."
            return
        }
        val normalized = requestedFolderName.trim().ifBlank { AppPreferences.DEFAULT_DRIVE_FOLDER_NAME }
        pendingFolderName = normalized
        pendingAccount = expectedAccount
        busy = true
        message = null
        scope.launch {
            try {
                when (val authorization = container.googleDriveAuthorization.authorize()) {
                    is GoogleDriveAuthorizationResult.Authorized ->
                        finishConnection(authorization.accessToken, normalized, expectedAccount)
                    is GoogleDriveAuthorizationResult.NeedsResolution -> {
                        authorizationLauncher.launch(
                            IntentSenderRequest.Builder(authorization.pendingIntent.intentSender).build(),
                        )
                    }
                    is GoogleDriveAuthorizationResult.Failed -> {
                        busy = false
                        pendingFolderName = null
                        pendingAccount = null
                        messageIsError = true
                        message = authorization.message
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                busy = false
                pendingFolderName = null
                pendingAccount = null
                messageIsError = true
                message = "Android could not open Google Drive authorization."
            }
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Cloud,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    Text("Google Drive", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (connected) {
                            "Connected · destination: ${preferences.googleDriveFolderName}"
                        } else {
                            "Send completed torrent files directly from TorBox to Drive"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = if (connected) Icons.Rounded.CheckCircle else Icons.Rounded.Cloud,
                    contentDescription = null,
                    tint = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            OutlinedTextField(
                value = folderDraft,
                onValueChange = { folderDraft = it.take(200) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
                label = { Text("Destination folder") },
                supportingText = {
                    Text(
                        if (connected && folderDraft == preferences.googleDriveFolderName) {
                            "TorBox uploads into this app-created Drive folder."
                        } else {
                            "A new folder with this name will be created in My Drive."
                        },
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )

            Text(
                "Google file permission lets this app create its destination folder. A short-lived Google token is sent to TorBox so TorBox can upload your selected torrent files. The folder setting also applies to Drive uploads started elsewhere in your TorBox account.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (!connected) {
                Button(
                    onClick = { authorizeAndConfigure(folderDraft) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && folderDraft.isNotBlank(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (busy) "Connecting…" else "Connect Google Drive")
                }
            } else if (folderDraft.trim() != preferences.googleDriveFolderName) {
                OutlinedButton(
                    onClick = { authorizeAndConfigure(folderDraft) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && folderDraft.isNotBlank(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (busy) "Changing folder…" else "Create & use this folder")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Send new torrents to Drive", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Use Drive by default on the Add screen. You can still turn it off per torrent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Switch(
                    checked = defaultEnabled && connected,
                    enabled = connected && !busy,
                    onCheckedChange = { enabled ->
                        preferences.googleDriveByDefault = enabled
                        defaultEnabled = enabled
                    },
                )
            }

            OutlinedButton(
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val pass = app.driveAutomationRunner().runOnce(includeReview = true)
                            if (pass.torBoxAuthBlocked) {
                                CompletionMonitorScheduler.cancelRejectedAccount(app, pass.accountScope)
                            } else if (CompletionMonitorScheduler.hasDriveWork(app)) {
                                CompletionMonitorScheduler.scheduleFallback(app)
                                CompletionMonitorScheduler.runSoon(app)
                            }
                            messageIsError = pass.torBoxAuthBlocked || pass.retryableFailures > 0 || pass.authRequired
                            message = when {
                                pass.torBoxAuthBlocked -> "TorBox rejected the API token. Replace it before checking transfers again."
                                pass.authRequired -> "Reconnect Google Drive to continue unsent files."
                                pass.retryableFailures > 0 -> "Some transfer checks failed. Unconfirmed requests are not resent."
                                else -> "Transfer check finished. Unconfirmed requests are not resent."
                            }
                        } catch (error: CancellationException) { throw error
                        } catch (_: Exception) {
                            messageIsError = true
                            message = "Transfer status could not be checked. Nothing uncertain was resent."
                        } finally { busy = false; refreshLocalState() }
                    }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
            ) { Text("Check transfers") }

            if (connected || transferRows.isNotEmpty()) {
                TextButton(onClick = {
                    busy = true
                    scope.launch {
                        try {
                            AccountSensitiveWorkGate.mutex.withLock {
                                driveAccountScope(container.tokenStore.read())?.let { container.driveStore.stopUnsubmitted(it) }
                                preferences.clearDriveConnection()
                            }
                            CompletionMonitorScheduler.cancelIfIdle(app)
                            messageIsError = false
                            message = "Unsent files were stopped. Jobs already queued in TorBox may continue. Google account permission has not been revoked."
                        } catch (error: CancellationException) { throw error
                        } catch (_: Exception) {
                            messageIsError = true
                            message = "Stopping did not finish. Check the transfer list before trying again."
                        } finally { busy = false; refreshLocalState() }
                    }
                }, enabled = !busy) { Text("Stop sending to Drive") }
            }
            transferRows.forEach { row ->
                Text(row, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
            }

            message?.let { text ->
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        if (messageIsError) Icons.Rounded.ErrorOutline else Icons.Rounded.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                        tint = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
