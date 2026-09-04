package app.jabs.torboxdrop.ui

import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import app.jabs.torboxdrop.util.middleEllipsizeFilename
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

fun formatBytes(value: Long?): String? {
    if (value == null || value < 0) return null
    if (value < 1_000L) return "$value B"
    val units = arrayOf("KB", "MB", "GB", "TB", "PB")
    var amount = value.toDouble()
    var unit = -1
    while (amount >= 1_000.0 && unit < units.lastIndex) {
        amount /= 1_000.0
        unit++
    }
    val digits = when {
        amount >= 100 -> 0
        amount >= 10 -> 1
        else -> 2
    }
    return "% .${digits}f %s".format(Locale.getDefault(), amount, units[unit]).trimStart()
}

fun formatRate(bytesPerSecond: Long?): String? =
    bytesPerSecond?.takeIf { it >= 0 }?.let { "${formatBytes(it)}/s" }

fun formatEta(seconds: Long?): String? {
    if (seconds == null || seconds < 0 || seconds == Long.MAX_VALUE) return null
    if (seconds < 60) return "${seconds}s"
    val minutes = seconds / 60
    if (minutes < 60) return "${minutes}m"
    val hours = minutes / 60
    val remainingMinutes = minutes % 60
    if (hours < 24) return if (remainingMinutes == 0L) "${hours}h" else "${hours}h ${remainingMinutes}m"
    val days = hours / 24
    val remainingHours = hours % 24
    return if (remainingHours == 0L) "${days}d" else "${days}d ${remainingHours}h"
}

fun formatInstant(instant: Instant?): String? = instant
    ?.atZone(ZoneId.systemDefault())
    ?.format(DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", Locale.getDefault()))

fun formatRelativeUpdate(instant: Instant?, now: Instant = Instant.now()): String? {
    if (instant == null) return null
    val seconds = abs(Duration.between(instant, now).seconds)
    return when {
        seconds < 5 -> "Updated now"
        seconds < 60 -> "Updated ${seconds}s ago"
        seconds < 3_600 -> "Updated ${seconds / 60}m ago"
        seconds < 86_400 -> "Updated ${seconds / 3_600}h ago"
        else -> formatInstant(instant)?.let { "Updated $it" }
    }
}

fun formatExpiry(instant: Instant?, now: Instant = Instant.now()): String? {
    if (instant == null) return null
    val duration = Duration.between(now, instant)
    if (duration.isNegative || duration.isZero) return "Expired"
    val hours = duration.toHours()
    return when {
        hours < 1 -> "Expires in ${duration.toMinutes().coerceAtLeast(1)}m"
        hours < 24 -> "Expires in ${hours}h"
        else -> "Expires in ${duration.toDays()}d"
    }
}

fun compactDisplayName(name: String, maxCharacters: Int = 62): String {
    return middleEllipsizeFilename(name, maxCharacters)
}

fun activeMetadata(item: DownloadItem): String = buildList {
    add(item.friendlyState)
    formatRate(item.downloadSpeed)?.let(::add)
    formatEta(item.etaSeconds)?.let { add("$it left") }
    if (item.type == DownloadType.TORRENT) {
        listOfNotNull(
            item.seeds?.let { "${it}S" },
            item.peers?.let { "${it}P" },
        ).joinToString(" / ").takeIf(String::isNotEmpty)?.let(::add)
    }
}.joinToString(" · ")

fun normalizedProgress(progress: Double?): Float {
    if (progress == null || !progress.isFinite()) return 0f
    return progress.coerceIn(0.0, 1.0).toFloat()
}

fun percentLabel(progress: Double?): String = if (progress == null || !progress.isFinite()) {
    "—"
} else {
    "%.0f%%".format(Locale.getDefault(), normalizedProgress(progress) * 100f)
}

/**
 * Returns the best server-derived fraction available for display without interpolating progress.
 *
 * Authoritative ready-and-present state displays 100%. For unfinished items, valid byte counts
 * take precedence so the percentage, progress bar, and adjacent "downloaded of total" text cannot
 * contradict one another. The API's raw 0.0..1.0 progress remains the fallback for records without
 * usable byte counts.
 */
fun displayProgressFraction(item: DownloadItem): Float? {
    if (item.isReady) return 1f

    val total = item.totalSize
    val downloaded = item.downloadedBytes
    if (total != null && total > 0L && downloaded != null && downloaded in 0L..total) {
        return (downloaded.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }

    item.progress?.takeIf(Double::isFinite)?.let { return normalizedProgress(it) }
    return null
}

fun percentLabel(item: DownloadItem): String = displayProgressFraction(item)?.let { fraction ->
    "%.0f%%".format(Locale.getDefault(), fraction * 100f)
} ?: "—"
