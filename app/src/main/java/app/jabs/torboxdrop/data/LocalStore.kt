package app.jabs.torboxdrop.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.jabs.torboxdrop.model.Bookmark
import app.jabs.torboxdrop.model.DownloadFile
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import app.jabs.torboxdrop.model.NotificationSubscription
import app.jabs.torboxdrop.model.QueuedDownload
import app.jabs.torboxdrop.model.RecentSend
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class LocalStore(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE downloads (cache_key TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE queue_items (cache_key TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE files (cache_key TEXT PRIMARY KEY NOT NULL, download_key TEXT NOT NULL, payload TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX files_download_key ON files(download_key)")
        db.execSQL(
            """CREATE TABLE subscriptions (
                cache_key TEXT PRIMARY KEY NOT NULL,
                item_id TEXT NOT NULL,
                item_type TEXT NOT NULL,
                name TEXT NOT NULL,
                armed_at INTEGER NOT NULL,
                notified_at INTEGER,
                has_been_seen INTEGER NOT NULL,
                consecutive_misses INTEGER NOT NULL,
                queue_id TEXT,
                source_hash TEXT,
                source_value TEXT,
                delivery_state TEXT NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE recent_sends (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                value TEXT NOT NULL,
                item_type TEXT NOT NULL,
                sent_at INTEGER NOT NULL,
                source TEXT NOT NULL
            )""".trimIndent(),
        )
        db.execSQL(
            """CREATE TABLE bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                url TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL
            )""".trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE subscriptions ADD COLUMN queue_id TEXT")
            db.execSQL("ALTER TABLE subscriptions ADD COLUMN source_hash TEXT")
            db.execSQL("ALTER TABLE subscriptions ADD COLUMN source_value TEXT")
        }
    }

    suspend fun replaceDownloads(items: List<DownloadItem>) = io {
        writableDatabase.transaction {
            delete("downloads", null, null)
            items.forEach { item ->
                insertWithOnConflict(
                    "downloads",
                    null,
                    ContentValues().apply {
                        put("cache_key", item.key)
                        put("payload", item.toJson().toString())
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
    }

    suspend fun loadDownloads(): List<DownloadItem> = io {
        readableDatabase.query("downloads", arrayOf("payload"), null, null, null, null, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    runCatching { JSONObject(cursor.getString(0)).toDownload() }.getOrNull()?.let(::add)
                }
            }
        }
    }

    suspend fun replaceQueue(items: List<QueuedDownload>) = io {
        writableDatabase.transaction {
            delete("queue_items", null, null)
            items.forEach { item ->
                insertWithOnConflict(
                    "queue_items",
                    null,
                    ContentValues().apply {
                        put("cache_key", item.key)
                        put("payload", item.toJson().toString())
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
    }

    suspend fun loadQueue(): List<QueuedDownload> = io {
        readableDatabase.query("queue_items", arrayOf("payload"), null, null, null, null, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    runCatching { JSONObject(cursor.getString(0)).toQueue() }.getOrNull()?.let(::add)
                }
            }
        }
    }

    suspend fun saveFiles(type: DownloadType, downloadId: String, files: List<DownloadFile>) = io {
        val parentKey = key(type, downloadId)
        writableDatabase.transaction {
            delete("files", "download_key = ?", arrayOf(parentKey))
            files.forEach { file ->
                insertWithOnConflict(
                    "files",
                    null,
                    ContentValues().apply {
                        put("cache_key", "$parentKey:${file.id}")
                        put("download_key", parentKey)
                        put("payload", file.toJson().toString())
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
        }
    }

    suspend fun loadFiles(type: DownloadType, downloadId: String): List<DownloadFile> = io {
        readableDatabase.query(
            "files",
            arrayOf("payload"),
            "download_key = ?",
            arrayOf(key(type, downloadId)),
            null,
            null,
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    runCatching { JSONObject(cursor.getString(0)).toFile() }.getOrNull()?.let(::add)
                }
            }
        }
    }

    /** Searches only file metadata that has already been cached, without loading every file list. */
    suspend fun searchCachedFileDownloadKeys(query: String): Set<String> = io {
        val needle = query.trim()
        if (needle.isEmpty()) return@io emptySet()
        val terms = needle.split(Regex("\\s+")).filter(String::isNotBlank)
        readableDatabase.query(
            "files",
            arrayOf("download_key", "payload"),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    val payload = runCatching { JSONObject(cursor.getString(1)) }.getOrNull() ?: continue
                    val haystack = buildString {
                        append(payload.optString("name"))
                        append(' ')
                        append(payload.optString("path"))
                        append(' ')
                        append(payload.optString("mimeType"))
                    }
                    if (terms.all { haystack.contains(it, ignoreCase = true) }) {
                        add(cursor.getString(0))
                    }
                }
            }
        }
    }

    suspend fun addRecent(value: String, type: DownloadType, source: String, sentAt: Instant = Instant.now()) = io {
        writableDatabase.transaction {
            insert(
                "recent_sends",
                null,
                ContentValues().apply {
                    put("value", value)
                    put("item_type", type.name)
                    put("sent_at", sentAt.toEpochMilli())
                    put("source", source)
                },
            )
            execSQL(
                "DELETE FROM recent_sends WHERE id NOT IN (SELECT id FROM recent_sends ORDER BY sent_at DESC LIMIT 30)",
            )
        }
    }

    suspend fun recentSends(): List<RecentSend> = io {
        readableDatabase.query(
            "recent_sends",
            arrayOf("id", "value", "item_type", "sent_at", "source"),
            null,
            null,
            null,
            null,
            "sent_at DESC",
            "30",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    runCatching {
                        RecentSend(
                            id = cursor.getLong(0),
                            value = cursor.getString(1),
                            type = DownloadType.valueOf(cursor.getString(2)),
                            sentAt = Instant.ofEpochMilli(cursor.getLong(3)),
                            source = cursor.getString(4),
                        )
                    }.getOrNull()?.let(::add)
                }
            }
        }
    }

    suspend fun clearRecentSends() = io { writableDatabase.delete("recent_sends", null, null) }

    suspend fun addBookmark(title: String, url: String) = io {
        writableDatabase.insertWithOnConflict(
            "bookmarks",
            null,
            ContentValues().apply {
                put("title", title.ifBlank { url })
                put("url", url)
                put("created_at", Instant.now().toEpochMilli())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    suspend fun removeBookmark(url: String) = io {
        writableDatabase.delete("bookmarks", "url = ?", arrayOf(url))
    }

    suspend fun bookmarks(): List<Bookmark> = io {
        readableDatabase.query(
            "bookmarks",
            arrayOf("id", "title", "url", "created_at"),
            null,
            null,
            null,
            null,
            "created_at DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Bookmark(
                            id = cursor.getLong(0),
                            title = cursor.getString(1),
                            url = cursor.getString(2),
                            createdAt = Instant.ofEpochMilli(cursor.getLong(3)),
                        ),
                    )
                }
            }
        }
    }

    suspend fun armSubscription(item: DownloadItem, hasBeenSeen: Boolean = true) = io {
        writableDatabase.insertWithOnConflict(
            "subscriptions",
            null,
            ContentValues().apply {
                put("cache_key", item.key)
                put("item_id", item.id)
                put("item_type", item.type.name)
                put("name", item.name)
                put("armed_at", Instant.now().toEpochMilli())
                putNull("notified_at")
                put("has_been_seen", if (hasBeenSeen) 1 else 0)
                put("consecutive_misses", 0)
                putNull("queue_id")
                putNull("source_hash")
                putNull("source_value")
                put("delivery_state", STATE_ARMED)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    suspend fun armQueuedSubscription(
        type: DownloadType,
        queuedId: String,
        name: String,
        sourceHash: String?,
        sourceValue: String?,
    ) = io {
        writableDatabase.insertWithOnConflict(
            "subscriptions",
            null,
            ContentValues().apply {
                put("cache_key", queuedKey(type, queuedId))
                put("item_id", queuedId)
                put("item_type", type.name)
                put("name", name)
                put("armed_at", Instant.now().toEpochMilli())
                putNull("notified_at")
                put("has_been_seen", 0)
                put("consecutive_misses", 0)
                put("queue_id", queuedId)
                if (sourceHash == null) putNull("source_hash") else put("source_hash", sourceHash)
                if (sourceValue == null) putNull("source_value") else put("source_value", sourceValue)
                put("delivery_state", STATE_ARMED)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    suspend fun disarmSubscription(type: DownloadType, id: String) = io {
        writableDatabase.delete("subscriptions", "cache_key = ?", arrayOf(key(type, id)))
    }

    suspend fun disarmQueuedSubscription(type: DownloadType, id: String) = io {
        writableDatabase.delete("subscriptions", "cache_key = ?", arrayOf(queuedKey(type, id)))
    }

    suspend fun armedSubscriptions(): List<NotificationSubscription> = subscriptions(STATE_ARMED)

    suspend fun pendingSubscriptions(): List<NotificationSubscription> = subscriptions(STATE_PENDING)

    suspend fun monitoredSubscriptions(): List<NotificationSubscription> =
        armedSubscriptions() + pendingSubscriptions()

    private suspend fun subscriptions(state: String): List<NotificationSubscription> = io {
        readableDatabase.query(
            "subscriptions",
            arrayOf(
                "cache_key",
                "item_id",
                "item_type",
                "name",
                "armed_at",
                "notified_at",
                "has_been_seen",
                "consecutive_misses",
                "queue_id",
                "source_hash",
                "source_value",
            ),
            "delivery_state = ?",
            arrayOf(state),
            null,
            null,
            "armed_at ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    runCatching {
                        NotificationSubscription(
                            subscriptionKey = cursor.getString(0),
                            downloadId = cursor.getString(1),
                            type = DownloadType.valueOf(cursor.getString(2)),
                            name = cursor.getString(3),
                            armedAt = Instant.ofEpochMilli(cursor.getLong(4)),
                            notifiedAt = cursor.longOrNull(5)?.let(Instant::ofEpochMilli),
                            hasBeenSeen = cursor.getInt(6) != 0,
                            consecutiveMisses = cursor.getInt(7),
                            queueId = cursor.stringOrNull(8),
                            sourceHash = cursor.stringOrNull(9),
                            sourceValue = cursor.stringOrNull(10),
                        )
                    }.getOrNull()?.let(::add)
                }
            }
        }
    }

    suspend fun rebindQueuedSubscription(
        type: DownloadType,
        queuedId: String,
        active: DownloadItem,
    ): Boolean = io {
        writableDatabase.transactionResult {
            val queuedKey = queuedKey(type, queuedId)
            val activeKey = key(type, active.id)
            val activeAlreadyWatched = query(
                "subscriptions",
                arrayOf("cache_key"),
                "cache_key = ?",
                arrayOf(activeKey),
                null,
                null,
                null,
            ).use { it.moveToFirst() }
            if (activeAlreadyWatched) {
                return@transactionResult delete(
                    "subscriptions",
                    "cache_key = ? AND queue_id = ?",
                    arrayOf(queuedKey, queuedId),
                ) == 1
            }
            update(
                "subscriptions",
                ContentValues().apply {
                    put("cache_key", activeKey)
                    put("item_id", active.id)
                    put("name", active.name)
                    put("has_been_seen", 1)
                    put("consecutive_misses", 0)
                    putNull("queue_id")
                },
                "cache_key = ? AND queue_id = ?",
                arrayOf(queuedKey, queuedId),
            ) == 1
        }
    }

    suspend fun markSubscriptionSeen(subscriptionKey: String, currentName: String? = null) = io {
        writableDatabase.update(
            "subscriptions",
            ContentValues().apply {
                put("has_been_seen", 1)
                put("consecutive_misses", 0)
                currentName?.takeIf(String::isNotBlank)?.let { put("name", it) }
            },
            "cache_key = ?",
            arrayOf(subscriptionKey),
        )
    }

    suspend fun recordSubscriptionMiss(subscriptionKey: String): Int = io {
        writableDatabase.transactionResult {
            execSQL(
                "UPDATE subscriptions SET consecutive_misses = consecutive_misses + 1 WHERE cache_key = ?",
                arrayOf(subscriptionKey),
            )
            query(
                "subscriptions",
                arrayOf("consecutive_misses"),
                "cache_key = ?",
                arrayOf(subscriptionKey),
                null,
                null,
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        }
    }

    /** Atomically moves ARMED to PENDING. A stable notification ID handles a retry after process death. */
    suspend fun claimReady(subscriptionKey: String): Boolean = io {
        writableDatabase.transactionResult {
            val state = query(
                "subscriptions",
                arrayOf("delivery_state"),
                "cache_key = ?",
                arrayOf(subscriptionKey),
                null,
                null,
                null,
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            if (state == STATE_PENDING) return@transactionResult true
            if (state != STATE_ARMED) return@transactionResult false
            update(
                "subscriptions",
                ContentValues().apply { put("delivery_state", STATE_PENDING) },
                "cache_key = ? AND delivery_state = ?",
                arrayOf(subscriptionKey, STATE_ARMED),
            ) == 1
        }
    }

    suspend fun markNotificationPosted(subscriptionKey: String) = io {
        writableDatabase.update(
            "subscriptions",
            ContentValues().apply {
                put("delivery_state", STATE_POSTED)
                put("notified_at", Instant.now().toEpochMilli())
            },
            "cache_key = ?",
            arrayOf(subscriptionKey),
        )
    }

    suspend fun disarmSubscription(subscriptionKey: String) = io {
        writableDatabase.delete("subscriptions", "cache_key = ?", arrayOf(subscriptionKey))
    }

    suspend fun clearDownloadCache() = io {
        writableDatabase.transaction {
            delete("downloads", null, null)
            delete("queue_items", null, null)
            delete("files", null, null)
        }
    }

    suspend fun clearAccountScopedData() = io {
        writableDatabase.transaction {
            delete("downloads", null, null)
            delete("queue_items", null, null)
            delete("files", null, null)
            delete("subscriptions", null, null)
        }
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

    private val DownloadItem.key: String get() = key(type, id)
    private val QueuedDownload.key: String get() = key(type, id)

    private fun key(type: DownloadType, id: String) = "${type.name}:$id"
    private fun queuedKey(type: DownloadType, id: String) = "QUEUE:${type.name}:$id"

    private fun DownloadItem.toJson() = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("name", name)
        put("rawState", rawState)
        put("friendlyState", friendlyState)
        putNullable("progress", progress)
        putNullable("totalSize", totalSize)
        putNullable("downloadedBytes", downloadedBytes)
        putNullable("downloadSpeed", downloadSpeed)
        putNullable("uploadSpeed", uploadSpeed)
        putNullable("etaSeconds", etaSeconds)
        putNullable("seeds", seeds)
        putNullable("peers", peers)
        putNullable("ratio", ratio)
        putNullable("availability", availability)
        putInstant("createdAt", createdAt)
        putInstant("updatedAt", updatedAt)
        putInstant("cachedAt", cachedAt)
        putInstant("expiresAt", expiresAt)
        put("tags", JSONArray(tags))
        put("airLocked", airLocked)
        put("downloadFinished", downloadFinished)
        put("downloadPresent", downloadPresent)
        put("cached", cached)
        putNullable("privateTorrent", privateTorrent)
        putNullable("hash", hash)
        putNullable("trackerMessage", trackerMessage)
        putNullable("originalSource", originalSource)
        putNullable("fileCount", fileCount)
        putNullable("allowZip", allowZip)
        putNullable("error", error)
        putNullable("sourceJson", sourceJson)
    }

    private fun JSONObject.toDownload() = DownloadItem(
        id = getString("id"),
        type = DownloadType.valueOf(getString("type")),
        name = getString("name"),
        rawState = optString("rawState"),
        friendlyState = optString("friendlyState", "Unknown"),
        progress = doubleOrNull("progress"),
        totalSize = longOrNull("totalSize"),
        downloadedBytes = longOrNull("downloadedBytes"),
        downloadSpeed = longOrNull("downloadSpeed"),
        uploadSpeed = longOrNull("uploadSpeed"),
        etaSeconds = longOrNull("etaSeconds"),
        seeds = intOrNull("seeds"),
        peers = intOrNull("peers"),
        ratio = doubleOrNull("ratio"),
        availability = doubleOrNull("availability"),
        createdAt = instantOrNull("createdAt"),
        updatedAt = instantOrNull("updatedAt"),
        cachedAt = instantOrNull("cachedAt"),
        expiresAt = instantOrNull("expiresAt"),
        tags = optJSONArray("tags")?.stringList().orEmpty(),
        airLocked = optBoolean("airLocked"),
        downloadFinished = optBoolean("downloadFinished"),
        downloadPresent = optBoolean("downloadPresent"),
        cached = optBoolean("cached"),
        privateTorrent = booleanOrNull("privateTorrent"),
        hash = stringOrNull("hash"),
        trackerMessage = stringOrNull("trackerMessage"),
        originalSource = stringOrNull("originalSource"),
        fileCount = intOrNull("fileCount"),
        allowZip = booleanOrNull("allowZip"),
        error = stringOrNull("error"),
        sourceJson = stringOrNull("sourceJson"),
    )

    private fun QueuedDownload.toJson() = JSONObject().apply {
        put("id", id)
        put("type", type.name)
        put("name", name)
        putInstant("queuedAt", queuedAt)
        putNullable("source", source)
        putNullable("sourceJson", sourceJson)
    }

    private fun JSONObject.toQueue() = QueuedDownload(
        id = getString("id"),
        type = DownloadType.valueOf(getString("type")),
        name = getString("name"),
        queuedAt = instantOrNull("queuedAt"),
        source = stringOrNull("source"),
        sourceJson = stringOrNull("sourceJson"),
    )

    private fun DownloadFile.toJson() = JSONObject().apply {
        put("id", id)
        put("downloadId", downloadId)
        put("downloadType", downloadType.name)
        put("name", name)
        putNullable("path", path)
        putNullable("size", size)
        putNullable("mimeType", mimeType)
        put("infected", infected)
    }

    private fun JSONObject.toFile() = DownloadFile(
        id = getLong("id"),
        downloadId = getString("downloadId"),
        downloadType = DownloadType.valueOf(getString("downloadType")),
        name = getString("name"),
        path = stringOrNull("path"),
        size = longOrNull("size"),
        mimeType = stringOrNull("mimeType"),
        infected = optBoolean("infected"),
    )

    private fun JSONObject.putNullable(name: String, value: Any?) {
        put(name, value ?: JSONObject.NULL)
    }

    private fun JSONObject.putInstant(name: String, value: Instant?) {
        putNullable(name, value?.toEpochMilli())
    }

    private fun JSONObject.stringOrNull(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)

    private fun JSONObject.longOrNull(name: String): Long? =
        if (!has(name) || isNull(name)) null else getLong(name)

    private fun JSONObject.intOrNull(name: String): Int? =
        if (!has(name) || isNull(name)) null else getInt(name)

    private fun JSONObject.doubleOrNull(name: String): Double? =
        if (!has(name) || isNull(name)) null else getDouble(name)

    private fun JSONObject.booleanOrNull(name: String): Boolean? =
        if (!has(name) || isNull(name)) null else getBoolean(name)

    private fun JSONObject.instantOrNull(name: String): Instant? = longOrNull(name)?.let(Instant::ofEpochMilli)

    private fun JSONArray.stringList(): List<String> = buildList {
        for (index in 0 until length()) optString(index).takeIf(String::isNotBlank)?.let(::add)
    }

    private fun android.database.Cursor.longOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private fun android.database.Cursor.stringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private companion object {
        const val DATABASE_NAME = "torbox_drop.db"
        const val DATABASE_VERSION = 2
        const val STATE_ARMED = "ARMED"
        const val STATE_PENDING = "PENDING"
        const val STATE_POSTED = "POSTED"
    }
}
