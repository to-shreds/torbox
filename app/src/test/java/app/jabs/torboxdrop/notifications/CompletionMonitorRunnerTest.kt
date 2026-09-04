package app.jabs.torboxdrop.notifications

import app.jabs.torboxdrop.data.TorBoxBadTokenException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CompletionMonitorRunnerTest {
    @Test
    fun readyDownloadIsClaimedPostedMarkedAndDisarmedOnce() = runTest {
        val dependency = FakeDependencies(CompletionState.Ready)
        var posts = 0
        val runner = CompletionMonitorRunner(dependency) {
            posts++
            true
        }

        val first = runner.runOnce()
        val second = runner.runOnce()

        assertThat(first.delivered).isEqualTo(1)
        assertThat(first.hasArmedDownloads).isFalse()
        assertThat(second.armedAtStart).isEqualTo(0)
        assertThat(posts).isEqualTo(1)
        assertThat(dependency.marked).isTrue()
        assertThat(dependency.disarmed).isTrue()
    }

    @Test
    fun failedAndroidPostDoesNotMarkOrDisarm() = runTest {
        val dependency = FakeDependencies(CompletionState.Ready)
        val result = CompletionMonitorRunner(dependency) { false }.runOnce()

        assertThat(result.retryableFailures).isEqualTo(1)
        assertThat(result.hasArmedDownloads).isTrue()
        assertThat(dependency.marked).isFalse()
        assertThat(dependency.disarmed).isFalse()
    }

    @Test
    fun definitivelyRemovedDownloadIsDisarmedWithoutNotification() = runTest {
        val dependency = FakeDependencies(CompletionState.Removed)
        var posts = 0
        val result = CompletionMonitorRunner(dependency) {
            posts++
            true
        }.runOnce()

        assertThat(result.removed).isEqualTo(1)
        assertThat(posts).isEqualTo(0)
        assertThat(dependency.disarmed).isTrue()
    }

    @Test
    fun cancellationWhileLoadingInitialSubscriptionsIsRethrown() = runTest {
        val dependency = FakeDependencies(
            completionState = CompletionState.NotReady,
            cancelOnArmedRead = 1,
        )

        assertCancellation(CompletionMonitorRunner(dependency) { true })
    }

    @Test
    fun cancellationWhileCheckingAnItemIsRethrown() = runTest {
        val dependency = FakeDependencies(
            completionState = CompletionState.NotReady,
            cancelOnCheck = true,
        )

        assertCancellation(CompletionMonitorRunner(dependency) { true })
    }

    @Test
    fun cancellationFromNotificationPublisherIsRethrown() = runTest {
        val dependency = FakeDependencies(CompletionState.Ready)

        assertCancellation(
            CompletionMonitorRunner(dependency) {
                throw CancellationException("publisher cancelled")
            },
        )
    }

    @Test
    fun cancellationWhileLoadingFinalSubscriptionsIsRethrown() = runTest {
        val dependency = FakeDependencies(
            completionState = CompletionState.NotReady,
            cancelOnArmedRead = 2,
        )

        assertCancellation(CompletionMonitorRunner(dependency) { true })
    }

    @Test
    fun rejectedTokenStopsBackgroundRetriesWithoutDroppingWatch() = runTest {
        val dependency = FakeDependencies(
            completionState = CompletionState.NotReady,
            badTokenOnCheck = true,
        )

        val result = CompletionMonitorRunner(dependency) { true }.runOnce()

        assertThat(result.authBlocked).isTrue()
        assertThat(result.retryableFailures).isEqualTo(0)
        assertThat(result.hasArmedDownloads).isTrue()
        assertThat(dependency.disarmed).isFalse()
    }

    private suspend fun assertCancellation(runner: CompletionMonitorRunner) {
        val failure = runCatching { runner.runOnce() }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
    }

    private class FakeDependencies(
        private val completionState: CompletionState,
        private val cancelOnArmedRead: Int? = null,
        private val cancelOnCheck: Boolean = false,
        private val badTokenOnCheck: Boolean = false,
    ) : CompletionMonitorDependencies {
        private val item = ArmedDownload(
            subscriptionKey = "torrent:7",
            downloadId = "7",
            type = MonitoredDownloadType.TORRENT,
            displayName = "Example",
        )
        private var claimed = false
        private var armedReads = 0
        var marked = false
        var disarmed = false

        override suspend fun armedDownloads(): List<ArmedDownload> {
            armedReads++
            if (armedReads == cancelOnArmedRead) throw CancellationException("subscriptions cancelled")
            return if (disarmed) emptyList() else listOf(item)
        }

        override suspend fun checkCompletion(download: ArmedDownload): CompletionState {
            if (cancelOnCheck) throw CancellationException("completion check cancelled")
            if (badTokenOnCheck) {
                throw TorBoxBadTokenException("BAD_TOKEN", 401, "The token was rejected")
            }
            return completionState
        }

        override suspend fun claimCompletion(download: ArmedDownload): CompletionClaim? {
            if (claimed) return null
            claimed = true
            return CompletionClaim("claim-7", item)
        }

        override suspend fun markNotificationPosted(claim: CompletionClaim) {
            marked = true
        }

        override suspend fun disarm(download: ArmedDownload) {
            disarmed = true
        }
    }
}
