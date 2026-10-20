package org.unitedsoftware.alexandrite.agent.turn

import kotlinx.coroutines.CompletableDeferred
import org.unitedsoftware.alexandrite.agent.StoreIndex
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.hook.HookDecision
import org.unitedsoftware.alexandrite.sdk.store.TranscriptStore
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeKind
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.Submission
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.sdk.turn.TurnPoints
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FollowUpsTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val read = recordingTool("fs.read").hold()

    private fun harness(
        store: MemoryStore? = this.store,
        configure: PluginHarness.Builder.() -> Unit = {},
    ): PluginHarness = agentHarness(
        """{"agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}}""",
        store,
    ) { channel(operators = setOf("operator")).model(model).tool(read).apply(configure) }

    private fun PluginHarness.Running.send(text: String, user: String = "operator"): Admission.Accepted =
        channel().receive(text, sender = channel().user(user)) as Admission.Accepted

    private suspend fun stored(): List<TranscriptEntry> = store.transcripts.entries(store.conversations.current(key).id)

    private fun words(entry: TranscriptEntry): String? = ((entry as? UserEntry)?.parts?.last() as? TextPart)?.text

    @Test
    fun `a message of the principal sent while tools run joins the turn after the round's results`() {
        model.reply { toolCall("fs.read") }.reply { text("Both done.") }
        val input = Interceptor(TurnPoints.TURN_INPUT)

        harness { hook(input) }.execute {
            val first = send("Read it.")
            read.awaitCalls(1)
            val second = send("And then summarize.")
            read.release()

            assertIs<TurnOutcome.Completed>(first.ticket.outcome())
            assertEquals(TurnOutcome.Absorbed(first.ticket.turn), second.ticket.outcome())
            val entries = stored()
            assertEquals(listOf("Read it.", null, null, "And then summarize.", null), entries.map(::words))
            assertIs<ToolResultEntry>(entries[2])
            assertEquals(entries.take(4), model.requests[1].history)
            assertEquals(entries, store.transcripts.turnEntries(first.ticket.turn))
            assertEquals(
                listOf("Read it." to false, "And then summarize." to true),
                input.seen.map { it.text to it.followUp },
            )
            assertEquals(listOf(first.ticket.turn), input.seen.map { it.turn.id }.distinct())
            assertEquals("Both done.", channel().reply(first.ticket.turn).completed?.text)
            assertEquals(listOf(first.ticket.turn), channel().replies.map { it.turn.id })
        }
    }

    @Test
    fun `a follow-up that turn input refuses is dropped with a notice while the turn goes on`() {
        model.reply { toolCall("fs.read") }.reply { text("Done.") }
        val input = Interceptor(TurnPoints.TURN_INPUT) {
            if (it.followUp) HookDecision.Abort("No follow-ups.") else HookDecision.Continue
        }

        harness { hook(input) }.execute {
            val first = send("Read it.")
            read.awaitCalls(1)
            val message = channel().message("And more.", sender = channel().user("operator"))
            val second = channel().submit(Submission.Message(message)) as Admission.Accepted
            read.release()

            assertIs<TurnOutcome.Completed>(first.ticket.outcome())
            assertEquals(TurnOutcome.Completed(null), second.ticket.outcome())
            val notice = channel().sent.single().message
            assertEquals(
                listOf("No follow-ups.", MessageKind.NOTICE, message.ref),
                listOf(notice.text, notice.kind, notice.replyTo),
            )
            val entries = stored()
            assertEquals(NoticeEntry(null, "No follow-ups.", NoticeKind.HOOK_ABORTED), entries[3].withoutRecord())
            assertEquals(entries.take(3), model.requests[1].history)
        }
    }

    @Test
    fun `a follow-up whose entry cannot be stored ends as failed with its turn`() {
        model.reply { toolCall("fs.read") }.reply { text("Never.") }
        val transcripts = object : TranscriptStore by store.transcripts {
            override suspend fun append(turn: TurnId, entries: List<TranscriptEntry>): List<TranscriptEntry> {
                check(entries.none { words(it) == "And then summarize." }) { "The disk is full." }
                return store.transcripts.append(turn, entries)
            }
        }

        harness(store = null) { plugin(StoreIndex(store, transcripts = transcripts)) }.execute {
            val first = send("Read it.")
            read.awaitCalls(1)
            val second = send("And then summarize.")
            read.release()

            assertIs<TurnOutcome.Failed>(first.ticket.outcome())
            assertEquals(
                TurnOutcome.Failed("java.lang.IllegalStateException: The disk is full."),
                second.ticket.outcome(),
            )
        }
    }

    @Test
    fun `a message that no tool boundary took runs as a turn of its own`() {
        val streaming = CompletableDeferred<Unit>()
        model.reply {
            text("One")
            pause { streaming.await() }
            text(" answer.")
        }.reply { text("Two.") }

        harness().execute {
            val first = send("First.")
            model.awaitRequests(1)
            val second = send("Second.")
            streaming.complete(Unit)

            assertIs<TurnOutcome.Completed>(first.ticket.outcome())
            assertIs<TurnOutcome.Completed>(second.ticket.outcome())
            val entries = stored()
            assertEquals(listOf("First.", null, "Second.", null), entries.map(::words))
            assertEquals("Two.", channel().reply(second.ticket.turn).completed?.text)
        }
    }

    @Test
    fun `a message the principal sends in another chat of a linked group runs as a turn of its own there`() {
        model.reply { toolCall("fs.read") }.reply { text("To the group.") }.reply { text("To the DM.") }
        val config = """
            {
              "agents": {
                "coder": {
                  "model": "scripted/test-model",
                  "channels": {"test:main": {}},
                  "linkedChats": [["test:main:group", "test:main:dm"]]
                }
              }
            }
        """.trimIndent()

        agentHarness(config, store) { channel(operators = setOf("operator")).model(model).tool(read) }.execute {
            val operator = channel().user("operator")
            val group = channel().receive("Read it.", channel().chat("group"), operator) as Admission.Accepted
            read.awaitCalls(1)
            val dm = channel().receive("Privately: hi.", channel().chat("dm"), operator) as Admission.Accepted
            read.release()

            assertIs<TurnOutcome.Completed>(group.ticket.outcome())
            assertIs<TurnOutcome.Completed>(dm.ticket.outcome())
            assertEquals("To the group.", channel().reply(group.ticket.turn).completed?.text)
            val reply = channel().reply(dm.ticket.turn)
            assertEquals(channel().chat("dm") to "To the DM.", reply.turn.chat to reply.completed?.text)
            assertEquals(3, model.requests[1].history.size)
        }
    }

    @Test
    fun `a message of anyone but the principal waits for its own turn`() {
        model.reply { toolCall("fs.read") }.reply { text("To the operator.") }.reply { text("To the guest.") }

        harness().execute {
            val first = send("Read it.")
            read.awaitCalls(1)
            val second = send("Me too.", user = "guest")
            read.release()

            assertIs<TurnOutcome.Completed>(first.ticket.outcome())
            assertIs<TurnOutcome.Completed>(second.ticket.outcome())
            val kinds = model.requests[1].history.map { it::class }
            assertEquals(listOf(UserEntry::class, AssistantEntry::class, ToolResultEntry::class), kinds)
            assertEquals("To the guest.", channel().reply(second.ticket.turn).completed?.text)
        }
    }
}
