package app.jabs.torboxdrop.mp4

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.jabs.torboxdrop.R
import app.jabs.torboxdrop.TorBoxDropApplication
import app.jabs.torboxdrop.drive.driveAccountScope
import app.jabs.torboxdrop.model.DownloadFile
import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import app.jabs.torboxdrop.util.UrlSafety
import app.jabs.torboxdrop.util.YouTubeMp4Policy
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Each explicit Save as MP4 starts one durable WorkManager job. Only identifiers and a
 * one-way account fingerprint are persisted; TorBox credentials/CDN URLs are not.
 *
 * When the remote video is not yet ready, the job retries instead of occupying a
 * foreground service for the entire TorBox preparation period.
 *
 * Download and transcode use phone storage and MediaCodec, not the Render relay.
 */
@OptIn(UnstableApi::class)
class YouTubeMp4Worker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    private val application = context.applicationContext as TorBoxDropApplication
    private val notificationId = 21000 + (id.hashCode() and 0x3fff)
    private var lastNotificationTime = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foreground("Preparing MP4 job", null)

    override suspend fun doWork(): Result {
        val downloadId = inputData.getString(KEY_WEB_ID).orEmpty()
        val requestedFileId = inputData.getLong(KEY_FILE_ID, -1)
        val account = inputData.getString(KEY_ACCOUNT).orEmpty()
        val sourceFromAdd = inputData.getString(KEY_YOUTUBE_SOURCE)
        val token = application.container.tokenStore.read()
        if (downloadId.isEmpty() || account.isEmpty() || driveAccountScope(token) != account) {
            return failure("TorBox account changed. Start a new MP4 job from the current account.")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return failure("Save as MP4 requires Android 10 or newer to write to Downloads.")
        }
        var label = inputData.getString(KEY_NAME).orEmpty().ifBlank { "YouTube video" }
        val directory = File(application.filesDir, "youtube-mp4/" + id)
        val original = File(directory, "source.webm")
        val partial = File(directory, "source.download")
        val converted = File(directory, "output.mp4")
        val completeMarker = File(directory, "output.complete")

        try {
            val item = application.container.api.getDownload(DownloadType.WEB, downloadId, bypassCache = true)
                ?: return failure("The TorBox video is no longer available.")
            // A present, non-YouTube source is NEVER replaced with the user-submitted
            // value. This guards against an unexpected/recycled TorBox record ID.
            val actual = YouTubeMp4Policy.canonicalSource(item.originalSource)
            if (!item.originalSource.isNullOrBlank() && actual == null) {
                return failure("TorBox returned a non-YouTube source; no MP4 was saved.")
            }
            val source = actual
                ?: YouTubeMp4Policy.canonicalSource(sourceFromAdd)
                ?: return failure("This item is not an identifiable YouTube video.")
            if (actual != null && sourceFromAdd != null && actual != sourceFromAdd) {
                return failure("The TorBox video identity changed; nothing was saved.")
            }
            if (!item.isReady) {
                if (runAttemptCount >= MAX_PREPARATION_ATTEMPTS) {
                    return failure("TorBox has not completed this YouTube video. Start a new MP4 job if it later finishes.")
                }
                setProgress(workDataOf(KEY_PHASE to "Waiting for TorBox to finish the video"))
                return Result.retry()
            }

            val files = application.container.api.getFiles(DownloadType.WEB, downloadId, bypassCache = true)
            val video: DownloadFile = if (requestedFileId >= 0) {
                files.firstOrNull { it.id == requestedFileId }
                    ?: return failure("This video file is no longer available on TorBox.")
            } else {
                val videos = files.filter { !it.infected && YouTubeMp4Policy.isSupportedVideo(it) }
                if (videos.size != 1) {
                    return failure("The video has multiple files or no supported video. Open Files and choose one to save as MP4.")
                }
                videos.single()
            }
            if (video.infected || !YouTubeMp4Policy.isSupportedVideo(video)) {
                return failure("This file cannot be converted safely to MP4.")
            }
            label = YouTubeMp4Policy.outputFilename(video.name)
            // Reject cross-account/cross-identity results before the first media byte is fetched.
            if (driveAccountScope(application.container.tokenStore.read()) != account) {
                return failure("TorBox account changed before the download began.")
            }

            setForeground(foreground("Downloading " + label, 0))
            directory.mkdirs()
            val sourceFile = if (video.name.endsWith(".mp4", ignoreCase = true)) {
                File(directory, "source.mp4")
            } else original

            val isAlreadyConverted = completeMarker.exists() && converted.length() > 0L &&
                !video.name.endsWith(".mp4", ignoreCase = true)
            if (!isAlreadyConverted && (!sourceFile.exists() || sourceFile.length() <= 0L)) {
                val link = application.container.api.requestTemporaryDownloadUrl(
                    DownloadType.WEB, downloadId, fileId = video.id, appendName = true,
                )
                if (!UrlSafety.isSafeForDeviceDownload(link, token)) {
                    return failure("TorBox supplied an unsafe file location. No media was downloaded.")
                }
                fetchDirectlyFromTorBox(link, sourceFile, partial, video.size, label)
            }

            // The existing MP4 requires only a local copy. WebM requires an actual
            // H.264/AAC encode (renaming WebM would not produce a valid MP4).
            val output = if (video.name.endsWith(".mp4", ignoreCase = true)) {
                sourceFile
            } else {
                if (!completeMarker.exists() || converted.length() <= 0) {
                    completeMarker.delete()
                    converted.delete()
                    transcode(sourceFile, converted, label)
                    if (converted.length() <= 0) throw IOException("The MP4 encoder produced an empty file.")
                    completeMarker.writeText("complete")
                }
                converted
            }
            if (driveAccountScope(application.container.tokenStore.read()) != account) {
                return failure("TorBox account changed during conversion. The MP4 was not published.")
            }
            // Discard the downloaded WebM after conversion. This halves the storage
            // required while Android copies the MP4 into public Downloads.
            if (output != sourceFile) sourceFile.delete()
            setForeground(foreground("Saving " + label + " to Downloads", 99))
            val uri = publishToDownloads(output, label)
            setProgress(workDataOf(KEY_PHASE to "Saved to Downloads", KEY_PERCENT to 100, KEY_OUTPUT_URI to uri.toString()))
            notifyFinished(label, success = true)
            directory.deleteRecursively()
            return Result.success(workDataOf(KEY_OUTPUT_URI to uri.toString()))
        } catch (cancelled: CancellationException) {
            // WorkManager cancellation must stop MediaCodec and active transfer.
            throw cancelled
        } catch (error: Exception) {
            val retryable = error is IOException && runAttemptCount < MAX_NETWORK_ATTEMPTS
            val detail = UrlSafety.redact(error.message ?: "Media processing error", token)
                .replace(Regex("https?://\\S+"), "[private link]")
                .take(240)
            val safe = when (error) {
                is IOException -> "Could not download or save this video: " + detail
                else -> "MP4 conversion failed: " + detail
            }
            if (retryable) {
                setProgress(workDataOf(KEY_PHASE to "Retrying: " + safe))
                return Result.retry()
            }
            return failure(safe)
        }
    }

    private suspend fun failure(message: String): Result {
        // Finished failures must not strand multi-gigabyte partial files forever.
        withContext(Dispatchers.IO) {
            File(application.filesDir, "youtube-mp4/" + id).deleteRecursively()
        }
        setProgress(workDataOf(KEY_PHASE to message))
        notifyFinished(message, success = false)
        return Result.failure(workDataOf(KEY_PHASE to message))
    }

    private fun foreground(message: String, percent: Int?): ForegroundInfo {
        createChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("TorBox Drop: Save as MP4")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, percent ?: 0, percent == null)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
            ).build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, type)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private suspend fun showProgress(label: String, percent: Int) {
        val now = System.currentTimeMillis()
        if (now - lastNotificationTime < 1200) return
        lastNotificationTime = now
        setProgress(workDataOf(KEY_PHASE to label, KEY_PERCENT to percent))
        setForeground(foreground(label, percent))
    }

    private suspend fun fetchDirectlyFromTorBox(
        link: String,
        output: File,
        partial: File,
        expectedSize: Long?,
        label: String,
    ) = withContext(Dispatchers.IO) {
        // This client does not follow redirects that might expose credential-bearing URLs
        // to an untrusted destination. The source URL was validated by UrlSafety.
        val client = OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
        val start = partial.takeIf { it.exists() }?.length() ?: 0L
        if (start > 0 && expectedSize != null && start == expectedSize) {
            if (!partial.renameTo(output)) throw IOException("Could not finish the resumed local download.")
            return@withContext
        }
        val request = Request.Builder().url(link)
            .header("Accept", "application/octet-stream")
            .apply { if (start > 0) header("Range", "bytes=" + start + "-") }
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code !in listOf(200, 206)) {
                throw IOException("TorBox storage returned HTTP " + response.code + ".")
            }
            if (response.code == 206 && start > 0) {
                val range = response.header("Content-Range").orEmpty()
                if (!range.startsWith("bytes " + start + "-")) {
                    partial.delete()
                    throw IOException("Storage returned the wrong resume range.")
                }
            }
            val append = start > 0 && response.code == 206
            val body = response.body ?: throw IOException("TorBox returned no video data.")
            val total = expectedSize?.takeIf { it > 0 }
                ?: body.contentLength().takeIf { it > 0 }?.plus(if (append) start else 0)
            if (!append && start > 0) partial.delete()
            // Leave enough capacity for an MP4 export as well as the source; fail visibly
            // rather than filling all internal storage on a multi-gigabyte video.
            if (total != null) {
                val free = StatFs(application.filesDir.absolutePath).availableBytes
                val remaining = (total - if (append) start else 0).coerceAtLeast(0)
                if (free < remaining + total + 256L * 1024 * 1024) {
                    throw IOException("Not enough device storage for download and MP4 conversion.")
                }
            }
            FileOutputStream(partial, append).use { file ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    var downloaded = if (append) start else 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        if (isStopped) throw CancellationException("MP4 job cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        file.write(buffer, 0, count)
                        downloaded += count
                        val progress = total?.takeIf { it > 0 }?.let {
                            ((downloaded * 45) / it).toInt().coerceIn(0, 45)
                        } ?: 0
                        showProgress("Downloading " + label, progress)
                    }
                }
            }
            if (total != null && partial.length() != total) {
                throw IOException("The TorBox download stopped before all bytes arrived.")
            }
            if (partial.length() == 0L || !partial.renameTo(output)) {
                throw IOException("Could not finish the local video download.")
            }
        }
    }

    private suspend fun transcode(source: File, output: File, label: String) {
        val finished = CompletableDeferred<Unit>()
        // Media3 Transformer requires all control operations on the same Looper (main).
        // Actual decode, encode and muxing occur on Android's media codec threads.
        val transformer = withContext(Dispatchers.Main.immediate) {
            Transformer.Builder(applicationContext)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        finished.complete(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException,
                    ) {
                        finished.completeExceptionally(exception)
                    }
                })
                .build()
                .also {
                    it.start(
                        EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source))).build(),
                        output.absolutePath,
                    )
                }
        }
        try {
            while (!finished.isCompleted) {
                delay(1200)
                val percent = withContext(Dispatchers.Main.immediate) {
                    val holder = ProgressHolder()
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        holder.progress.coerceIn(0, 100)
                    } else null
                }
                showProgress(
                    "Converting " + label + " to MP4",
                    percent?.let { 45 + it * 53 / 100 } ?: 45,
                )
            }
            finished.await()
        } finally {
            if (!finished.isCompleted || isStopped) {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    transformer.cancel()
                }
            }
        }
    }

    private suspend fun publishToDownloads(source: File, filename: String): Uri =
        withContext(Dispatchers.IO) {
            val resolver = application.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Android could not create the MP4 in Downloads.")
            try {
                resolver.openOutputStream(uri, "w")?.use { output ->
                    source.inputStream().buffered().use { it.copyTo(output, 256 * 1024) }
                } ?: throw IOException("Android could not write the MP4 to Downloads.")
                val published = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                if (resolver.update(uri, published, null, null) != 1) {
                    throw IOException("Android could not finalize the MP4 in Downloads.")
                }
                uri
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = application.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "MP4 conversion jobs", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notifyFinished(message: String, success: Boolean) {
        createChannel()
        val notification: Notification = NotificationCompat.Builder(application, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (success) "MP4 saved to Downloads" else "Save as MP4 failed")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(application).notify(notificationId + 100000, notification)
        } catch (_: SecurityException) {
            // Notification permission can be denied; job output is still persisted.
        }
    }

    companion object {
        private const val CHANNEL_ID = "torbox_mp4_background"
        private const val KEY_WEB_ID = "web_id"
        private const val KEY_FILE_ID = "file_id"
        private const val KEY_ACCOUNT = "account_scope"
        private const val KEY_NAME = "name"
        private const val KEY_YOUTUBE_SOURCE = "youtube_source"
        private const val KEY_PHASE = "phase"
        private const val KEY_PERCENT = "percent"
        private const val KEY_OUTPUT_URI = "output_uri"
        private const val MAX_PREPARATION_ATTEMPTS = 150
        private const val MAX_NETWORK_ATTEMPTS = 6

        /** Only called from a deliberate user tap; does not store tokens or file links. */
        fun enqueue(
            context: Context,
            accountScope: String,
            downloadId: String,
            fileId: Long = -1L,
            filename: String = "YouTube video",
            youtubeSourceFromAdd: String? = null,
        ) {
            require(downloadId.matches(Regex("[0-9]+")))
            require(fileId >= -1L)
            require(accountScope.matches(Regex("[0-9a-f]{64}")))
            val canonical = youtubeSourceFromAdd?.let {
                requireNotNull(YouTubeMp4Policy.canonicalSource(it))
            }
            val unique = "youtube-mp4-" + accountScope.take(16) + "-" + downloadId + "-" + fileId
            val data = Data.Builder()
                .putString(KEY_ACCOUNT, accountScope)
                .putString(KEY_WEB_ID, downloadId)
                .putLong(KEY_FILE_ID, fileId)
                .putString(KEY_NAME, filename.take(140))
                .apply { if (canonical != null) putString(KEY_YOUTUBE_SOURCE, canonical) }
                .build()
            val request = OneTimeWorkRequestBuilder<YouTubeMp4Worker>()
                .setInputData(data)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag("youtube-mp4")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(unique, ExistingWorkPolicy.KEEP, request)
        }
    }
}
