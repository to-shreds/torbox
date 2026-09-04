package app.jabs.torboxdrop.util

import java.math.RoundingMode
import java.text.DecimalFormat
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.absoluteValue

object DisplayFormatters {
    private val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")

    fun bytes(bytes: Long?): String {
        if (bytes == null || bytes < 0) return "—"
        if (bytes < 1024) return "$bytes B"
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return "${decimal(value)} ${units[unit]}"
    }

    fun speed(bytesPerSecond: Long?): String =
        if (bytesPerSecond == null || bytesPerSecond < 0) "—" else "${bytes(bytesPerSecond)}/s"

    /** Formats an ETA compactly enough for a dense download row. */
    fun eta(seconds: Long?): String {
        if (seconds == null || seconds < 0 || seconds == Long.MAX_VALUE) return "—"
        if (seconds < 60) return "${seconds}s"
        if (seconds < 3_600) {
            val minutes = seconds / 60
            val remainder = seconds % 60
            return if (remainder == 0L) "${minutes}m" else "${minutes}m ${remainder}s"
        }
        if (seconds < 86_400) {
            val hours = seconds / 3_600
            val minutes = (seconds % 3_600) / 60
            return if (minutes == 0L) "${hours}h" else "${hours}h ${minutes}m"
        }
        val days = seconds / 86_400
        val hours = (seconds % 86_400) / 3_600
        return if (hours == 0L) "${days}d" else "${days}d ${hours}h"
    }

    /** [percent] is a percentage in the range 0..100, not a fraction. */
    fun progress(percent: Double?): String {
        if (percent == null || !percent.isFinite()) return "—"
        val safe = percent.coerceIn(0.0, 100.0)
        return if (safe % 1.0 == 0.0) "${safe.toInt()}%" else "${decimal(safe)}%"
    }

    fun progressFraction(fraction: Double?): String =
        if (fraction == null || !fraction.isFinite()) "—" else progress(fraction.coerceIn(0.0, 1.0) * 100)

    fun dateTime(
        instant: Instant?,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        if (instant == null) return "—"
        val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", locale)
        return formatter.format(instant.atZone(zoneId))
    }

    fun expiry(expiresAt: Instant?, now: Instant = Instant.now()): String {
        if (expiresAt == null) return "No expiry information"
        if (!expiresAt.isAfter(now)) return "Expired"
        val seconds = Duration.between(now, expiresAt).seconds
        return when {
            seconds < 60 -> "Expires soon"
            seconds < 3_600 -> quantity("Expires in", seconds / 60, "minute")
            seconds < 86_400 -> quantity("Expires in", seconds / 3_600, "hour")
            else -> quantity("Expires in", seconds / 86_400, "day")
        }
    }

    fun updatedAgo(updatedAt: Instant?, now: Instant = Instant.now()): String {
        if (updatedAt == null) return "Update time unknown"
        val seconds = Duration.between(updatedAt, now).seconds.coerceAtLeast(0)
        return when {
            seconds < 60 -> "Updated ${seconds}s ago"
            seconds < 3_600 -> "Updated ${seconds / 60}m ago"
            seconds < 86_400 -> "Updated ${seconds / 3_600}h ago"
            else -> "Updated ${seconds / 86_400}d ago"
        }
    }

    private fun decimal(value: Double): String {
        val pattern = if (value.absoluteValue >= 100) "0" else "0.#"
        return DecimalFormat(pattern).apply {
            roundingMode = RoundingMode.HALF_UP
            isGroupingUsed = false
        }.format(value)
    }

    private fun quantity(prefix: String, count: Long, unit: String): String =
        "$prefix $count $unit${if (count == 1L) "" else "s"}"
}
