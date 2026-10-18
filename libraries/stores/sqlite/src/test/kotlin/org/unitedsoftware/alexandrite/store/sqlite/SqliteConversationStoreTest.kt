package org.unitedsoftware.alexandrite.store.sqlite

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.chat.TurnLineage
import org.unitedsoftware.alexandrite.sdk.store.ConversationKind
import org.unitedsoftware.alexandrite.sdk.store.ConversationState
import org.unitedsoftware.alexandrite.sdk.store.TurnEndKind
import org.unitedsoftware.alexandrite.testkit.TEST_TIME
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SqliteConversationStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `ids hold only characters that are safe in file names`() {
        withStore(directory) {
            val ids = List(20) { conversations.newConversation(main).successor.id.value } + List(1000) { newId() }

            // Ids also name the temporary files that media is written through.
            assertEquals(emptyList(), ids.filterNot { Regex("[A-Za-z0-9_-]+").matches(it) })
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
    fun `kinds, states and ends that another version wrote are read as they are`() {
        withStore(directory) {
            val known = conversations.current(main)
            val voted = turn(known)
            database.transaction {
                execute("UPDATE turns SET kind = 'vote', outcome = 'paused' WHERE id = ?", voted.id.value)
                execute(
                    "INSERT INTO conversations (id, kind, agent, chat_id, state, created_at, depth) " +
                        "SELECT 'future', 'archive', agent, chat_id, 'active', created_at, 0 FROM conversations " +
                        "WHERE id = ?",
                    known.id.value,
                )
            }
            val turn = turn(conversations.conversation(ConversationId("future"))!!)
            database.transaction { execute("UPDATE conversations SET state = 'frozen' WHERE id = 'future'") }

            val future = conversations.conversation(ConversationId("future"))!!
            val record = conversations.turn(voted.id)!!

            assertEquals(ConversationKind.of("archive"), future.kind)
            assertEquals(ConversationState.of("frozen"), future.state)
            assertEquals(TurnKind.of("vote"), record.kind)
            assertEquals(TurnEndKind.of("paused"), record.end)
            val append = assertFailsWith<IllegalStateException> { transcripts.append(turn.id, listOf(message("x"))) }
            val start = assertFailsWith<IllegalStateException> { turn(future) }
            assertEquals("Conversation future is frozen, so it takes no new entries.", append.message)
            assertEquals("Conversation future is frozen, so it takes no new turns.", start.message)
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
