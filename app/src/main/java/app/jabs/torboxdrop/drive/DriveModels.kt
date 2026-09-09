package app.jabs.torboxdrop.drive

import app.jabs.torboxdrop.model.DownloadType
import java.time.Instant

enum class DriveWatchState {
    WAITING,
    AUTH_REQUIRED,
    TRANSFERRING,
    NEEDS_REVIEW,
    COMPLETE,
    PARTIAL_FAILURE,
    FAILED,
}

data class DriveWatch(
    val watchKey: String,
    val accountScope: String,
    val downloadId: String,
    val type: DownloadType,
    val name: String,
    val armedAt: Instant,
    val hasBeenSeen: Boolean = true,
    val consecutiveMisses: Int = 0,
    val queueId: String? = null,
    val sourceHash: String? = null,
    val sourceValue: String? = null,
    val state: DriveWatchState = DriveWatchState.WAITING,
    val lastError: String? = null,
)

enum class DriveFileState {
    PENDING,
    SUBMITTING,
    SUBMITTED,
    UNCERTAIN,
    COMPLETE,
    FAILED,
}

data class DriveFileTransfer(
    val transferKey: String,
    val downloadKey: String,
    val fileId: Long,
    val fileName: String,
    val state: DriveFileState,
    val jobId: Long? = null,
    val attempts: Int = 0,
    val lastAttemptAt: Instant? = null,
    val priorJobIds: Set<Long> = emptySet(),
    val baselineCaptured: Boolean = false,
    val retryAt: Instant? = null,
    val lastError: String? = null,
)

data class TorBoxIntegrationJob(
    val id: Long?,
    val fileId: Long?,
    val hash: String?,
    val integration: String?,
    val progress: Double?,
    val status: String?,
    val type: String?,
    val detail: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val zip: Boolean = false,
)

data class DriveAutomationPassResult(
    val watchedAtStart: Int,
    val accountScope: String? = null,
    val queuedFiles: Int = 0,
    val completedFiles: Int = 0,
    val failedFiles: Int = 0,
    val retryableFailures: Int = 0,
    val authRequired: Boolean = false,
    val torBoxAuthBlocked: Boolean = false,
    val hasWork: Boolean = false,
)
