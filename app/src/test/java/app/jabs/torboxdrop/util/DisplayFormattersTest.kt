package app.jabs.torboxdrop.util

import org.junit.Assert.assertEquals
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Test

class DisplayFormattersTest {
    @Test
    fun bytesAndSpeed_areCompactAndHumanReadable() {
        assertEquals("—", DisplayFormatters.bytes(null))
        assertEquals("—", DisplayFormatters.bytes(-1))
        assertEquals("0 B", DisplayFormatters.bytes(0))
        assertEquals("1.5 KB", DisplayFormatters.bytes(1_536))
        assertEquals("1 GB", DisplayFormatters.bytes(1_073_741_824))
        assertEquals("11.4 MB/s", DisplayFormatters.speed(11_943_936))
    }

    @Test
    fun eta_handlesSecondsMinutesHoursDaysAndUnknown() {
        assertEquals("—", DisplayFormatters.eta(null))
        assertEquals("—", DisplayFormatters.eta(Long.MAX_VALUE))
        assertEquals("45s", DisplayFormatters.eta(45))
        assertEquals("4m", DisplayFormatters.eta(240))
        assertEquals("1h 2m", DisplayFormatters.eta(3_725))
        assertEquals("1d 1h", DisplayFormatters.eta(90_000))
    }

    @Test
    fun progress_isExplicitAboutPercentVersusFractionAndClamps() {
        assertEquals("68.3%", DisplayFormatters.progress(68.25))
        assertEquals("100%", DisplayFormatters.progress(120.0))
        assertEquals("0%", DisplayFormatters.progress(-10.0))
        assertEquals("68.3%", DisplayFormatters.progressFraction(0.6825))
        assertEquals("—", DisplayFormatters.progress(Double.NaN))
    }

    @Test
    fun dateTime_usesProvidedTimezone() {
        val formatted = DisplayFormatters.dateTime(
            Instant.parse("2026-09-04T04:30:00Z"),
            ZoneId.of("America/New_York"),
            Locale.US,
        )

        assertEquals("Sep 4, 2026 · 12:30 AM", formatted)
    }

    @Test
    fun expiry_andUpdatedAgo_handleBoundaries() {
        val now = Instant.parse("2026-09-04T00:00:00Z")

        assertEquals("Expired", DisplayFormatters.expiry(now.minusSeconds(1), now))
        assertEquals("Expires soon", DisplayFormatters.expiry(now.plusSeconds(30), now))
        assertEquals("Expires in 11 days", DisplayFormatters.expiry(now.plusSeconds(86_400L * 11), now))
        assertEquals("Updated 8s ago", DisplayFormatters.updatedAgo(now.minusSeconds(8), now))
    }
}
