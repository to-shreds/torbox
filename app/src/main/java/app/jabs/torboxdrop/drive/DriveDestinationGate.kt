package app.jabs.torboxdrop.drive

import app.jabs.torboxdrop.data.TorBoxDriveGateway
import java.util.Locale

data class DriveDestinationLease(val folderId: String, val restoreFolderId: String?)

/**
 * TorBox documents only an account-wide Drive folder, not a per-job folder argument.
 * Keep that folder unchanged for the lifetime of accepted or ambiguous jobs. All callers
 * hold AccountSensitiveWorkGate; cross-device/website changes are detected, never overridden
 * while jobs are in flight. Do not initiate competing Drive uploads in another TorBox client.
 */
internal class DriveDestinationGate(private val store: DriveStore, private val api: TorBoxDriveGateway) {
    suspend fun acquire(scope: String, watchKey: String, destination: String): Boolean {
        val active = store.inFlightWatchKeys(scope)
        // Serialize watches as well as folders so identical hash/file jobs cannot cross-adopt.
        if (active.any { it != watchKey }) return false
        var lease = store.destinationLease(scope)
        if (lease != null && lease.folderId != destination) {
            if (!releaseIfIdle(scope)) return false
            lease = null
        }
        val current = api.getGoogleDriveFolderId()
        if (lease != null && current == lease.folderId) return true
        // No changes to a folder after even one possibly accepted request, including old v2 journals.
        if (active.isNotEmpty() || api.getAllJobs().any(::blocksFolderChange)) return false
        if (lease != null && current != lease.folderId && current != lease.restoreFolderId) {
            // Someone changed the account setting after our lease. Preserve that newer value.
            store.clearDestinationLease(scope)
            lease = null
        }
        if (lease == null) {
            // Persist before PUT: a lost response/restart must not forget the original destination.
            lease = DriveDestinationLease(destination, current)
            store.saveDestinationLease(scope, lease)
        }
        api.updateGoogleDriveFolderId(destination)
        check(api.getGoogleDriveFolderId() == destination) { "TorBox has not confirmed the Drive destination." }
        return true
    }

    suspend fun releaseIfIdle(scope: String): Boolean {
        val lease = store.destinationLease(scope) ?: return true
        if (store.inFlightWatchKeys(scope).isNotEmpty()) return false
        if (api.getAllJobs().any(::blocksFolderChange)) return false
        val current = api.getGoogleDriveFolderId()
        if (current == lease.folderId && current != lease.restoreFolderId) {
            api.updateGoogleDriveFolderId(lease.restoreFolderId)
            check(api.getGoogleDriveFolderId() == lease.restoreFolderId) { "TorBox has not restored the Drive destination." }
        }
        store.clearDestinationLease(scope)
        return true
    }

    companion object {
        internal fun blocksFolderChange(job: TorBoxIntegrationJob): Boolean {
            val integration = job.integration?.lowercase(Locale.ROOT)?.replace("_", "")?.replace(" ", "")
            if (integration in setOf("onedrive", "dropbox", "gofile", "pixeldrain", "1fichier")) return false
            return job.status?.trim()?.lowercase(Locale.ROOT) !in setOf("completed", "failed", "cancelled", "canceled")
        }
    }
}
