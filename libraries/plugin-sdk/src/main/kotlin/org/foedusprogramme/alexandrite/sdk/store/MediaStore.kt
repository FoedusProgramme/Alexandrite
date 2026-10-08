package org.foedusprogramme.alexandrite.sdk.store

import dev.drewhamilton.poko.Poko
import org.foedusprogramme.alexandrite.sdk.di.BoundSpi
import org.foedusprogramme.alexandrite.sdk.transcript.InlineMedia
import org.foedusprogramme.alexandrite.sdk.transcript.MediaId
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.sdk.transcript.MediaPart
import org.foedusprogramme.alexandrite.sdk.transcript.StoredMedia
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import java.time.Instant

/**
 * The bytes of the media that entries refer to by [StoredMedia].
 *
 * - Only a store backend implements it, and only the agent calls it.
 * - The store mints each [MediaId], unique and opaque.
 * - Media that no entry refers to may be removed once a day has passed since it was put.
 */
@BoundSpi
public interface MediaStore {
    /**
     * Stores [bytes] as media of [kind] with the IANA media type [mediaType] under an id of its own; throws
     * [IllegalArgumentException] when [mediaType] is blank or the media is larger than the store takes.
     */
    public suspend fun put(bytes: ByteArray, kind: MediaKind, mediaType: String): StoredMedia

    /** The bytes of [id], null when the store has none. */
    public suspend fun read(id: MediaId): ByteArray?

    /** What the store knows of [id], null when it has no such media. */
    public suspend fun info(id: MediaId): MediaInfo?
}

/** [entry] with the media it holds inline put into the store and referred to by [StoredMedia]. */
public suspend fun MediaStore.storeInline(entry: TranscriptEntry): TranscriptEntry = when (entry) {
    is UserEntry -> UserEntry(entry.record, entry.parts.map { if (it is MediaPart) stored(it) else it }, entry.origin)

    is ToolResultEntry -> ToolResultEntry(
        entry.record,
        entry.callId,
        entry.toolName,
        entry.content.map { if (it is MediaPart) stored(it) else it },
        entry.outcome,
    )

    else -> entry
}

private suspend fun MediaStore.stored(part: MediaPart): MediaPart {
    val source = part.source as? InlineMedia ?: return part
    val stored = put(source.bytes(), part.kind, part.mediaType)
    return MediaPart(part.kind, part.mediaType, stored, part.name, part.width, part.height)
}

/** Media as the store keeps it. */
@Poko
public class MediaInfo(
    public val id: MediaId,
    public val kind: MediaKind,
    /** The IANA media type, such as `image/png`. */
    public val mediaType: String,
    /** In bytes. */
    public val size: Long,
    /** The SHA-256 of the bytes in lowercase hex. */
    public val sha256: String,
    public val createdAt: Instant,
)
