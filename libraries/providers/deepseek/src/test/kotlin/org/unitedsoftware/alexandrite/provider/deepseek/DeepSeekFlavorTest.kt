package org.unitedsoftware.alexandrite.provider.deepseek

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.unitedsoftware.alexandrite.provider.common.ModelConfig
import org.unitedsoftware.alexandrite.provider.common.chat.chatEndpoint
import org.unitedsoftware.alexandrite.provider.common.chat.chunk
import org.unitedsoftware.alexandrite.provider.common.chat.chunks
import org.unitedsoftware.alexandrite.provider.common.chat.events
import org.unitedsoftware.alexandrite.provider.common.chat.usageChunk
import org.unitedsoftware.alexandrite.provider.common.chat.with
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelException
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.model.ToolChoice
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.Dialect
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningPart
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningSeal
import org.unitedsoftware.alexandrite.sdk.transcript.SealKind
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.FakeResponse
import org.unitedsoftware.alexandrite.testkit.fakeResponse
import org.unitedsoftware.alexandrite.testkit.testToolDefinition
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUserEntry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeepSeekFlavorTest {
    private val server = FakeModelServer()
    private val turn = testTurn()
    private val model = ModelRef(EndpointId("test"), "chat-model")
    private val tool = testToolDefinition("clock.now")
    private val call = ToolCallPart(ToolCallId("call_1"), "clock.now", "{}")

    @AfterTest
    fun close() {
        server.close()
    }

    private fun request(
        history: List<TranscriptEntry> = listOf(testUserEntry(turn, "Hi")),
        round: Int = 0,
        block: ModelRequest.Builder.() -> Unit = {},
    ): ModelRequest =
        ModelRequest.builder(model, history, 0, RequestIds(turn.conversation, turn.id, round)).apply(block).build()

    private fun reasoning(effort: ReasoningEffort?): ModelOptions = ModelOptions.builder().reasoning(effort).build()

    /** The events of [request]'s response, [response], and the body the request sent. */
    private fun send(
        request: ModelRequest,
        models: Map<String, ModelConfig> = emptyMap(),
        response: FakeResponse = chunks(chunk { with("content", "Ok.") }, chunk("stop")),
    ): Pair<List<ModelEvent>, JsonObject> = runBlocking {
        server.enqueue(response, "/v1/chat/completions")
        val events = chatEndpoint(server, DEEPSEEK_FLAVOR, models).use { it.events(request) }
        events to server.requests.last().json()
    }

    private fun List<ModelEvent>.warnings(): List<String> =
        filterIsInstance<ModelEvent.ResponseStarted>().single().warnings.map { it.message }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content

    private fun JsonObject.thinking(): String? = (this["thinking"] as? JsonObject)?.text("type")

    @Test
    fun `thinking is off for effort NONE and on otherwise, with one of DeepSeek's levels for an effort`() {
        val levels = mapOf(
            ReasoningEffort.NONE to null,
            ReasoningEffort.MINIMAL to "low",
            ReasoningEffort.LOW to "low",
            ReasoningEffort.MEDIUM to "high",
            ReasoningEffort.HIGH to "high",
            ReasoningEffort.XHIGH to "high",
            ReasoningEffort.MAX to "max",
        )

        for ((effort, level) in levels) {
            val (events, body) = send(request { options(reasoning(effort)) })

            assertEquals(if (level == null) "disabled" else "enabled", body.thinking(), "$effort")
            assertEquals(level, body.text("reasoning_effort"), "$effort")
            val mapped = level != null && level != effort.id
            val warning = "Model 'chat-model' takes no reasoning effort '$effort', so the request asks for '$level'."
            assertEquals(listOfNotNull(warning.takeIf { mapped }), events.warnings(), "$effort")
        }
        val (_, plain) = send(request())
        assertEquals("enabled" to null, plain.thinking() to plain.text("reasoning_effort"))
    }

    @Test
    fun `an effort the model has no level for is left to the backend with a warning`() {
        val models =
            mapOf("chat-model" to ModelConfig(reasoningEfforts = setOf(ReasoningEffort.NONE, ReasoningEffort.HIGH)))

        val (events, body) = send(request { options(reasoning(ReasoningEffort.LOW)) }, models)

        assertNull(body["thinking"] ?: body["reasoning_effort"])
        assertEquals(
            listOf("Model 'chat-model' takes no reasoning effort 'low', so the request asks for none."),
            events.warnings(),
        )
    }

    @Test
    fun `a forced tool choice is left to the model while it thinks`() {
        fun choose(choice: ToolChoice, effort: ReasoningEffort?): Pair<List<String>, JsonObject> {
            val (events, body) = send(request { tools(listOf(tool)).toolChoice(choice).options(reasoning(effort)) })
            return events.warnings() to body
        }

        val forced = listOf<Pair<ToolChoice, ReasoningEffort?>>(
            ToolChoice.Required to null,
            ToolChoice.Named("clock.now") to ReasoningEffort.HIGH,
        )
        for ((choice, effort) in forced) {
            val (warnings, body) = choose(choice, effort)
            assertNull(body["tool_choice"], "$choice")
            assertEquals("enabled", body.thinking(), "$choice")
            assertEquals(
                listOf("Model 'chat-model' takes no forced tool choice while it thinks, so the model decides."),
                warnings,
            )
        }
        val (warnings, unthinking) = choose(ToolChoice.Required, ReasoningEffort.NONE)
        assertEquals("required" to emptyList(), unthinking.text("tool_choice") to warnings)
        assertEquals("none", choose(ToolChoice.None, null).second.text("tool_choice"))
        assertNull(choose(ToolChoice.Auto, ReasoningEffort.HIGH).second["tool_choice"])
    }

    @Test
    fun `the parallel field is never sent`() {
        val options = ModelOptions.builder().parallelToolCalls(false).build()

        val (events, body) = send(request { tools(listOf(tool)).options(options).cacheKey("chat-42") })

        assertNull(body["parallel_tool_calls"] ?: body["prompt_cache_key"])
        assertTrue(events.warnings().single().endsWith("cannot keep it from calling tools in parallel."))
    }

    @Test
    fun `the reasoning of this endpoint goes back in every assistant message, tool calls included`() {
        val own = ReasoningSeal(ModelRef(EndpointId("test"), "other-model"), DEEPSEEK, SealKind.PLAIN, "Think.")
        val foreign = ReasoningSeal(ModelRef(EndpointId("elsewhere"), "chat-model"), DEEPSEEK, SealKind.PLAIN, "No.")
        val other = ReasoningSeal(model, Dialect("openai-chat"), SealKind.PLAIN, "Nor.")
        val history = listOf(
            testUserEntry(turn, "Hi"),
            AssistantEntry(null, listOf(ReasoningPart("No.", null, foreign), TextPart("Hi.")), model),
            testUserEntry(turn, "Again"),
            AssistantEntry(null, listOf(ReasoningPart("Nor.", null, other), TextPart("Hello.")), model),
            testUserEntry(turn, "Time?"),
            AssistantEntry(null, listOf(ReasoningPart("Think.", null, own), call), model),
            ToolResultEntry(null, call.id, call.name, listOf(TextPart("12:00")), ToolOutcome.Succeeded),
        )

        val (_, body) = send(request(history, round = 1) { tools(listOf(tool)) })

        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertNull(messages[1]["reasoning_content"] ?: messages[3]["reasoning_content"])
        val assistant = messages[5]
        assertEquals("Think.", assistant.text("reasoning_content"))
        assertEquals(listOf("role", "content", "reasoning_content", "tool_calls"), assistant.keys.toList())
        assertEquals("call_1", assistant["tool_calls"]!!.jsonArray.single().jsonObject.text("id"))
    }

    @Test
    fun `reasoning is sealed as plain text and cache hits count as cache reads`() {
        val usage = deepSeekUsage(100, 60, 20, 5)
        val response = chunks(
            chunk { with("reasoning_content", "Think") },
            chunk { with("reasoning_content", "ing.") },
            chunk { with("content", "Answer.") },
            chunk("stop"),
            usageChunk(usage),
        )

        val (events, _) = send(request(), response = response)

        val completed = events.last() as ModelEvent.Completed
        val reasoning = completed.message.parts.first() as ReasoningPart
        assertEquals("Thinking.", reasoning.text)
        assertEquals(
            listOf(SealKind.PLAIN, DEEPSEEK, "Thinking.", model),
            reasoning.seal!!.let {
                listOf(it.kind, it.dialect, it.data, it.origin)
            },
        )
        assertEquals("Thinking.", events.filterIsInstance<ModelEvent.ReasoningSealed>().single().seal.data)
        val counts = with(completed.usage) { listOf(inputTokens, cacheReadTokens, outputTokens, reasoningTokens) }
        assertEquals(listOf(100L, 60L, 20L, 5L), counts)
    }

    @Test
    fun `running out of resources is an overload after the output`() {
        val cut = chunks(chunk { with("content", "Par") }, chunk("insufficient_system_resource"))

        val error = assertFailsWith<ModelException> { send(request(), response = cut) }.error

        assertEquals(ModelErrorKind.OVERLOADED, error.kind)
        assertTrue(error.retryable && error.outputStarted)
    }

    @Test
    fun `the listing reports limits, image input and the efforts each model takes`() {
        server.enqueue(fakeResponse { body(DeepSeekFixture().listing().toString()) }, "/v1/models")

        val models = runBlocking { chatEndpoint(server, DEEPSEEK_FLAVOR).use { it.models() } }.associateBy { it.id }

        val chat = models.getValue("chat-model")
        assertEquals(1048576 to 393216, chat.contextWindow to chat.maxOutputTokens)
        assertEquals("DeepSeek chat-model" to DEEPSEEK, chat.displayName to chat.dialect)
        assertEquals(setOf(MediaKind.IMAGE), chat.inputMedia)
        assertEquals(
            setOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX),
            chat.reasoningEfforts,
        )
        assertTrue(chat.nativeTools && chat.parallelToolCalls)
        assertEquals(emptySet(), models.getValue("plain-model").inputMedia)
    }
}
