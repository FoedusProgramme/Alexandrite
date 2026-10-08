package org.foedusprogramme.alexandrite.store.sqlite

import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.transcript.EntryId
import org.foedusprogramme.alexandrite.sdk.transcript.EntryRecord
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeEntry
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeKind
import org.foedusprogramme.alexandrite.sdk.transcript.SummaryEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptCodec
import org.foedusprogramme.alexandrite.sdk.transcript.UnknownEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.testkit.TEST_TIME
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqliteTranscriptStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `entries are stored with their type and format, at the millisecond of the store's clock`() {
        val clock = MutableClock(TEST_TIME.plusNanos(123_456_789))
        val model = ModelRef(EndpointId("anthropic"), "claude-opus")
        val entries = listOf(
            message("hello"),
            AssistantEntry(null, listOf(TextPart("Hi.")), model),
            ToolResultEntry(null, ToolCallId("c"), "notes.add", listOf(TextPart("Saved.")), ToolOutcome.Succeeded),
            SummaryEntry(null, "The member said hello.", EntryId(1)),
            NoticeEntry(null, "The model answered with nothing.", NoticeKind.BLANK_REPLY),
            UnknownEntry(null, "poll", json("""{"type":"poll","question":"Lunch?"}""")),
        )

        withStore(directory, clock) {
            val conversation = conversations.current(main)
            val turn = turn(conversation)

            val stored = transcripts.append(turn.id, entries)

            val time = TEST_TIME.plusMillis(123)
            assertEquals(
                entries.mapIndexed { index, entry ->
                    entry.withRecord(EntryRecord(EntryId(index + 1L), conversation.id, turn.id, time))
                },
                stored,
            )
            assertEquals(
                listOf("user", "assistant", "tool_result", "summary", "notice", "poll"),
                rows("SELECT type FROM entries ORDER BY id").map { it.single() },
            )
            assertEquals(
                List(entries.size) { TranscriptCodec.FORMAT_VERSION },
                rows("SELECT format FROM entries").map { it.single() },
            )
        }
    }

    @Test
    fun `a user entry is indexed by the message that carried it`() {
        withStore(directory) {
            append(message("a"), message("b"), NoticeEntry(null, "Done.", NoticeKind.FAILED))

            assertEquals(
                listOf(listOf("m-a"), listOf("m-b"), listOf(null)),
                rows("SELECT message_id FROM entries ORDER BY id"),
            )
        }
    }

    @Test
    fun `a row of another format or that cannot be decoded is read as unknown`() {
        withStore(directory) {
            val turn = turn(conversations.current(main))
            database.transaction {
                for ((format, body) in listOf(2 to """{"type":"user","v2":true}""", 1 to """{"type":"summary"}""")) {
                    execute(
                        "INSERT INTO entries (conversation, turn, type, created_at, format, body) " +
                            "VALUES (?, ?, ?, ?, ?, ?)",
                        turn.conversation.value,
                        turn.id.value,
                        body.substringAfter(":\"").substringBefore('"'),
                        now,
                        format,
                        body,
                    )
                }
            }

            val read = transcripts.entries(turn.conversation)

            assertEquals(
                listOf(
                    UnknownEntry(null, "user", json("""{"type":"user","v2":true}""")),
                    UnknownEntry(null, "summary", json("""{"type":"summary"}""")),
                ),
                read.map { UnknownEntry(null, (it as UnknownEntry).type, it.json) },
            )
            assertEquals(listOf(1L, 2L), read.map { it.record!!.id.value })
        }
    }

    @Test
    fun `deleting a message deletes the files no other media names`() {
        withStore(directory) {
            val own = media.put(byteArrayOf(4, 5, 6), MediaKind.IMAGE, "image/png")
            val first = media.put(png, MediaKind.IMAGE, "image/png")
            val second = media.put(png, MediaKind.IMAGE, "image/png")
            append(UserEntry(null, listOf(mediaPart(own), mediaPart(first)), message("a").origin))
            val ownFile = mediaFile(media.info(own.id)!!.sha256)
            val sharedFile = mediaFile(media.info(first.id)!!.sha256)

            transcripts.deleteMessage(ChannelMessageRef(chat, "m-a"))

            assertTrue(Files.notExists(ownFile))
            assertNull(media.info(first.id))
            assertTrue(Files.exists(sharedFile))
            assertEquals(png.toList(), media.read(second.id)!!.toList())
        }
    }

    private fun mediaFile(sha256: String): Path =
        directory.resolve(SqliteMediaStore.DIRECTORY).resolve(sha256.take(2)).resolve(sha256)
}
