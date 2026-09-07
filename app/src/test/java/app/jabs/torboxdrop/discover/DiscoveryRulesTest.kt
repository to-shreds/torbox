package app.jabs.torboxdrop.discover

import org.junit.Assert.*
import org.junit.Test

class DiscoveryRulesTest {
    private val hash = "a".repeat(40)
    @Test fun canonicalHashesAndRejectMalformed() {
        assertEquals(hash, DiscoveryRules.hash("  ${hash.uppercase()}  "))
        listOf("", "a".repeat(39), "a".repeat(41), "g".repeat(40), "$hash?token=secret", "magnet:?xt=urn:btih:$hash")
            .forEach { assertNull(DiscoveryRules.hash(it)) }
        assertNull(DiscoveryRules.hash(null))
    }
    @Test fun exactImdbAndEpisodeIdentifiers() {
        assertEquals("tt1234567", DiscoveryFilter().streamId("tt1234567"))
        assertEquals("tt1234567:2:13", DiscoveryFilter(kind = MediaKind.TV, season = 2, episode = 13).streamId("tt1234567"))
        assertEquals("tt1234567:0:1", DiscoveryFilter(kind = MediaKind.TV, season = 0).streamId("tt1234567"))
        listOf("/tt1234567", "tt1234567:1:2", "tt1234567?token=x", "1234567").forEach { assertFalse(DiscoveryRules.validImdb(it)) }
    }
    @Test(expected = IllegalArgumentException::class) fun episodeZeroRejected() { DiscoveryFilter(episode = 0) }
    @Test fun qualityAndCodecUnknownRemainUnknown() {
        assertEquals("4K", DiscoveryRules.quality("Example.2160p.WEB.H265"))
        assertEquals("1080p", DiscoveryRules.quality("Example 1080p"))
        assertEquals("720p", DiscoveryRules.quality("Example.720p"))
        assertEquals("Unknown quality", DiscoveryRules.quality("Example E1080pX 104kings"))
        assertEquals("HEVC", DiscoveryRules.codec("x265"))
        assertEquals("HEVC", DiscoveryRules.codec("h.265"))
        assertEquals("H.264", DiscoveryRules.codec("H264"))
        assertEquals("AV1", DiscoveryRules.codec("AV1"))
        assertNull(DiscoveryRules.codec("unknown"))
    }
    @Test fun duplicateVariantsUseOneHash() {
        val list = listOf(TorrentCandidate(hash, "A", ""), TorrentCandidate(hash.uppercase(), "B", ""), TorrentCandidate("bad", "C", ""))
        assertEquals(1, DiscoveryRules.deduplicate(list).size)
    }
    @Test fun magnetContainsOnlyValidatedHash() {
        val release = CachedRelease(hash, "https://untrusted/?apikey=secret", null, "Unknown quality", null, 0)
        assertEquals("magnet:?xt=urn:btih:$hash", release.magnet)
    }
    @Test fun binaryUnitsAndUnknownSizes() {
        assertEquals("1.0 GiB", DiscoveryRules.sizeLabel(1_073_741_824))
        assertEquals("Size unknown", DiscoveryRules.sizeLabel(null))
        assertEquals("Size unknown", DiscoveryRules.sizeLabel(-1))
    }
}
