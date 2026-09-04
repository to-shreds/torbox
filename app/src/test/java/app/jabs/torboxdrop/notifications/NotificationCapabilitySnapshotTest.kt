package app.jabs.torboxdrop.notifications

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotificationCapabilitySnapshotTest {
    @Test
    fun completionRequiresPermissionGlobalSwitchAndCompletionChannel() {
        assertThat(snapshot(runtime = false).blockingIssue(NotificationCapabilityUse.COMPLETION_ALERTS))
            .isEqualTo(NotificationCapabilityIssue.RUNTIME_PERMISSION_REQUIRED)
        assertThat(snapshot(global = false).blockingIssue(NotificationCapabilityUse.COMPLETION_ALERTS))
            .isEqualTo(NotificationCapabilityIssue.APP_NOTIFICATIONS_DISABLED)
        assertThat(snapshot(completion = false).blockingIssue(NotificationCapabilityUse.COMPLETION_ALERTS))
            .isEqualTo(NotificationCapabilityIssue.COMPLETION_CHANNEL_DISABLED)
        assertThat(snapshot(monitoring = false).canPostCompletion).isTrue()
    }

    @Test
    fun liveMonitoringAlsoRequiresMonitoringChannel() {
        val state = snapshot(monitoring = false)

        assertThat(state.canPostCompletion).isTrue()
        assertThat(state.canRunLiveMonitoring).isFalse()
        assertThat(state.blockingIssue(NotificationCapabilityUse.LIVE_MONITORING))
            .isEqualTo(NotificationCapabilityIssue.MONITORING_CHANNEL_DISABLED)
    }

    @Test
    fun fullyEnabledSnapshotAllowsBothUses() {
        val state = snapshot()

        assertThat(state.canPostCompletion).isTrue()
        assertThat(state.canRunLiveMonitoring).isTrue()
        assertThat(state.blockingIssue(NotificationCapabilityUse.COMPLETION_ALERTS)).isNull()
        assertThat(state.blockingIssue(NotificationCapabilityUse.LIVE_MONITORING)).isNull()
    }

    private fun snapshot(
        runtime: Boolean = true,
        global: Boolean = true,
        completion: Boolean = true,
        monitoring: Boolean = true,
    ) = NotificationCapabilitySnapshot(
        runtimePermissionGranted = runtime,
        appNotificationsEnabled = global,
        completionChannelEnabled = completion,
        monitoringChannelEnabled = monitoring,
    )
}
