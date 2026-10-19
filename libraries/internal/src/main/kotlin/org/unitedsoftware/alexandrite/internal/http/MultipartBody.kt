package org.unitedsoftware.alexandrite.internal.http

import org.unitedsoftware.alexandrite.internal.hash.hex
import java.io.InputStream
import java.net.http.HttpRequest
import java.security.SecureRandom

/** A `multipart/form-data` body of text fields and files, sent with its length and each file streamed. */
public class MultipartBody private constructor(private val parts: List<Part>, private val boundary: String) {
    public val contentType: String = "multipart/form-data; boundary=$boundary"

    /** The body's length in bytes. */
    public val length: Long = parts.sumOf { it.head.size + it.size + CRLF.size } + closing().size

    internal fun publisher(): HttpRequest.BodyPublisher {
        val publishers = parts.flatMap { part ->
            listOf(published(part.head), part.publisher(), published(CRLF))
        }
        return HttpRequest.BodyPublishers.concat(*(publishers + published(closing())).toTypedArray())
    }

    private fun closing(): ByteArray = "--$boundary--\r\n".toByteArray(Charsets.UTF_8)

    public class Builder internal constructor() {
        private val parts = mutableListOf<Pending>()

        public fun field(name: String, value: String): Builder = apply {
            val encoded = value.toByteArray(Charsets.UTF_8)
            parts += Pending(disposition(name, null), null, encoded.size.toLong()) { published(encoded) }
        }

        /** Adds the file [fileName] of [mediaType] as the field [name], its [size] bytes read from [open] when sent. */
        public fun file(
            name: String,
            fileName: String,
            mediaType: String,
            size: Long,
            open: () -> InputStream,
        ): Builder = apply {
            require(size >= 0) { "A file holds at least 0 bytes, was $size." }
            require(mediaType.isNotBlank() && mediaType.none { it == '\r' || it == '\n' }) {
                "Malformed media type '$mediaType'."
            }
            parts += Pending(disposition(name, fileName), mediaType, size) {
                if (size == 0L) {
                    HttpRequest.BodyPublishers.noBody()
                } else {
                    HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(open), size)
                }
            }
        }

        public fun build(): MultipartBody {
            val boundary = "alexandrite-" + hex(ByteArray(BOUNDARY_BYTES).also(RANDOM::nextBytes))
            return MultipartBody(parts.map { it.part(boundary) }, boundary)
        }
    }

    private class Pending(
        val disposition: String,
        val mediaType: String?,
        val size: Long,
        val publisher: () -> HttpRequest.BodyPublisher,
    ) {
        fun part(boundary: String): Part {
            val head = buildString {
                append("--").append(boundary).append("\r\n")
                append("Content-Disposition: ").append(disposition).append("\r\n")
                mediaType?.let { append("Content-Type: ").append(it).append("\r\n") }
                append("\r\n")
            }
            return Part(head.toByteArray(Charsets.UTF_8), size, publisher)
        }
    }

    private class Part(val head: ByteArray, val size: Long, val publisher: () -> HttpRequest.BodyPublisher)

    public companion object {
        public fun builder(): Builder = Builder()
    }
}

/** The `Content-Disposition` value of the field [name], a file named [fileName] unless it is null. */
private fun disposition(name: String, fileName: String?): String =
    "form-data; name=\"${quoted(name)}\"" + fileName?.let { "; filename=\"${quoted(it)}\"" }.orEmpty()

/** [text] with the characters that would end a quoted parameter percent-encoded. */
private fun quoted(text: String): String = text.replace("\"", "%22").replace("\r", "%0D").replace("\n", "%0A")

private fun published(bytes: ByteArray): HttpRequest.BodyPublisher = HttpRequest.BodyPublishers.ofByteArray(bytes)

private val CRLF = "\r\n".toByteArray(Charsets.UTF_8)

private const val BOUNDARY_BYTES = 16

private val RANDOM = SecureRandom()
