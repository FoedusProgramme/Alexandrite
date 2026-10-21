package org.unitedsoftware.alexandrite.agent.turn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.unitedsoftware.alexandrite.agent.ProvidersIndex
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.logged
import org.unitedsoftware.alexandrite.agent.provider
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.model.ModelEndpoint
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.store.TurnEndKind
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeKind
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BarriersTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val read = recordingTool("fs.read") { _, _ -> ToolResult("Read.") }

    private fun harness(configure: PluginHarness.Builder.() -> Unit = { model(model) }): PluginHarness = agentHarness(
        """{"agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}}""",
        store,
    ) { channel(operators = setOf("operator")).tool(read).apply(configure) }

    private fun PluginHarness.Running.ask(text: String = "Hi"): Admission.Accepted =
        channel().receive(text, sender = channel().user("operator")) as Admission.Accepted

    /** [model] as an endpoint whose round 1 throws [thrown]. */
    private fun throwingInRound1(model: ScriptedModel, thrown: () -> Throwable): ProvidersIndex {
        val endpoint = object : ModelEndpoint by model {
            override fun stream(request: ModelRequest): Flow<ModelEvent> =
                if (request.ids.round == 1) flow { throw thrown() } else model.stream(request)
        }
        return ProvidersIndex("throwing", listOf(provider(endpoint)))
    }

    @Test
    fun `a tool that throws an error fails its call, and the turn and the conversation go on`() {
        val todo = recordingTool("fs.todo") { _, _ -> TODO("Not yet.") }
        model.reply { toolCall("fs.todo") }.reply { text("It failed.") }.reply { text("Again.") }

        lateinit var outcomes: List<TurnOutcome>
        logged {
            harness { model(model).tool(todo) }.execute {
                outcomes = listOf(ask().ticket.outcome(), ask().ticket.outcome())
            }
        }

        outcomes.forEach { assertIs<TurnOutcome.Completed>(it) }
        val result = blockingEntries().filterIsInstance<ToolResultEntry>().single()
        assertEquals(
            ToolOutcome.Failed to "The tool failed: An operation is not implemented: Not yet.",
            result.outcome to (result.content.single() as TextPart).text,
        )
    }

    @Test
    fun `a model call that throws an error or leaks a cancellation fails the turn with a notice, and the next works`() {
        for (thrown in listOf({ NotImplementedError("Not yet.") }, { CancellationException("Leaked.") })) {
            val store = MemoryStore()
            val model = ScriptedModel()
            model.reply { toolCall("fs.read") }.reply { text("Fine.") }
            lateinit var failed: Admission.Accepted
            lateinit var next: TurnOutcome
            val lines = logged {
                agentHarness(
                    """{"agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}}""",
                    store,
                ) {
                    channel(operators = setOf("operator")).tool(read).plugin(throwingInRound1(model, thrown))
                }.execute {
                    failed = ask()
                    assertIs<TurnOutcome.Failed>(failed.ticket.outcome())
                    assertEquals(MessageKind.NOTICE, channel().reply(failed.ticket.turn).awaitEnd().completed?.kind)
                    next = ask("Again.").ticket.outcome()
                }
            }

            assertIs<TurnOutcome.Completed>(next)
            assertEquals(TurnEndKind.FAILED, blocking { store.conversations.turn(failed.ticket.turn)?.end })
            val stored = blocking { store.transcripts.turnEntries(failed.ticket.turn) }
            assertEquals(NoticeKind.FAILED, (stored.last() as NoticeEntry).kind)
            assertEquals(1, lines.count { it.startsWith("ERROR Turn ${failed.ticket.turn} of $key failed") })
        }
    }

    private fun blockingEntries() = blocking { store.transcripts.entries(store.conversations.current(key).id) }
}
