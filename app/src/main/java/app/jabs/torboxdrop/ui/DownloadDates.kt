package app.jabs.torboxdrop.ui

import app.jabs.torboxdrop.model.DownloadItem
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun dateAndAge(value: Instant?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    if (value == null) return "Not reported"
    val date = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", Locale.getDefault()).format(value.atZone(zone))
    val seconds = Duration.between(value, now).seconds
    val age = when {
        seconds < -60 -> "in the future"
        seconds < 60 -> "just now"
        seconds < 3600 -> "${seconds / 60}m ago"
        seconds < 86400 -> "${seconds / 3600}h ago"
        else -> "${seconds / 86400}d ago"
    }
    return "$date ($age)"
}

/** cached_at is a cache timestamp, not an invented per-account completion date. */
fun downloadDates(item: DownloadItem): String = buildList {
    add("Added: ${dateAndAge(item.createdAt)}")
    item.cachedAt?.let { add("Cached: ${dateAndAge(it)}") }
}.joinToString("\n")
