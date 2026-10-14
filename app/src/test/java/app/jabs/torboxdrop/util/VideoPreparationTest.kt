package app.jabs.torboxdrop.util

import app.jabs.torboxdrop.model.DownloadItem
import app.jabs.torboxdrop.model.DownloadType
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VideoPreparationTest {
    private val pending = DownloadItem(id = "83", type = DownloadType.WEB, name = "Video", rawState = "downloading")
    @Test fun waitsForBothReadinessFlagsAndReportsProgress() = runTest {
        val ready = pending.copy(downloadFinished = true, downloadPresent = true)
        val states = listOf(null, pending, pending.copy(downloadFinished = true), ready)
        var calls = 0
        val reported = mutableListOf<DownloadItem?>()
        val actual = VideoPreparation.awaitReady(fetch = { states[calls++] }, onStatus = { reported += it }, intervalMillis = 5)
        assertEquals(ready, actual)
        assertEquals(4, calls)
        assertEquals(states, reported)
    }
    @Test fun providerErrorDoesNotPollOrRetryForever() = runTest {
        var calls = 0
        val failure = runCatching { VideoPreparation.awaitReady(fetch = { calls++; pending.copy(error = "Source unavailable") }) }.exceptionOrNull()
        assertTrue(failure?.message?.contains("Source unavailable") == true)
        assertEquals(1, calls)
    }
    @Test fun boundedTimeoutLeavesExistingItemAlone() = runTest {
        var calls = 0
        val failure = runCatching { VideoPreparation.awaitReady(fetch = { calls++; pending }, intervalMillis = 5, timeoutMillis = 12) }.exceptionOrNull()
        assertNotNull(failure)
        assertTrue(calls in 2..3)
        assertTrue(failure!!.message!!.contains("not deleted or added again"))
    }
    @Test fun closingOrSwitchingAccountCancelsPreparation() = runTest {
        var calls = 0
        val job = launch { VideoPreparation.awaitReady(fetch = { calls++; pending }) }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, calls)
    }
    @Test fun inactiveVideoTerminatesWithUsefulError() = runTest {
        val failure = runCatching { VideoPreparation.awaitReady(fetch = { pending.copy(rawState = "inactive") }) }.exceptionOrNull()
        assertNotNull(failure)
    }
}
