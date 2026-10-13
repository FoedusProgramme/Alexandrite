package org.foedusprogramme.alexandrite.agent.turn

import org.foedusprogramme.alexandrite.agent.TestClock
import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.blocking
import org.foedusprogramme.alexandrite.agent.delegation.DELEGATE_TOOL
import org.foedusprogramme.alexandrite.agent.delegation.DELEGATION_SOURCE
import org.foedusprogramme.alexandrite.agent.delegation.RUN_INTERRUPTED_RESULT
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.agent.job.BackgroundJob
import org.foedusprogramme.alexandrite.agent.job.InterruptedJob
import org.foedusprogramme.alexandrite.agent.job.InterruptedJobs
import org.foedusprogramme.alexandrite.agent.job.JobRecords
import org.foedusprogramme.alexandrite.agent.logged
import org.foedusprogramme.alexandrite.agent.permission.AwaitingCalls
import org.foedusprogramme.alexandrite.agent.permission.Refusals
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.chat.ReplyTarget
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.chat.TurnLineage
import org.foedusprogramme.alexandrite.sdk.store.ConversationState
import org.foedusprogramme.alexandrite.sdk.store.TurnEndKind
import org.foedusprogramme.alexandrite.sdk.store.TurnRecord
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.NotRunReason
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
import org.foedusprogramme.alexandrite.testkit.TestRecords
import org.foedusprogramme.alexandrite.testkit.testUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class RecoveryTest {
    private val clock = TestClock()
    private val store = MemoryStore(clock)
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val chat = key.chat
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
    fun `a call that waited for an approver is answered as interrupted, and the records of waiting calls go`() {
        val conversation = blocking { store.conversations.current(key).id }
        val turn = blocking { seed("t-1", conversation, listOf(message("Write both."), calls("a", "b"))) }
        val awaiting = AwaitingCalls(TestRecords(store).of("alexandrite-agent"))
        blocking {
            awaiting.put(turn, ToolCallId("b"), "k3x9")
            awaiting.put(TurnId("t-gone"), ToolCallId("c"), "m4y2")
        }

        val lines = recovered()

        val interrupted = ToolResultEntry(
            null,
            ToolCallId("b"),
            "fs.read",
            listOf(TextPart(Refusals.INTERRUPTED)),
            ToolOutcome.NotRun(NotRunReason.INTERRUPTED, "k3x9"),
        )
        assertEquals(
            listOf(message("Write both."), calls("a", "b"), cancelled("a"), interrupted, notice),
            blocking { entries(turn) },
        )
        assertEquals(
            listOf(
                endedLine("t-1", 1),
                "INFO Turn t-1 of $key never ended while 1 call(s) waited for an approver: they are answered as " +
                    "interrupted",
            ),
            lines,
        )
        assertEquals(emptyMap(), blocking { awaiting.all() })
    }

    @Test
    fun `a run left running is answered and sealed, and the call that delegated it is answered as interrupted`() {
        val conversation = blocking { store.conversations.current(key).id }
        val root = blocking { seed("t-1", conversation, listOf(message("Review it."), delegated("d"))) }
        val lineage = TurnLineage(RunId("r-1"), root, conversation, ToolCallId("d"), root, conversation, 1)
        val task = UserEntry(null, listOf(TextPart("Review it.")), UserOrigin.SystemWritten(DELEGATION_SOURCE))
        val child = blocking { store.conversations.createDelegated(key, lineage).id }
        val run = blocking {
            clock.now = clock.now.plusSeconds(60)
            val turn = TurnInfo.builder(TurnId("t-run"), key.chat, child, TurnKind.DELEGATED)
                .actor(testUser())
                .agent(key.agent)
                .lineage(lineage)
                .replyTarget(ReplyTarget.CALLER)
                .key(key)
                .build()
            store.conversations.startTurn(turn)
            store.transcripts.append(turn.id, listOf(task, calls("a")))
            turn.id
        }

        val lines = recovered()

        val interrupted = ToolResultEntry(
            null,
            ToolCallId("d"),
            DELEGATE_TOOL,
            listOf(TextPart(RUN_INTERRUPTED_RESULT)),
            ToolOutcome.NotRun(NotRunReason.INTERRUPTED),
        )
        assertEquals(listOf(message("Review it."), delegated("d"), interrupted, notice), blocking { entries(root) })
        assertEquals(listOf(task, calls("a"), cancelled("a"), notice), blocking { entries(run) })
        assertEquals(
            listOf(TurnEndKind.SHUT_DOWN, TurnEndKind.SHUT_DOWN),
            listOf(root, run).map { blocking { record(it) }?.end },
        )
        assertEquals(ConversationState.SEALED, blocking { store.conversations.conversation(child) }?.state)
        assertEquals(
            listOf(
                endedLine("t-1", 0),
                "INFO Turn t-1 of $key never ended while it waited for 1 sub-agent run(s): they are answered as " +
                    "interrupted",
                endedLine("t-run", 1),
            ),
            lines,
        )
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

    @Test
    fun `a job left running is noted as interrupted in its conversation, or only logged once that is sealed`() {
        val sealed = blocking { store.conversations.current(key).id }
        val old = blocking { seed("t-old", sealed, listOf(message("Build it."))) }
        val current = blocking { store.conversations.newConversation(key).successor.id }
        val new = blocking { seed("t-new", current, listOf(message("Test it."))) }
        val jobs = JobRecords(TestRecords(store).of("alexandrite-agent"))
        blocking {
            listOf(old, new).forEach { store.conversations.endTurn(it, TurnEndKind.COMPLETED) }
            jobs.put(job("j-old", old, sealed, "./gradlew build"))
            jobs.put(job("j-new", new, current, "./gradlew test"))
        }

        val lines = recovered()

        assertEquals(
            listOf(
                "INFO Job j-old (command.exec ./gradlew build) of $chat was interrupted by a restart, after its " +
                    "conversation $sealed was sealed",
                "INFO Job j-new (command.exec ./gradlew test) of $chat was interrupted by a restart: it is not run again",
            ),
            lines,
        )
        val notice = "Job j-new (command.exec ./gradlew test) was interrupted by a restart; it was not run again."
        assertEquals(listOf(message("Build it.")), blocking { entries(old) })
        assertEquals(
            listOf(message("Test it."), NoticeEntry(null, notice, NoticeKind.INTERRUPTED)),
            blocking { entries(new) },
        )
        assertEquals(emptyMap(), blocking { jobs.all() })
        val interrupted = InterruptedJobs(TestRecords(store).of("alexandrite-agent"), store.conversations)
        val shown = listOf(InterruptedJob(RunId("j-new"), "command.exec", "./gradlew test"))
        assertEquals(shown to null, blocking { interrupted.pending(current)?.jobs to interrupted.pending(sealed) })
        assertEquals(emptyList(), recovered())
        assertEquals(shown, blocking { interrupted.pending(current)?.jobs })
    }

    private fun job(id: String, turn: TurnId, conversation: ConversationId, command: String): BackgroundJob {
        val info = TurnInfo.builder(turn, key.chat, conversation, TurnKind.MESSAGE)
            .actor(testUser())
            .agent(key.agent)
            .key(key)
            .build()
        val call = ToolCallPart(ToolCallId("call-$id"), "command.exec", "{}")
        return BackgroundJob(RunId(id), info, call, command, clock.now, 1800.seconds)
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

    private fun delegated(id: String): AssistantEntry = AssistantEntry(
        null,
        listOf(ToolCallPart(ToolCallId(id), DELEGATE_TOOL, """{"profile": "reviewer", "task": "Review it."}""")),
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
