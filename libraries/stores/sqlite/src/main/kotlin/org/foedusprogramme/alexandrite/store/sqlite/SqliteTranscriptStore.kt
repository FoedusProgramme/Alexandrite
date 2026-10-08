package org.foedusprogramme.alexandrite.store.sqlite

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.di.Binds
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.store.ConversationState
import org.foedusprogramme.alexandrite.sdk.store.TranscriptStore
import org.foedusprogramme.alexandrite.sdk.transcript.EntryId
import org.foedusprogramme.alexandrite.sdk.transcript.EntryRecord
import org.foedusprogramme.alexandrite.sdk.transcript.InlineMedia
import org.foedusprogramme.alexandrite.sdk.transcript.MediaId
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptCodec
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UnknownEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserOrigin
import org.slf4j.LoggerFactory

@Singleton
@Binds(TranscriptStore::class)
internal class SqliteTranscriptStore(private val database: StoreDatabase, private val media: SqliteMediaStore) :
    TranscriptStore {
    override suspend fun append(turn: TurnId, entries: List<TranscriptEntry>): List<TranscriptEntry> {
        val encoded = entries.map { entry ->
            require(entry.record == null) { "Entry ${entry.record?.id} is stored already." }
            require(entry.mediaParts().none { it.source is InlineMedia }) {
                "An entry to store holds inline media: store them first, with MediaStore.storeInline."
            }
            Encoded(entry, TranscriptCodec.encode(entry))
        }
        return database.transaction {
            val (conversation, state) = queryOne(
                "SELECT c.id, c.state FROM turns t JOIN conversations c ON c.id = t.conversation WHERE t.id = ?",
                turn.value,
            ) { ConversationId(getString(1)) to ConversationState.of(getString(2)) }
                ?: throw IllegalArgumentException("Turn $turn is not recorded.")
            check(state == ConversationState.ACTIVE) {
                "Conversation $conversation is $state, so it takes no new entries."
            }
            encoded.map { insert(conversation, turn, it) }
        }
    }

    override suspend fun entries(conversation: ConversationId): List<TranscriptEntry> = database.transaction {
        rows("WHERE conversation = ? ORDER BY id", conversation.value)
    }.map { it.entry() }

    override suspend fun tail(conversation: ConversationId, count: Int): List<TranscriptEntry> {
        require(count >= 0) { "A tail has no negative length, was $count." }
        return database.transaction {
            rows("WHERE conversation = ? ORDER BY id DESC LIMIT ?", conversation.value, count)
        }.asReversed().map { it.entry() }
    }

    override suspend fun entry(id: EntryId): TranscriptEntry? =
        database.transaction { rows("WHERE id = ?", id.value) }.singleOrNull()?.entry()

    override suspend fun entries(message: ChannelMessageRef): List<TranscriptEntry> = database.transaction {
        val chat = chatIdOrNull(message.chat) ?: return@transaction emptyList()
        rows("WHERE message_chat_id = ? AND message_id = ? ORDER BY id", chat, message.id)
    }.map { it.entry() }

    override suspend fun deleteMessage(message: ChannelMessageRef): Int = database.transaction {
        val chat = chatIdOrNull(message.chat) ?: return@transaction 0
        val ids = query("SELECT id FROM entries WHERE message_chat_id = ? AND message_id = ?", chat, message.id) {
            getLong(1)
        }
        val referred = ids.flatMapTo(LinkedHashSet()) { id ->
            query("SELECT media_id FROM entry_media WHERE entry_id = ?", id) { MediaId(getString(1)) }
        }
        for (id in ids) {
            execute("DELETE FROM entry_media WHERE entry_id = ?", id)
            execute("DELETE FROM entries WHERE id = ?", id)
        }
        media.deleteUnreferenced(this, referred)
        ids.size
    }

    private fun Tx.insert(conversation: ConversationId, turn: TurnId, encoded: Encoded): TranscriptEntry {
        val message = ((encoded.entry as? UserEntry)?.origin as? UserOrigin.FromChat)?.message
        val id = queryOne(
            "INSERT INTO entries (conversation, turn, type, created_at, format, body, message_chat_id, message_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
            conversation.value,
            turn.value,
            encoded.type,
            now,
            TranscriptCodec.FORMAT_VERSION,
            encoded.body,
            message?.let { chatId(it.chat) },
            message?.id,
        ) { getLong(1) } ?: throw IllegalStateException("The store gave the entry no id.")
        for (mediaId in encoded.entry.storedMedia()) {
            requireNotNull(queryOne("SELECT 1 FROM media WHERE id = ?", mediaId.value) { true }) {
                "An entry to store refers to the unknown media $mediaId."
            }
            execute("INSERT INTO entry_media (entry_id, media_id) VALUES (?, ?)", id, mediaId.value)
        }
        return encoded.entry.withRecord(EntryRecord(EntryId(id), conversation, turn, now))
    }

    private fun Tx.rows(where: String, vararg arguments: Any?): List<Row> = query(
        "SELECT id, conversation, turn, created_at, format, type, body FROM entries $where",
        *arguments,
    ) {
        Row(
            EntryRecord(
                EntryId(getLong("id")),
                ConversationId(getString("conversation")),
                TurnId(getString("turn")),
                instant("created_at"),
            ),
            getInt("format"),
            getString("type"),
            getString("body"),
        )
    }

    private class Encoded(val entry: TranscriptEntry, val body: String) {
        val type: String = Json.parseToJsonElement(body).jsonObject.getValue("type").jsonPrimitive.content
    }

    private class Row(val record: EntryRecord, val format: Int, val type: String, val body: String) {
        /** The stored entry, an [UnknownEntry] when its format or its content is not one this version reads. */
        fun entry(): TranscriptEntry {
            val entry = if (format == TranscriptCodec.FORMAT_VERSION) {
                try {
                    TranscriptCodec.decode(body)
                } catch (e: IllegalArgumentException) {
                    logger.warn("Entry {} cannot be read and counts as unknown", record.id, e)
                    null
                }
            } else {
                null
            }
            return (entry ?: UnknownEntry(null, type, raw())).withRecord(record)
        }

        private fun raw(): JsonObject = Json.parseToJsonElement(body).jsonObject
    }
}

private val logger = LoggerFactory.getLogger(SqliteTranscriptStore::class.java)
