package app.jabs.torboxdrop.drive

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/** A cloud submission must never be replayed by an HTTP follow-up or authentication retry. */
internal fun driveJsonBody(json: String): RequestBody {
    val delegate = json.toRequestBody("application/json; charset=utf-8".toMediaType())
    return object : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
        override fun isOneShot() = true
    }
}
