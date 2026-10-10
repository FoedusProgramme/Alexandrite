package org.foedusprogramme.alexandrite.agent.turn

import kotlinx.coroutines.CompletableDeferred
import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.hook.HookDecision
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeEntry
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeKind
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.Submission
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnPoints
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import org.foedusprogramme.alexandrite.testkit.recordingTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FollowUpsTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val read = recordingTool("fs.read").hold()

    private fun harness(configure: PluginHarness.Builder.() -> Unit = {}): PluginHarness = agentHarness(
        """{"agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}}""",
        store,
    ) { channel(admins = setOf("admin")).model(model).tool(read).apply(configure) }

    private fun PluginHarness.Running.send(text: String, user: String = "admin"): Admission.Accepted =
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
            val message = channel().message("And more.", sender = channel().user("admin"))
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
    fun `a message of anyone but the principal waits for its own turn`() {
        model.reply { toolCall("fs.read") }.reply { text("To the admin.") }.reply { text("To the member.") }

        harness().execute {
            val first = send("Read it.")
            read.awaitCalls(1)
            val second = send("Me too.", user = "member")
            read.release()

            assertIs<TurnOutcome.Completed>(first.ticket.outcome())
            assertIs<TurnOutcome.Completed>(second.ticket.outcome())
            val kinds = model.requests[1].history.map { it::class }
            assertEquals(listOf(UserEntry::class, AssistantEntry::class, ToolResultEntry::class), kinds)
            assertEquals("To the member.", channel().reply(second.ticket.turn).completed?.text)
        }
    }
}
