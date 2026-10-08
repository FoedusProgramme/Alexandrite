package org.foedusprogramme.alexandrite.store.sqlite

import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.chat.TurnLineage
import org.foedusprogramme.alexandrite.sdk.store.ConversationKind
import org.foedusprogramme.alexandrite.sdk.store.ConversationState
import org.foedusprogramme.alexandrite.sdk.store.TurnEndKind
import org.foedusprogramme.alexandrite.testkit.TEST_TIME
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqliteConversationStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `conversation ids are 22 URL-safe characters`() {
        withStore(directory) {
            val ids = List(20) { conversations.newConversation(main).successor.id.value }

            assertTrue(ids.all { Regex("[A-Za-z0-9_-]{22}").matches(it) }, "$ids")
        }
    }

    @Test
    fun `times are those of the store's clock, to the millisecond`() {
        val clock = MutableClock(TEST_TIME.plusNanos(123_456_789))

        withStore(directory, clock) {
            val old = conversations.current(main)
            clock.advance(Duration.ofMinutes(5))
            val sealed = conversations.newConversation(main).sealed!!

            assertEquals(TEST_TIME.plusMillis(123), old.createdAt)
            assertEquals(TEST_TIME.plusMillis(123) + Duration.ofMinutes(5), sealed.sealedAt)
        }
    }

    @Test
    fun `a kind or state this version does not know is read as it is`() {
        withStore(directory) {
            val known = conversations.current(main)
            database.transaction {
                execute(
                    "INSERT INTO conversations (id, kind, agent, chat_id, state, created_at, depth) " +
                        "SELECT 'future', 'archive', agent, chat_id, 'frozen', created_at, 0 FROM conversations " +
                        "WHERE id = ?",
                    known.id.value,
                )
            }

            val future = conversations.conversation(ConversationId("future"))!!

            assertEquals(ConversationKind.of("archive"), future.kind)
            assertEquals(ConversationState.of("frozen"), future.state)
            val turn = turn(future)
            val error = assertFailsWith<IllegalStateException> { transcripts.append(turn.id, listOf(message("x"))) }
            assertEquals("Conversation future is frozen, so it takes no new entries.", error.message)
        }
    }

    @Test
    fun `a kind of turn and an end this version does not know are read as they are`() {
        withStore(directory) {
            val turn = turn(conversations.current(main))
            database.transaction {
                execute("UPDATE turns SET kind = 'vote', outcome = 'paused' WHERE id = ?", turn.id.value)
            }

            val record = conversations.turn(turn.id)!!

            assertEquals(TurnKind.of("vote"), record.kind)
            assertEquals(TurnEndKind.of("paused"), record.end)
        }
    }

    @Test
    fun `a delegated conversation that is refused adds no row`() {
        withStore(directory) {
            val parent = turn(conversations.current(main))
            val conversation = parent.conversation
            val lineage = TurnLineage(RunId("run"), TurnId("missing"), conversation, null, parent.id, conversation, 1)

            assertFailsWith<IllegalArgumentException> { conversations.createDelegated(main, lineage) }

            assertEquals(1, rows("SELECT id FROM conversations").size)
        }
    }
}
