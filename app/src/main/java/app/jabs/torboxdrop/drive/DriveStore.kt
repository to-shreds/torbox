package app.jabs.torboxdrop.drive

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.jabs.torboxdrop.model.DownloadFile
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Separate durable state for Drive automation.
 *
 * It intentionally does not share the notification-subscription table. Turning a completion bell
 * off must never cancel a Drive transfer, and a Drive-only watch must never masquerade as a
 * notification watch in the UI.
 */
class DriveStore(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE drive_watches (
                watch_key TEXT PRIMARY KEY NOT NULL,
                account_scope TEXT NOT NULL,
                item_id TEXT NOT NULL,
                item_type TEXT NOT NULL,
                name TEXT NOT NULL,
                armed_at INTEGER NOT NULL,
                has_been_seen INTEGER NOT NULL,
                consecutive_misses INTEGER NOT NULL,
                queue_id TEXT,
                source_hash TEXT,
                source_value TEXT,
                state TEXT NOT NULL,
                last_error TEXT
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX drive_watches_account_state ON drive_watches(account_scope, state)")
        db.execSQL(
            """CREATE TABLE drive_files (
                transfer_key TEXT PRIMARY KEY NOT NULL,
                watch_key TEXT NOT NULL,
                file_id INTEGER NOT NULL,
                file_name TEXT NOT NULL,
                state TEXT NOT NULL,
                job_id INTEGER,
                attempts INTEGER NOT NULL,
                last_attempt_at INTEGER,
                last_error TEXT
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX drive_files_watch_key ON drive_files(watch_key)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    suspend fun armActive(
        accountScope: String,
        item: DownloadItem,
        sourceValue: String? = null,
        hasBeenSeen: Boolean = true,
    ) = io {
        val watchKey = key(item.type, item.id)
        writableDatabase.transaction {
            delete("drive_files", "watch_key = ?", arrayOf(watchKey))
            insertWithOnConflict(
                "drive_watches",
                null,
                watchValues(
                    watchKey = watchKey,
                    accountScope = accountScope,
                    itemId = item.id,
                    type = item.type,
                    name = item.name,
                    hasBeenSeen = hasBeenSeen,
                    queueId = null,
                    sourceHash = item.hash,
                    sourceValue = sourceValue,
                ),
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    suspend fun armQueued(
        accountScope: String,
        type: DownloadType,
        queueId: String,
        name: String,
        sourceHash: String?,
        sourceValue: String?,
    ) = io {
        val watchKey = queuedKey(type, queueId)
        writableDatabase.transaction {
            delete("drive_files", "watch_key = ?", arrayOf(watchKey))
            insertWithOnConflict(
                "drive_watches",
                null,
                watchValues(
                    watchKey = watchKey,
                    accountScope = accountScope,
                    itemId = queueId,
                    type = type,
                    name = name,
                    hasBeenSeen = false,
                    queueId = queueId,
                    sourceHash = sourceHash,
                    sourceValue = sourceValue,
                ),
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    suspend fun runnableWatches(accountScope: String): List<DriveWatch> = io {
        watches(
            accountScope,
            setOf(DriveWatchState.WAITING, DriveWatchState.TRANSFERRING),
        )
    }

    suspend fun authRequiredWatches(accountScope: String): List<DriveWatch> = io {
        watches(accountScope, setOf(DriveWatchState.AUTH_REQUIRED))
    }

    suspend fun hasRunnableWork(accountScope: String): Boolean = io {
        readableDatabase.query(
            "drive_watches",
            arrayOf("watch_key"),
            "account_scope = ? AND state IN (?, ?)",
            arrayOf(accountScope, DriveWatchState.WAITING.name, DriveWatchState.TRANSFERRING.name),
            null,
            null,
            null,
            "1",
        ).use { it.moveToFirst() }
    }

    suspend fun resetAuthorizationRequired(accountScope: String) = io {
        writableDatabase.execSQL(
            """UPDATE drive_watches
               SET state = CASE
                   WHEN EXISTS (SELECT 1 FROM drive_files f WHERE f.watch_key = drive_watches.watch_key)
                       THEN ? ELSE ? END,
                   last_error = NULL
               WHERE account_scope = ? AND state = ?""".trimIndent(),
            arrayOf(
                DriveWatchState.TRANSFERRING.name,
                DriveWatchState.WAITING.name,
                accountScope,
                DriveWatchState.AUTH_REQUIRED.name,
            ),
        )
    }

    suspend fun markAuthorizationRequired(watchKey: String, message: String) =
        updateWatchState(watchKey, DriveWatchState.AUTH_REQUIRED, message)

    suspend fun markWatchFailed(watchKey: String, message: String) =
        updateWatchState(watchKey, DriveWatchState.FAILED, message)

    suspend fun markWatchTransferring(watchKey: String, sourceHash: String?, name: String?) = io {
        writableDatabase.update(
            "drive_watches",
            ContentValues().apply {
                put("state", DriveWatchState.TRANSFERRING.name)
                putNull("last_error")
                sourceHash?.takeIf(String::isNotBlank)?.let { put("source_hash", it) }
                name?.takeIf(String::isNotBlank)?.let { put("name", it) }
                put("has_been_seen", 1)
                put("consecutive_misses", 0)
            },
            "watch_key = ?",
            arrayOf(watchKey),
        )
    }

    suspend fun markSeen(watchKey: String, item: DownloadItem) = io {
        writableDatabase.update(
            "drive_watches",
            ContentValues().apply {
                put("has_been_seen", 1)
                put("consecutive_misses", 0)
                put("name", item.name)
                item.hash?.takeIf(String::isNotBlank)?.let { put("source_hash", it) }
            },
            "watch_key = ?",
            arrayOf(watchKey),
        )
    }

    suspend fun recordMiss(watchKey: String): Int = io {
        writableDatabase.transactionResult {
            execSQL(
                "UPDATE drive_watches SET consecutive_misses = consecutive_misses + 1 WHERE watch_key = ?",
                arrayOf(watchKey),
            )
            query(
                "drive_watches",
                arrayOf("consecutive_misses"),
                "watch_key = ?",
                arrayOf(watchKey),
                null,
                null,
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        }
    }

    suspend fun rebindQueued(watch: DriveWatch, active: DownloadItem): Boolean = io {
        val queuedId = watch.queueId ?: return@io false
        val nextKey = key(active.type, active.id)
        writableDatabase.transactionResult {
            val collision = query(
                "drive_watches",
                arrayOf("watch_key"),
                "watch_key = ? AND account_scope = ?",
                arrayOf(nextKey, watch.accountScope),
                null,
                null,
                null,
            ).use { it.moveToFirst() }
            if (collision) {
                delete("drive_files", "watch_key = ?", arrayOf(watch.watchKey))
                return@transactionResult delete(
                    "drive_watches",
                    "watch_key = ? AND queue_id = ?",
                    arrayOf(watch.watchKey, queuedId),
                ) == 1
            }
            update(
                "drive_watches",
                ContentValues().apply {
                    put("watch_key", nextKey)
                    put("item_id", active.id)
                    put("item_type", active.type.name)
                    put("name", active.name)
                    put("has_been_seen", 1)
                    put("consecutive_misses", 0)
                    putNull("queue_id")
                    active.hash?.takeIf(String::isNotBlank)?.let { put("source_hash", it) }
                },
                "watch_key = ? AND queue_id = ? AND account_scope = ?",
                arrayOf(watch.watchKey, queuedId, watch.accountScope),
            ) == 1
        }
    }

    suspend fun ensureFileTransfers(watchKey: String, files: List<DownloadFile>) = io {
        writableDatabase.transaction {
            files.forEach { file ->
                val transferKey = "$watchKey:${file.id}"
                insertWithOnConflict(
                    "drive_files",
                    null,
                    ContentValues().apply {
                        put("transfer_key", transferKey)
                        put("watch_key", watchKey)
                        put("file_id", file.id)
                        put("file_name", file.name)
                        put("state", if (file.infected) DriveFileState.FAILED.name else DriveFileState.PENDING.name)
                        putNull("job_id")
                        put("attempts", 0)
                        putNull("last_attempt_at")
                        if (file.infected) put("last_error", "TorBox marked this file as infected.")
                        else putNull("last_error")
                    },
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
        }
    }

    suspend fun fileTransfers(watchKey: String): List<DriveFileTransfer> = io {
        readableDatabase.query(
            "drive_files",
            arrayOf(
                "transfer_key",
                "watch_key",
                "file_id",
                "file_name",
                "state",
                "job_id",
                "attempts",
                "last_attempt_at",
                "last_error",
            ),
            "watch_key = ?",
            arrayOf(watchKey),
            null,
            null,
            "file_id ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    runCatching {
                        DriveFileTransfer(
                            transferKey = cursor.getString(0),
                            downloadKey = cursor.getString(1),
                            fileId = cursor.getLong(2),
                            fileName = cursor.getString(3),
                            state = DriveFileState.valueOf(cursor.getString(4)),
                            jobId = cursor.longOrNull(5),
                            attempts = cursor.getInt(6),
                            lastAttemptAt = cursor.longOrNull(7)?.let(Instant::ofEpochMilli),
                            lastError = cursor.stringOrNull(8),
                        )
                    }.getOrNull()?.let(::add)
                }
            }
        }
    }

    /** Claims one not-yet-submitted file and records the attempt before network I/O. */
    suspend fun claimFileForSubmission(transferKey: String, at: Instant = Instant.now()): Boolean = io {
        writableDatabase.transactionResult {
            val claimed = update(
                "drive_files",
                ContentValues().apply {
                    put("state", DriveFileState.SUBMITTING.name)
                    put("last_attempt_at", at.toEpochMilli())
                    putNull("last_error")
                },
                "transfer_key = ? AND state = ?",
                arrayOf(transferKey, DriveFileState.PENDING.name),
            ) == 1
            if (claimed) {
                execSQL(
                    "UPDATE drive_files SET attempts = attempts + 1 WHERE transfer_key = ?",
                    arrayOf(transferKey),
                )
            }
            claimed
        }
    }

    suspend fun markFileSubmitted(transferKey: String, jobId: Long? = null) = io {
        writableDatabase.update(
            "drive_files",
            ContentValues().apply {
                put("state", DriveFileState.SUBMITTED.name)
                if (jobId == null) putNull("job_id") else put("job_id", jobId)
                putNull("last_error")
            },
            "transfer_key = ?",
            arrayOf(transferKey),
        )
    }

    suspend fun markFileComplete(transferKey: String, jobId: Long?) = io {
        writableDatabase.update(
            "drive_files",
            ContentValues().apply {
                put("state", DriveFileState.COMPLETE.name)
                if (jobId != null) put("job_id", jobId)
                putNull("last_error")
            },
            "transfer_key = ?",
            arrayOf(transferKey),
        )
    }

    suspend fun markFileFailed(transferKey: String, message: String, jobId: Long? = null) = io {
        writableDatabase.update(
            "drive_files",
            ContentValues().apply {
                put("state", DriveFileState.FAILED.name)
                if (jobId != null) put("job_id", jobId)
                put("last_error", message.take(MAX_ERROR_LENGTH))
            },
            "transfer_key = ?",
            arrayOf(transferKey),
        )
    }

    suspend fun retryAmbiguousIfStale(
        transferKey: String,
        staleBefore: Instant,
        maxAttempts: Int,
    ): Boolean = io {
        writableDatabase.transactionResult {
            data class Row(val state: String, val attempts: Int, val lastAttempt: Long?, val jobId: Long?)
            val row = query(
                "drive_files",
                arrayOf("state", "attempts", "last_attempt_at", "job_id"),
                "transfer_key = ?",
                arrayOf(transferKey),
                null,
                null,
                null,
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else Row(
                    state = cursor.getString(0),
                    attempts = cursor.getInt(1),
                    lastAttempt = cursor.longOrNull(2),
                    jobId = cursor.longOrNull(3),
                )
            } ?: return@transactionResult false
            if (row.state !in setOf(DriveFileState.SUBMITTING.name, DriveFileState.SUBMITTED.name) || row.jobId != null) {
                return@transactionResult false
            }
            if (row.lastAttempt == null || Instant.ofEpochMilli(row.lastAttempt).isAfter(staleBefore)) {
                return@transactionResult false
            }
            if (row.attempts >= maxAttempts) {
                update(
                    "drive_files",
                    ContentValues().apply {
                        put("state", DriveFileState.FAILED.name)
                        put("last_error", "Could not confirm that TorBox accepted the Drive transfer.")
                    },
                    "transfer_key = ?",
                    arrayOf(transferKey),
                )
                return@transactionResult false
            }
            update(
                "drive_files",
                ContentValues().apply {
                    put("state", DriveFileState.PENDING.name)
                    putNull("last_error")
                },
                "transfer_key = ?",
                arrayOf(transferKey),
            ) == 1
        }
    }

    suspend fun finishWatchIfTerminal(watchKey: String): DriveWatchState? = io {
        writableDatabase.transactionResult {
            val states = query(
                "drive_files",
                arrayOf("state"),
                "watch_key = ?",
                arrayOf(watchKey),
                null,
                null,
                null,
            ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
            if (states.isEmpty()) return@transactionResult null
            val terminal = states.all {
                it == DriveFileState.COMPLETE.name || it == DriveFileState.FAILED.name
            }
            if (!terminal) return@transactionResult null
            val completed = states.count { it == DriveFileState.COMPLETE.name }
            val failed = states.size - completed
            val state = when {
                completed == states.size -> DriveWatchState.COMPLETE
                completed == 0 -> DriveWatchState.FAILED
                else -> DriveWatchState.PARTIAL_FAILURE
            }
            update(
                "drive_watches",
                ContentValues().apply {
                    put("state", state.name)
                    if (failed == 0) putNull("last_error")
                    else put("last_error", "$failed Drive file transfer${if (failed == 1) "" else "s"} failed.")
                },
                "watch_key = ?",
                arrayOf(watchKey),
            )
            state
        }
    }

    suspend fun activeCount(accountScope: String): Int = io {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM drive_watches WHERE account_scope = ? AND state IN (?, ?, ?)",
            arrayOf(
                accountScope,
                DriveWatchState.WAITING.name,
                DriveWatchState.AUTH_REQUIRED.name,
                DriveWatchState.TRANSFERRING.name,
            ),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    private fun watches(accountScope: String, states: Set<DriveWatchState>): List<DriveWatch> {
        if (states.isEmpty()) return emptyList()
        val placeholders = states.joinToString(",") { "?" }
        val args = arrayOf(accountScope, *states.map(DriveWatchState::name).toTypedArray())
        return readableDatabase.rawQuery(
            """SELECT watch_key, account_scope, item_id, item_type, name, armed_at, has_been_seen,
                      consecutive_misses, queue_id, source_hash, source_value, state, last_error
               FROM drive_watches
               WHERE account_scope = ? AND state IN ($placeholders)
               ORDER BY armed_at ASC""".trimIndent(),
            args,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    runCatching {
                        DriveWatch(
                            watchKey = cursor.getString(0),
                            accountScope = cursor.getString(1),
                            downloadId = cursor.getString(2),
                            type = DownloadType.valueOf(cursor.getString(3)),
                            name = cursor.getString(4),
                            armedAt = Instant.ofEpochMilli(cursor.getLong(5)),
                            hasBeenSeen = cursor.getInt(6) != 0,
                            consecutiveMisses = cursor.getInt(7),
                            queueId = cursor.stringOrNull(8),
                            sourceHash = cursor.stringOrNull(9),
                            sourceValue = cursor.stringOrNull(10),
                            state = DriveWatchState.valueOf(cursor.getString(11)),
                            lastError = cursor.stringOrNull(12),
                        )
                    }.getOrNull()?.let(::add)
                }
            }
        }
    }

    private fun watchValues(
        watchKey: String,
        accountScope: String,
        itemId: String,
        type: DownloadType,
        name: String,
        hasBeenSeen: Boolean,
        queueId: String?,
        sourceHash: String?,
        sourceValue: String?,
    ) = ContentValues().apply {
        put("watch_key", watchKey)
        put("account_scope", accountScope)
        put("item_id", itemId)
        put("item_type", type.name)
        put("name", name)
        put("armed_at", Instant.now().toEpochMilli())
        put("has_been_seen", if (hasBeenSeen) 1 else 0)
        put("consecutive_misses", 0)
        if (queueId == null) putNull("queue_id") else put("queue_id", queueId)
        if (sourceHash == null) putNull("source_hash") else put("source_hash", sourceHash)
        if (sourceValue == null) putNull("source_value") else put("source_value", sourceValue)
        put("state", DriveWatchState.WAITING.name)
        putNull("last_error")
    }

    private suspend fun updateWatchState(watchKey: String, state: DriveWatchState, message: String?) = io {
        writableDatabase.update(
            "drive_watches",
            ContentValues().apply {
                put("state", state.name)
                if (message == null) putNull("last_error") else put("last_error", message.take(MAX_ERROR_LENGTH))
            },
            "watch_key = ?",
            arrayOf(watchKey),
        )
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try {
            block()
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }

    private fun <T> SQLiteDatabase.transactionResult(block: SQLiteDatabase.() -> T): T {
        beginTransaction()
        try {
            return block().also { setTransactionSuccessful() }
        } finally {
            endTransaction()
        }
    }

    private fun android.database.Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
    private fun android.database.Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

    companion object {
        private const val DATABASE_NAME = "torbox_drive_automation.db"
        private const val DATABASE_VERSION = 1
        private const val MAX_ERROR_LENGTH = 500

        fun key(type: DownloadType, id: String): String = "${type.name}:$id"
        fun queuedKey(type: DownloadType, id: String): String = "QUEUE:${type.name}:$id"
    }
}
