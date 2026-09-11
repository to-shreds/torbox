package app.jabs.torboxdrop.drive

import app.jabs.torboxdrop.data.AppPreferences
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import app.jabs.torboxdrop.notifications.AccountSensitiveWorkGate
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class ManualDriveException(message: String) : Exception(message)
data class ManualDriveResult(val added: Boolean, val folderName: String, val monitoringScheduled: Boolean)
data class ManualDriveTarget(val item: DownloadItem, val accountScope: String)

/** Explicit confirmation only; never re-adds or deletes the TorBox torrent. */
class ManualDriveCoordinator(
    private val preferences: AppPreferences,
    private val tokenProvider: () -> String?,
    private val google: GoogleDriveApiClient,
    private val store: DriveStore,
    private val loadTorrent: suspend (String) -> DownloadItem?,
    private val schedule: () -> Unit,
) {
    suspend fun enqueue(target: ManualDriveTarget, folderName: String, accessToken: String): ManualDriveResult =
        AccountSensitiveWorkGate.withAccount(target.accountScope, tokenProvider) {
            val name = normalizeFolderName(folderName)
            if (!preferences.driveConfiguredFor(target.accountScope)) {
                throw ManualDriveException("Connect Google Drive in Settings first, then try again.")
            }
            if (!isEligible(target.item)) throw ManualDriveException("Only ready torrents can be sent to Drive.")
            val item = loadTorrent(target.item.id)
                ?: throw ManualDriveException("TorBox no longer lists this torrent. Nothing was sent.")
            if (!isEligible(item) || item.id != target.item.id || item.hash.isNullOrBlank() ||
                !item.hash.equals(target.item.hash, true) ||
                !Regex("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}").matches(item.hash)) {
                throw ManualDriveException("This torrent is no longer ready or its identity changed. Refresh and try again.")
            }
            val defaultId = preferences.googleDriveFolderId
            val id = if (name == preferences.googleDriveFolderName && defaultId != null) defaultId else {
                store.reservedManualFolder(target.accountScope, name) ?: google.generateFolderId(accessToken).also {
                    store.reserveManualFolder(target.accountScope, name, it)
                }
            }
            val folder = google.ensureDestinationFolder(id, name, accessToken)
            // Durable admission and scheduling form one cancellation-safe tail. No bearer is saved.
            withContext(NonCancellable) {
                val added = store.armManual(target.accountScope, item, folder, defaultId)
                val scheduled = runCatching { schedule() }.isSuccess
                ManualDriveResult(added, folder.name, scheduled)
            }
        }

    companion object {
        fun isEligible(item: DownloadItem): Boolean =
            item.type == DownloadType.TORRENT && item.downloadFinished && item.downloadPresent

        fun normalizeFolderName(value: String): String {
            val name = value.trim()
            if (name.isEmpty() || name.length > 200 || name.any { it.isISOControl() } || name in setOf(".", "..")) {
                throw ManualDriveException("Enter a folder name of 1 to 200 characters without control characters.")
            }
            return name
        }
    }
}
