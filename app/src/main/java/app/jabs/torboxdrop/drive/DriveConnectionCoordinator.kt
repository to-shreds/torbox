package app.jabs.torboxdrop.drive

import app.jabs.torboxdrop.data.AppPreferences
import app.jabs.torboxdrop.data.TorBoxDriveGateway
import app.jabs.torboxdrop.notifications.AccountSensitiveWorkGate

/** Called only after explicit visible Google consent; no bearer token is stored here. */
class DriveConnectionCoordinator(
    private val preferences: AppPreferences,
    private val tokenProvider: () -> String?,
    private val google: GoogleDriveApiClient,
    private val torBox: TorBoxDriveGateway,
    private val store: DriveStore,
) {
    suspend fun connect(expectedAccount: String, folderName: String, accessToken: String): GoogleDriveFolder =
        AccountSensitiveWorkGate.withAccount(expectedAccount, tokenProvider) {
            val name = folderName.trim().take(200).ifBlank { AppPreferences.DEFAULT_DRIVE_FOLDER_NAME }
            preferences.googleDriveConnected = false
            val saved = preferences.googleDriveFolderId?.takeIf { preferences.googleDriveFolderName == name }
            val id = saved ?: google.generateFolderId(accessToken).also {
                preferences.reserveDriveFolder(it, name)
            }
            val folder = google.ensureDestinationFolder(id, name, accessToken)
            torBox.updateGoogleDriveFolderId(folder.id)
            preferences.bindDriveAccount(expectedAccount)
            store.resetAuthorizationRequired(expectedAccount)
            folder
        }
}
