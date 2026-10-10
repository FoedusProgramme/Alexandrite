package org.foedusprogramme.alexandrite.testkit.store

import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.store.ConversationState
import org.foedusprogramme.alexandrite.sdk.store.TranscriptStore
import org.foedusprogramme.alexandrite.sdk.transcript.EntryId
import org.foedusprogramme.alexandrite.sdk.transcript.EntryRecord
import org.foedusprogramme.alexandrite.sdk.transcript.InlineMedia
import org.foedusprogramme.alexandrite.sdk.transcript.MediaId
import org.foedusprogramme.alexandrite.sdk.transcript.MediaPart
import org.foedusprogramme.alexandrite.sdk.transcript.StoredMedia
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptCodec
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserOrigin

internal class MemoryTranscripts(private val data: MemoryData) : TranscriptStore {
    override suspend fun append(turn: TurnId, entries: List<TranscriptEntry>): List<TranscriptEntry> {
        val encoded = entries.map { entry ->
            require(entry.record == null) { "Entry ${entry.record?.id} is stored already." }
            require(entry.mediaParts().none { it.source is InlineMedia }) {
                "An entry to store holds inline media: store them first, with MediaStore.storeInline."
            }
            entry to TranscriptCodec.encode(entry)
        }
        return data.locked { now ->
            val conversation = turns[turn]?.conversation?.let(conversations::getValue)
                ?: throw IllegalArgumentException("Turn $turn is not recorded.")
            check(conversation.state == ConversationState.ACTIVE) {
                "Conversation ${conversation.id} is ${conversation.state}, so it takes no new entries."
            }
            for ((entry, _) in encoded) {
                val unknown = entry.storedMedia().firstOrNull { it !in media }
                require(unknown == null) { "An entry to store refers to the unknown media $unknown." }
            }
            encoded.map { (entry, body) ->
                val record = EntryRecord(EntryId(++lastEntry), conversation.id, turn, now)
                val message = ((entry as? UserEntry)?.origin as? UserOrigin.FromChat)?.message
                this.entries[record.id.value] = StoredEntry(record, body, message, entry.storedMedia())
                entry.withRecord(record)
            }
        }
    }

    override suspend fun entries(conversation: ConversationId): List<TranscriptEntry> =
        data.locked { entries.values.filter { it.record.conversation == conversation } }.map { it.read() }

    override suspend fun entriesAfter(conversation: ConversationId, after: EntryId): List<TranscriptEntry> =
        data.locked {
            entries.values.filter { it.record.conversation == conversation && it.record.id.value > after.value }
        }.map { it.read() }

    override suspend fun tail(conversation: ConversationId, count: Int): List<TranscriptEntry> {
        require(count >= 0) { "A tail has no negative length, was $count." }
        return entries(conversation).takeLast(count)
    }

    override suspend fun turnEntries(turn: TurnId): List<TranscriptEntry> =
        data.locked { entries.values.filter { it.record.turn == turn } }.map { it.read() }

    override suspend fun entry(id: EntryId): TranscriptEntry? = data.locked { entries[id.value] }?.read()

    override suspend fun entries(message: ChannelMessageRef): List<TranscriptEntry> =
        data.locked { entries.values.filter { it.message == message } }.map { it.read() }

    override suspend fun deleteMessage(message: ChannelMessageRef): Int = data.locked {
        val deleted = entries.values.filter { it.message == message }
        deleted.forEach { entries.remove(it.record.id.value) }
        val referred = entries.values.flatMapTo(HashSet()) { it.media }
        deleted.flatMap { it.media }.filter { it !in referred }.forEach { media.remove(it) }
        deleted.size
    }

    private fun StoredEntry.read(): TranscriptEntry = TranscriptCodec.decode(body).withRecord(record)
}

private fun TranscriptEntry.storedMedia(): Set<MediaId> =
    mediaParts().mapNotNullTo(LinkedHashSet()) { (it.source as? StoredMedia)?.id }

private fun TranscriptEntry.mediaParts(): List<MediaPart> = when (this) {
    is UserEntry -> parts.filterIsInstance<MediaPart>()
    is ToolResultEntry -> content.filterIsInstance<MediaPart>()
    else -> emptyList()
}
