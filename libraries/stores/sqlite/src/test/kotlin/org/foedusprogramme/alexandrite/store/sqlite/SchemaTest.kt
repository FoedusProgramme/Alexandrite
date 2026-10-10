package org.foedusprogramme.alexandrite.store.sqlite

import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.TurnLineage
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SchemaTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `a chat's address is kept only in chats and in entry bodies, and tables refer to chats by id`() {
        withStore(directory) {
            val photo = media.put(byteArrayOf(1), MediaKind.IMAGE, "image/png")
            val conversation = conversations.current(main)
            conversations.heartbeatBase(main)
            val turn = turn(conversation)
            transcripts.append(turn.id, listOf(UserEntry(null, listOf(mediaPart(photo)), message("x").origin)))
            chatStates.write("notes", "mode", AgentId.MAIN, chat, "1")
            val lineage = TurnLineage(RunId("run"), turn.id, conversation.id, null, turn.id, conversation.id, 1)
            conversations.createDelegated(main, lineage)
            conversations.newConversation(main)

            val tables = rows("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'")
                .map { it.single() as String }
            val references = tables.flatMap { table ->
                rows("SELECT \"table\", \"from\", \"to\" FROM pragma_foreign_key_list(?)", table)
                    .filter { it[0] == "chats" }
                    .map { "$table.${it[1]} -> chats.${it[2]}" }
            }
            val holding = tables.flatMap { table ->
                val columns = rows("SELECT name FROM pragma_table_info(?)", table).map { it.single() as String }
                columns.filter { column ->
                    rows("SELECT \"$column\" FROM \"$table\"").any { "$chat" in (it.single() as? String).orEmpty() }
                }.map { "$table.$it" }
            }

            assertEquals(
                setOf(
                    "conversations.chat_id -> chats.id",
                    "current_conversations.chat_id -> chats.id",
                    "turns.chat_id -> chats.id",
                    "turns.origin_chat_id -> chats.id",
                    "entries.message_chat_id -> chats.id",
                    "chat_states.chat_id -> chats.id",
                ),
                references.toSet(),
            )
            assertEquals(listOf("chats.address", "entries.body"), holding.sorted())
        }
    }
}
