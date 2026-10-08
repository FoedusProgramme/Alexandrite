package org.foedusprogramme.alexandrite.sdk.transcript

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import java.security.MessageDigest

/** Where the bytes of a [MediaPart] are. */
@SubclassOptInRequired(InternalAlexandriteApi::class)
public interface MediaSource

/** Media in the agent's media store. */
@OptIn(InternalAlexandriteApi::class)
@Poko
public class StoredMedia(public val id: MediaId) : MediaSource

/** Media bytes that a request or a tool result carries. */
@OptIn(InternalAlexandriteApi::class)
public class InlineMedia(bytes: ByteArray) : MediaSource {
    private val bytes = bytes.copyOf()

    public val size: Int get() = bytes.size

    /** A copy of the bytes. */
    public fun bytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean = other is InlineMedia && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "InlineMedia(size=$size, sha256=${digest.take(8).joinToString("") { "%02x".format(it) }})"
    }
}

/** A media source of a type this version does not know, as stored. */
@OptIn(InternalAlexandriteApi::class)
@Poko
public class UnknownMedia(
    public val type: String,
    /** The stored object, its type included. */
    public val json: JsonObject,
) : MediaSource

/** What kind of media a [MediaPart] holds. */
@JvmInline
@Serializable
public value class MediaKind internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        public val IMAGE: MediaKind = MediaKind("image")

        public val AUDIO: MediaKind = MediaKind("audio")

        public val VIDEO: MediaKind = MediaKind("video")

        /** A document or any other file. */
        public val FILE: MediaKind = MediaKind("file")

        /** The values this version knows. */
        public val entries: List<MediaKind> = listOf(IMAGE, AUDIO, VIDEO, FILE)

        /** The value of [id], which keeps an id this version does not know. */
        public fun of(id: String): MediaKind = MediaKind(id)
    }
}
