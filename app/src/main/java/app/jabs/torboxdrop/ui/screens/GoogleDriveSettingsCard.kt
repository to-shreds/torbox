package app.jabs.torboxdrop.ui.screens

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

    var connected by remember { mutableStateOf(preferences.googleDriveConnected) }
    var defaultEnabled by remember { mutableStateOf(preferences.googleDriveByDefault) }
    var folderDraft by rememberSaveable { mutableStateOf(preferences.googleDriveFolderName) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var pendingFolderName by rememberSaveable { mutableStateOf<String?>(null) }

    fun refreshLocalState() {
        connected = preferences.googleDriveConnected && !preferences.googleDriveFolderId.isNullOrBlank()
        defaultEnabled = preferences.googleDriveByDefault
        folderDraft = preferences.googleDriveFolderName
    }

    suspend fun finishConnection(accessToken: String, requestedFolderName: String) {
        val normalizedName = requestedFolderName.trim().ifBlank { AppPreferences.DEFAULT_DRIVE_FOLDER_NAME }
        try {
            if (!container.tokenStore.hasToken()) {
                error("Connect your TorBox account before connecting Google Drive.")
            }
            val existingId = preferences.googleDriveFolderId
            val existingName = preferences.googleDriveFolderName
            val folder = if (existingId.isNullOrBlank() || existingName != normalizedName) {
                container.googleDriveApi.createDestinationFolder(normalizedName, accessToken).also {
                    // Persist immediately after Google creates it. If the TorBox settings call fails,
                    // retrying will reuse this exact folder rather than creating a duplicate.
                    preferences.googleDriveFolderId = it.id
                    preferences.googleDriveFolderName = it.name
                }
            } else {
                GoogleDriveFolder(existingId, existingName)
            }

            // Reapply the account-level destination every successful connection. This is important
            // after replacing a TorBox API token because the new TorBox account has separate settings.
            container.driveIntegration.updateGoogleDriveFolderId(folder.id)
            preferences.googleDriveConnected = true
            val accountScope = driveAccountScope(container.tokenStore.read())
            if (accountScope != null) {
                container.driveStore.resetAuthorizationRequired(accountScope)
            }
            CompletionMonitorScheduler.scheduleFallback(app)
            CompletionMonitorScheduler.runSoon(app)
            CompletionMonitorService.startFromUserAction(app)
            messageIsError = false
            message = "Connected. Ready downloads will go to ${folder.name}."
        } catch (error: Exception) {
            preferences.googleDriveConnected = false
            messageIsError = true
            message = error.message?.take(240)?.ifBlank { null }
                ?: "Google Drive could not be connected."
        } finally {
            busy = false
            pendingFolderName = null
            refreshLocalState()
        }
    }

    val authorizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val requestedName = pendingFolderName ?: folderDraft
        when (val authorization = container.googleDriveAuthorization.completeFromIntent(result.data)) {
            is GoogleDriveAuthorizationResult.Authorized -> scope.launch {
                finishConnection(authorization.accessToken, requestedName)
            }
            is GoogleDriveAuthorizationResult.NeedsResolution -> {
                busy = false
                messageIsError = true
                message = "Google Drive still needs authorization. Try Connect again."
            }
            is GoogleDriveAuthorizationResult.Failed -> {
                busy = false
                pendingFolderName = null
                messageIsError = true
                message = authorization.message
            }
        }
    }

    fun authorizeAndConfigure(requestedFolderName: String) {
        if (busy) return
        if (!container.tokenStore.hasToken()) {
            messageIsError = true
            message = "Connect your TorBox account first."
            return
        }
        val normalized = requestedFolderName.trim().ifBlank { AppPreferences.DEFAULT_DRIVE_FOLDER_NAME }
        pendingFolderName = normalized
        busy = true
        message = null
        scope.launch {
            when (val authorization = container.googleDriveAuthorization.authorize()) {
                is GoogleDriveAuthorizationResult.Authorized -> {
                    finishConnection(authorization.accessToken, normalized)
                }
                is GoogleDriveAuthorizationResult.NeedsResolution -> {
                    runCatching {
                        authorizationLauncher.launch(
                            IntentSenderRequest.Builder(authorization.pendingIntent.intentSender).build(),
                        )
                    }.onFailure {
                        busy = false
                        pendingFolderName = null
                        messageIsError = true
                        message = "Android could not open Google Drive authorization."
                    }
                }
                is GoogleDriveAuthorizationResult.Failed -> {
                    busy = false
                    pendingFolderName = null
                    messageIsError = true
                    message = authorization.message
                }
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
