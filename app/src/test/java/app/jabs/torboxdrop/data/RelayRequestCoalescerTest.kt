package app.jabs.torboxdrop.data

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RelayRequestCoalescerTest {
    @Test
    fun suppressesEachUserAndTorrentPairForTenSeconds() = runTest {
        var now = 1_000L
        val coalescer = RelayRequestCoalescer(elapsedRealtimeMillis = { now })

        assertThat(coalescer.claim(" user-a ", listOf("one", "one", " two "), 24))
            .containsExactly("one", "two")
            .inOrder()
        assertThat(coalescer.claim("user-a", listOf("one", "two"), 24)).isEmpty()
        assertThat(coalescer.claim("user-b", listOf("one"), 24)).containsExactly("one")

        now += 9_999L
        assertThat(coalescer.claim("user-a", listOf("one"), 24)).isEmpty()
        now += 1L
        assertThat(coalescer.claim("user-a", listOf("one"), 24)).containsExactly("one")
    }

    @Test
    fun concurrentPassesAtomicallyClaimAnIdOnlyOnce() = runTest {
        val coalescer = RelayRequestCoalescer(elapsedRealtimeMillis = { 42L })

        val claimed = List(20) {
            async { coalescer.claim("user", listOf("torrent"), 24) }
        }.awaitAll().flatten()

        assertThat(claimed).containsExactly("torrent")
    }

    @Test
    fun suppressedLeadingIdsDoNotStarveNewIdsInTheSamePass() = runTest {
        var now = 0L
        val coalescer = RelayRequestCoalescer(elapsedRealtimeMillis = { now })
        val existing = (1..24).map { "existing-$it" }

        assertThat(coalescer.claim("user", existing, 24)).hasSize(24)
        now++

        assertThat(coalescer.claim("user", existing + "new", 24)).containsExactly("new")
    }

    @Test
    fun trackedStateIsBoundedAndExpiredEntriesArePruned() = runTest {
        var now = 0L
        val coalescer = RelayRequestCoalescer(
            suppressionWindowMillis = 10_000L,
            maxEntries = 3,
            elapsedRealtimeMillis = { now },
        )

        coalescer.claim("user", listOf("one", "two", "three", "four"), 4)
        assertThat(coalescer.trackedEntryCount()).isEqualTo(3)

        now = 10_000L
        coalescer.claim("user", listOf("fresh"), 4)
        assertThat(coalescer.trackedEntryCount()).isEqualTo(1)
    }
}
