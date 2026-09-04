package app.jabs.torboxdrop.notifications

import java.nio.ByteBuffer
import java.security.MessageDigest

object CompletionNotificationIdentity {
    fun tag(subscriptionKey: String): String = "torbox-ready:$subscriptionKey"

    fun id(subscriptionKey: String): Int {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(subscriptionKey.toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(digest, 0, Int.SIZE_BYTES).int and Int.MAX_VALUE
    }
}
