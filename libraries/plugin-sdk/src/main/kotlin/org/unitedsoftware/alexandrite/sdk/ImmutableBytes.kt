package org.unitedsoftware.alexandrite.sdk

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest

/** A copy of [bytes] that no one changes, equal to another of the same bytes. */
internal class ImmutableBytes(bytes: ByteArray) {
    private val bytes = bytes.copyOf()

    val size: Int get() = bytes.size

    fun toByteArray(): ByteArray = bytes.copyOf()

    /** A stream of the bytes, which copies none of them. */
    fun inputStream(): InputStream = ByteArrayInputStream(bytes)

    /** The first bytes of their SHA-256 in lowercase hex, which tell them apart in a log. */
    fun digestPrefix(): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .take(SHOWN_DIGEST_BYTES)
        .joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?): Boolean = other is ImmutableBytes && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    private companion object {
        const val SHOWN_DIGEST_BYTES = 8
    }
}
