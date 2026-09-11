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
            val holdsDestination = store.destinationLease(expectedAccount) != null ||
                store.inFlightWatchKeys(expectedAccount).isNotEmpty()
            if (holdsDestination && name != preferences.googleDriveFolderName) {
                throw GoogleDriveApiException("Wait for current Drive transfers to finish before changing the automatic destination.")
            }
            preferences.googleDriveConnected = false
            val saved = preferences.googleDriveFolderId?.takeIf { preferences.googleDriveFolderName == name }
            val id = saved ?: google.generateFolderId(accessToken).also {
                preferences.reserveDriveFolder(it, name)
            }
            val folder = google.ensureDestinationFolder(id, name, accessToken)
            // Re-authorization must not redirect an in-progress custom-folder upload.
            if (!holdsDestination) {
                val current = torBox.getGoogleDriveFolderId()
                if (current != folder.id && torBox.getAllJobs().any { DriveDestinationGate.blocksFolderChange(it) }) {
                    throw GoogleDriveApiException("Other Drive uploads are still running. Wait for them before changing the destination.")
                }
                torBox.updateGoogleDriveFolderId(folder.id)
            }
            preferences.bindDriveAccount(expectedAccount)
            store.resetAuthorizationRequired(expectedAccount)
            folder
        }
}
