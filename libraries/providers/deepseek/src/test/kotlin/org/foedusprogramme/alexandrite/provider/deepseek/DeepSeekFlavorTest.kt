package org.foedusprogramme.alexandrite.provider.deepseek

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.foedusprogramme.alexandrite.provider.common.ModelConfig
import org.foedusprogramme.alexandrite.provider.common.chat.chatEndpoint
import org.foedusprogramme.alexandrite.provider.common.chat.chunk
import org.foedusprogramme.alexandrite.provider.common.chat.chunks
import org.foedusprogramme.alexandrite.provider.common.chat.events
import org.foedusprogramme.alexandrite.provider.common.chat.usageChunk
import org.foedusprogramme.alexandrite.provider.common.chat.with
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelException
import org.foedusprogramme.alexandrite.sdk.model.ModelOptions
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.model.RequestIds
import org.foedusprogramme.alexandrite.sdk.model.ToolChoice
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.Dialect
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningPart
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningSeal
import org.foedusprogramme.alexandrite.sdk.transcript.SealKind
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.testkit.FakeModelServer
import org.foedusprogramme.alexandrite.testkit.FakeResponse
import org.foedusprogramme.alexandrite.testkit.fakeResponse
import org.foedusprogramme.alexandrite.testkit.testToolDefinition
import org.foedusprogramme.alexandrite.testkit.testTurn
import org.foedusprogramme.alexandrite.testkit.testUserEntry
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
    fun `thinking is off for no effort and on with one of DeepSeek's levels otherwise`() {
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
        assertNull(plain["thinking"] ?: plain["reasoning_effort"])
    }

    @Test
    fun `an effort the model has no level for is left to the backend with a warning`() {
        val models = mapOf("chat-model" to ModelConfig(reasoningEfforts = setOf("none", "high")))

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
