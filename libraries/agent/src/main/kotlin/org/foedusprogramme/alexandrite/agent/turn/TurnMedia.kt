package org.foedusprogramme.alexandrite.agent.turn

import org.foedusprogramme.alexandrite.agent.directory.Inbox
import org.foedusprogramme.alexandrite.agent.directory.Saving
import org.foedusprogramme.alexandrite.agent.directory.generatedName
import org.foedusprogramme.alexandrite.agent.directory.inboxName
import org.foedusprogramme.alexandrite.agent.prompt.NotedMedia
import org.foedusprogramme.alexandrite.agent.prompt.mediaNote
import org.foedusprogramme.alexandrite.sdk.channel.IncomingMessage
import org.foedusprogramme.alexandrite.sdk.channel.MediaAttachment
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.store.MediaStore
import org.foedusprogramme.alexandrite.sdk.store.storeInline
import org.foedusprogramme.alexandrite.sdk.transcript.InlineMedia
import org.foedusprogramme.alexandrite.sdk.transcript.MediaId
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
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

/**
 * The media of the turn [turn]: its messages' attachments saved into the [inbox], images put into [store] for a model
 * that views them, and the bytes its requests carry.
 */
internal class TurnMedia(
    private val turn: TurnId,
    private val store: MediaStore,
    private val maxBytes: Long,
    private val inbox: Inbox,
) {
    /** The bytes of the media the turn put or read, null where the store has none. */
    private val loaded = HashMap<MediaId, InlineMedia?>()

    /**
     * The images of [message] that the model is shown where it views [images], followed by the note that names where
     * each attachment was saved in the inbox of [info] or why it was not.
     */
    suspend fun put(info: TurnInfo, message: IncomingMessage, images: Boolean): List<UserPart> {
        val parts = mutableListOf<UserPart>()
        val noted = message.media.mapIndexed { index, attachment ->
            val generated = generatedName(attachment.kind, attachment.mediaType, message.ref.id, index + 1)
            put(info, attachment, generated, images, parts)
        }
        if (noted.isNotEmpty()) parts += mediaNote(noted)
        return parts
    }

    /** [result] with its inline media put into the store, or a note where a part is left out. */
    suspend fun stored(result: ToolResultEntry): ToolResultEntry {
        val small = result.withContent { part ->
            if ((part.inlineBytes()?.size ?: 0) > maxBytes) leftOut(part, "larger than $maxBytes bytes") else part
        }
        val stored = try {
            store.storeInline(small) as ToolResultEntry
        } catch (e: IllegalArgumentException) {
            logger.debug("Turn {} cannot store the media of a tool result", turn, e)
            return small.withContent { if (it.inlineBytes() != null) leftOut(it, "the store refused it") else it }
        }
        for ((part, put) in small.content.zip(stored.content)) {
            val bytes = part.inlineBytes() ?: continue
            val id = ((put as? MediaPart)?.source as? StoredMedia)?.id ?: continue
            loaded[id] = bytes
        }
        return stored
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

    /**
     * Saves [attachment] into the inbox of [info] as its own name or else [generated], and adds the part that shows it
     * to [parts] when it is an image the model views where it views [images].
     */
    private suspend fun put(
        info: TurnInfo,
        attachment: MediaAttachment,
        generated: String,
        images: Boolean,
        parts: MutableList<UserPart>,
    ): NotedMedia {
        attachment.unavailable?.let { return notSaved(attachment, it) }
        val tooLarge = "larger than the $maxBytes bytes a message may carry"
        if ((attachment.size ?: 0) > maxBytes) return notSaved(attachment, tooLarge)
        val bytes = try {
            attachment.content.read(maxBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.debug("Turn {} cannot load an attachment: {}", turn, e.toString())
            return notSaved(attachment, "it could not be loaded")
        }
        if (bytes.size > maxBytes) return notSaved(attachment, tooLarge, bytes.size.toLong())
        val saving = inbox.save(info, attachment.name?.let(::inboxName) ?: generated, bytes)
        val refusal = (saving as? Saving.Refused)?.reason
        refusal?.let {
            logger.warn("Turn {} did not save an attachment of type {}: {}", turn, attachment.mediaType, it)
        }
        val view = when {
            attachment.kind != MediaKind.IMAGE -> null
            !images -> NotedMedia.View.NOT_VIEWED
            attachment.mediaType.lowercase() !in VIEWED_IMAGES -> NotedMedia.View.UNVIEWABLE
            else -> shown(attachment, bytes, parts)
        }
        return NotedMedia(attachment, bytes.size.toLong(), (saving as? Saving.Saved)?.path, refusal, view)
    }

    /** Adds the part that shows [attachment] to [parts], and says how it went. */
    private suspend fun shown(
        attachment: MediaAttachment,
        bytes: ByteArray,
        parts: MutableList<UserPart>,
    ): NotedMedia.View {
        val stored = try {
            store.put(bytes, attachment.kind, attachment.mediaType)
        } catch (e: IllegalArgumentException) {
            logger.debug("Turn {} cannot store an attachment: {}", turn, e.toString())
            return NotedMedia.View.NOT_STORED
        }
        loaded[stored.id] = InlineMedia(bytes)
        with(attachment) { parts += MediaPart(kind, mediaType, stored, name, width, height) }
        return NotedMedia.View.SHOWN
    }

    private fun notSaved(attachment: MediaAttachment, why: String, size: Long? = attachment.size): NotedMedia {
        logger.warn("Turn {} leaves out an attachment of type {} ({})", turn, attachment.mediaType, why)
        return NotedMedia(attachment, size, null, why, null)
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

    /** What the model reads in place of [part] of a tool result, left out for [why]. */
    private fun leftOut(part: ToolOutputPart, why: String): ToolOutputPart {
        if (part !is MediaPart) return part
        logger.warn("Turn {} leaves out a tool result's {} of type {} ({})", turn, part.kind, part.mediaType, why)
        return TextPart("(${part.kind} of type ${part.mediaType}, left out: $why)")
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

private fun ToolOutputPart.inlineBytes(): InlineMedia? = (this as? MediaPart)?.source as? InlineMedia

private fun ToolResultEntry.withContent(part: (ToolOutputPart) -> ToolOutputPart): ToolResultEntry =
    ToolResultEntry(record, callId, toolName, content.map(part), outcome)

/** The types of image that the agent shows a model that views images. */
private val VIEWED_IMAGES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

private val logger = LoggerFactory.getLogger(TurnMedia::class.java)
