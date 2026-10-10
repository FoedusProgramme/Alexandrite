package org.foedusprogramme.alexandrite.agent.turn

import org.foedusprogramme.alexandrite.agent.TestClock
import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.blocking
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.agent.logged
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.store.TurnEndKind
import org.foedusprogramme.alexandrite.sdk.store.TurnRecord
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeEntry
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeKind
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptRules
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserOrigin
import org.foedusprogramme.alexandrite.sdk.turn.TurnPoints
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.testUser
import kotlin.test.Test
import kotlin.test.assertEquals

class RecoveryTest {
    private val clock = TestClock()
    private val store = MemoryStore(clock)
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val received = clock.now
    private val notice =
        NoticeEntry(null, "The agent stopped before it finished answering this message.", NoticeKind.INTERRUPTED)
    private val committed = Observer(TurnPoints.TURN_COMMITTED)

    /** Starts the turn [id] in [conversation] a minute after the last one. */
    private suspend fun seed(id: String, conversation: ConversationId, vararg batches: List<TranscriptEntry>): TurnId {
        clock.now = clock.now.plusSeconds(60)
        val turn = TurnInfo.builder(TurnId(id), key.chat, conversation, TurnKind.MESSAGE)
            .actor(testUser())
            .agent(key.agent)
            .key(key)
            .build()
        store.conversations.startTurn(turn)
        batches.forEach { store.transcripts.append(turn.id, it) }
        return turn.id
    }

    private fun recovered(): List<String> = logged {
        agentHarness(store = store) { channel().hook(committed) }.execute {
            assertEquals(emptyList(), channel().events)
        }
    }.filterNot { it.startsWith("WARN No agent is configured") }

    private suspend fun entries(turn: TurnId): List<TranscriptEntry> =
        store.transcripts.turnEntries(turn).map { it.withoutRecord() }

    private suspend fun record(turn: TurnId): TurnRecord? = store.conversations.turn(turn)

    private fun endedLine(turn: String, answered: Int): String =
        "INFO Turn $turn of $key never ended: it ends as shut down, with $answered open call(s) answered as cancelled"

    @Test
    fun `a turn left running gets cancelled results for its open calls and an interrupted notice, once`() {
        val conversation = blocking { store.conversations.current(key).id }
        val turn =
            blocking { seed("t-1", conversation, listOf(message("Read both."), calls("a", "b")), listOf(read("a"))) }

        val first = recovered()

        val entries = blocking { entries(turn) }
        assertEquals(
            listOf(message("Read both."), calls("a", "b"), read("a"), cancelled("b"), notice),
            entries,
        )
        assertEquals(emptyList(), TranscriptRules.check(blocking { store.transcripts.entries(conversation) }))
        val ended = blocking { record(turn) }
        assertEquals(TurnEndKind.SHUT_DOWN, ended?.end)
        assertEquals(listOf(endedLine("t-1", 1)), first)
        assertEquals(emptyList(), committed.seen)

        val second = recovered()

        assertEquals(emptyList(), second)
        assertEquals(entries, blocking { entries(turn) })
        assertEquals(ended, blocking { record(turn) })
    }

    @Test
    fun `unended turns are ended oldest first, also in a sealed conversation, which takes no entries`() {
        val sealed = blocking { store.conversations.current(key).id }
        val older = blocking { seed("t-older", sealed, listOf(message("Read it."), calls("a"))) }
        val current = blocking { store.conversations.newConversation(key).successor.id }
        val newer = blocking { seed("t-newer", current) }

        val lines = recovered()

        assertEquals(
            listOf(
                "WARN Turn t-older of $key never ended, but its conversation $sealed is sealed: its 1 open call(s) " +
                    "stay unanswered",
                endedLine("t-older", 0),
                endedLine("t-newer", 0),
            ),
            lines,
        )
        assertEquals(listOf(message("Read it."), calls("a")), blocking { entries(older) })
        assertEquals(listOf<TranscriptEntry>(notice), blocking { entries(newer) })
        assertEquals(
            listOf(TurnEndKind.SHUT_DOWN, TurnEndKind.SHUT_DOWN),
            listOf(older, newer).map { blocking { record(it) }?.end },
        )
    }

    @Test
    fun `a turn whose recovery stopped after it stored the notice is only ended`() {
        val conversation = blocking { store.conversations.current(key).id }
        val turn = blocking {
            seed("t-1", conversation, listOf(message("Read it."), calls("a")), listOf(cancelled("a"), notice))
        }

        recovered()

        assertEquals(
            listOf(message("Read it."), calls("a"), cancelled("a"), notice),
            blocking { entries(turn) },
        )
        assertEquals(TurnEndKind.SHUT_DOWN, blocking { record(turn) }?.end)
    }

    private fun message(text: String): UserEntry = UserEntry(
        null,
        listOf(TextPart(text)),
        UserOrigin.FromChat(testUser(), ChannelMessageRef(key.chat, "in-1"), received, null, null),
    )

    private fun calls(vararg ids: String): AssistantEntry = AssistantEntry(
        null,
        ids.map { ToolCallPart(ToolCallId(it), "fs.read", "{}") },
        ModelRef(EndpointId("scripted"), "test-model"),
    )

    private fun read(id: String) = ToolResultEntry(
        null,
        ToolCallId(id),
        "fs.read",
        listOf(TextPart("Contents.")),
        ToolOutcome.Succeeded,
    )

    private fun cancelled(id: String) = ToolResultEntry(
        null,
        ToolCallId(id),
        "fs.read",
        listOf(TextPart("Cancelled (outcome unknown)")),
        ToolOutcome.Cancelled,
    )
}
