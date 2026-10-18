package org.unitedsoftware.alexandrite.agent.context

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.rebuild
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RequestMatch
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A model's answer tokens count against its context window, so the prompt and the reserve must fit together. */
class OutputReserveTest {
    private val store = MemoryStore()
    private val model = ScriptedModel(
        models = listOf(ScriptedModel.modelInfo().rebuild { contextWindow(WINDOW).maxOutputTokens(MODEL_OUTPUT) }),
    )
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val summarizer = RequestMatch("a summarizer's request") { request -> request.summarizes }
    private val turns = RequestMatch("a turn's request") { request -> !request.summarizes }

    private val ModelRequest.summarizes: Boolean get() = instructions.any { it.id == SUMMARIZER_SECTION }

    /** Reads a file of `chars` characters. */
    private val read = recordingTool("fs.read") { arguments, _ ->
        ToolResult("x".repeat(arguments.getValue("chars").jsonPrimitive.int))
    }

    private fun harness(agent: String = ""): PluginHarness = agentHarness(
        """
        {
          "previewIntervalMillis": 0,
          "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}$agent}}
        }
        """.trimIndent(),
        store,
    ) { channel(admins = setOf("admin")).model(model).tool(read) }

    private suspend fun PluginHarness.Running.ask(): TurnOutcome {
        val admission = channel().receive("Read it.", sender = channel().user("admin"))
        return assertIs<Admission.Accepted>(admission).ticket.outcome()
    }

    @Test
    fun `a request leaves at most 32 768 answer tokens unless the agent says, and the trigger keeps room for them`() {
        model.reply(turns) { text("Done.") }

        harness().execute {
            assertIs<TurnOutcome.Completed>(ask())

            assertEquals(32_768, model.requests.single().options.maxOutputTokens)
            val status = get<ContextControl>().status(key)
            assertEquals(WINDOW - 32_768L - WINDOW / 50, status.trigger)
        }
    }

    @Test
    fun `a conversation that leaves too little room for the reserve compacts before it would not fit`() {
        model.reply(turns) { toolCall("fs.read", """{"chars": 60000}""") }
        model.reply(turns) { text("Read.") }
        model.reply(summarizer) { text("The file was read.") }
        model.reply(turns) { text("Done.") }

        harness(""", "maxOutputTokens": 85000, "compaction": {"keepTurns": 0}""").execute {
            assertIs<TurnOutcome.Completed>(ask())
            assertIs<TurnOutcome.Completed>(ask())

            val summarizing = model.requests.single { it.summarizes }
            assertEquals(ReasoningEffort.NONE, summarizing.options.reasoning)
            assertEquals(85_000, model.requests.last().options.maxOutputTokens)
        }
    }

    @Test
    fun `when there is nothing to compact the answer is cut to the room the prompt leaves, and the turn goes on`() {
        model.reply(turns) { toolCall("fs.read", """{"chars": 60000}""") }
        model.reply(turns) { text("Done.") }

        harness(""", "maxOutputTokens": 85000""").execute {
            assertIs<TurnOutcome.Completed>(ask())

            val last = model.requests.last { !it.summarizes }
            val reserve = checkNotNull(last.options.maxOutputTokens)
            assertTrue(reserve in 1_024 until 85_000, "$reserve")
        }
    }

    private companion object {
        const val WINDOW = 100_000
        const val MODEL_OUTPUT = 80_000
    }
}
