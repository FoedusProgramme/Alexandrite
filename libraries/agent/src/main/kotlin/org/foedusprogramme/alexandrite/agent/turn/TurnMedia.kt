package org.foedusprogramme.alexandrite.agent.turn

import org.foedusprogramme.alexandrite.agent.prompt.mediaNote
import org.foedusprogramme.alexandrite.sdk.channel.MediaAttachment
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.store.MediaStore
import org.foedusprogramme.alexandrite.sdk.transcript.InlineMedia
import org.foedusprogramme.alexandrite.sdk.transcript.MediaId
import org.foedusprogramme.alexandrite.sdk.transcript.MediaPart
import org.foedusprogramme.alexandrite.sdk.transcript.StoredMedia
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutputPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserPart
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

/** The media of the turn [turn]: its message's attachments put into [store], and the bytes its requests carry. */
internal class TurnMedia(private val turn: TurnId, private val store: MediaStore, private val maxBytes: Long) {
    /** The bytes of the media the turn put or read, null where the store has none. */
    private val loaded = HashMap<MediaId, InlineMedia?>()

    /** The parts that refer to [attachments] in the store, followed by a note on those left out. */
    suspend fun put(attachments: List<MediaAttachment>): List<UserPart> {
        val parts = mutableListOf<UserPart>()
        val leftOut = mutableListOf<Pair<MediaAttachment, String>>()
        for (attachment in attachments) {
            val why = put(attachment, parts) ?: continue
            logger.warn("Turn {} leaves out an attachment of type {} ({})", turn, attachment.mediaType, why)
            leftOut += attachment to why
        }
        if (leftOut.isNotEmpty()) parts += mediaNote(leftOut)
        return parts
    }

    /** [entries] with the stored media they refer to carried inline. */
    suspend fun inline(entries: List<TranscriptEntry>): List<TranscriptEntry> = entries.map { entry ->
        when (entry) {
            is UserEntry -> UserEntry(entry.record, entry.parts.map { userPart(it) }, entry.origin)

            is ToolResultEntry -> ToolResultEntry(
                entry.record,
                entry.callId,
                entry.toolName,
                entry.content.map { outputPart(it) },
                entry.outcome,
            )

            else -> entry
        }
    }

    /** Adds the part that refers to [attachment] in the store to [parts], or returns why it is left out. */
    private suspend fun put(attachment: MediaAttachment, parts: MutableList<UserPart>): String? {
        val tooLarge = "larger than the $maxBytes bytes a message may carry"
        if ((attachment.size ?: 0) > maxBytes) return tooLarge
        val bytes = try {
            attachment.content.read(maxBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("Turn {} cannot load an attachment", turn, e)
            return "which could not be loaded"
        }
        if (bytes.size > maxBytes) return tooLarge
        val stored = try {
            store.put(bytes, attachment.kind, attachment.mediaType)
        } catch (e: IllegalArgumentException) {
            logger.debug("Turn {} cannot store an attachment", turn, e)
            return "which could not be stored"
        }
        loaded[stored.id] = InlineMedia(bytes)
        with(attachment) { parts += MediaPart(kind, mediaType, stored, name, width, height) }
        return null
    }

    private suspend fun userPart(part: UserPart): UserPart =
        if (part is MediaPart) inlined(part) ?: missing(part) else part

    private suspend fun outputPart(part: ToolOutputPart): ToolOutputPart =
        if (part is MediaPart) inlined(part) ?: missing(part) else part

    /** [part] with its stored bytes inline, null where the store has none. */
    private suspend fun inlined(part: MediaPart): MediaPart? {
        val source = part.source as? StoredMedia ?: return part
        val bytes = (if (source.id in loaded) loaded[source.id] else read(source.id)) ?: return null
        return MediaPart(part.kind, part.mediaType, bytes, part.name, part.width, part.height)
    }

    /** What the model reads in place of [part], whose bytes the store no longer has. */
    private fun missing(part: MediaPart): TextPart =
        TextPart("(${part.kind} of type ${part.mediaType}, no longer stored)")

    private suspend fun read(id: MediaId): InlineMedia? {
        val bytes = store.read(id)?.let(::InlineMedia)
        if (bytes == null) logger.warn("Turn {} finds no media {} in the store", turn, id)
        loaded[id] = bytes
        return bytes
    }
}

private val logger = LoggerFactory.getLogger(TurnMedia::class.java)
