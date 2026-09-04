package app.jabs.torboxdrop.notifications

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CompletionNotificationIdentityTest {
    @Test
    fun identityIsStableAndScopedToSubscription() {
        val key = "torrent:12345"

        assertThat(CompletionNotificationIdentity.id(key))
            .isEqualTo(CompletionNotificationIdentity.id(key))
        assertThat(CompletionNotificationIdentity.tag(key)).isEqualTo("torbox-ready:$key")
        assertThat(CompletionNotificationIdentity.id(key)).isNotEqualTo(Int.MIN_VALUE)
    }

    @Test
    fun differentTypesProduceDifferentIdentity() {
        assertThat(CompletionNotificationIdentity.id("torrent:42"))
            .isNotEqualTo(CompletionNotificationIdentity.id("web:42"))
        assertThat(CompletionNotificationIdentity.tag("torrent:42"))
            .isNotEqualTo(CompletionNotificationIdentity.tag("web:42"))
    }
}
