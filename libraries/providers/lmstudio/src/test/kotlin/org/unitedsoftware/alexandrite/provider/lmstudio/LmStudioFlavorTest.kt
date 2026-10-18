package org.unitedsoftware.alexandrite.provider.lmstudio

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.provider.common.ModelConfig
import org.unitedsoftware.alexandrite.provider.common.chat.chatEndpoint
import org.unitedsoftware.alexandrite.provider.common.chat.chunk
import org.unitedsoftware.alexandrite.provider.common.chat.chunks
import org.unitedsoftware.alexandrite.provider.common.chat.events
import org.unitedsoftware.alexandrite.provider.common.chat.with
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelOptions
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.RequestIds
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.testkit.FakeModelServer
import org.unitedsoftware.alexandrite.testkit.FakeResponse
import org.unitedsoftware.alexandrite.testkit.fakeResponse
import org.unitedsoftware.alexandrite.testkit.testToolDefinition
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUserEntry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LmStudioFlavorTest {
    private val server = FakeModelServer()
    private val turn = testTurn()

    @AfterTest
    fun close() {
        server.close()
    }

    private fun request(block: ModelRequest.Builder.() -> Unit = {}): ModelRequest = ModelRequest.builder(
        ModelRef(EndpointId("test"), "chat-model"),
        listOf(testUserEntry(turn, "Hi")),
        0,
        RequestIds(turn.conversation, turn.id, 0),
    ).apply(block).build()

    /** The events of [request]'s response, [response], and the body the request sent. */
    private fun send(
        request: ModelRequest,
        response: FakeResponse = chunks(chunk { with("content", "Ok.") }, chunk("stop")),
        models: Map<String, ModelConfig> = emptyMap(),
    ): Pair<List<ModelEvent>, JsonObject> = runBlocking {
        server.enqueue(response, "/v1/chat/completions")
        val events = chatEndpoint(server, LMSTUDIO_FLAVOR, models).use { it.events(request) }
        events to server.requests.last().json()
    }

    private fun call(name: String): FakeResponse {
        val call = buildJsonObject {
            put("index", 0)
            put("id", "c1")
            putJsonObject("function") {
                put("name", name)
                put("arguments", "{}")
            }
        }
        return chunks(chunk { with("tool_calls", JsonArray(listOf(call))) }, chunk("tool_calls"))
    }

    private fun called(name: String, tools: List<ToolDefinition>): String {
        val (events, _) = send(request { tools(tools) }, call(name))
        return ((events.last() as ModelEvent.Completed).message.parts.single() as ToolCallPart).name
    }

    @Test
    fun `the server's own listing reports the loaded context, vision and efforts of its language models`() {
        server.enqueue(fakeResponse { body(LmStudioFixture().listing().toString()) }, "/api/v1/models")

        val models = runBlocking { chatEndpoint(server, LMSTUDIO_FLAVOR).use { it.models() } }.associateBy { it.id }

        assertEquals(setOf("chat-model", "plain-model"), models.keys)
        val chat = models.getValue("chat-model")
        assertEquals(32768 to "Studio chat-model", chat.contextWindow to chat.displayName)
        assertEquals(setOf(MediaKind.IMAGE), chat.inputMedia)
        assertEquals(setOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH), chat.reasoningEfforts)
        assertEquals(null to emptySet(), models.getValue("plain-model").let { it.contextWindow to it.inputMedia })
    }

    @Test
    fun `an older server is asked for the OpenAI listing`() {
        server.enqueue(FakeResponse.builder(404).body("{}").build(), "/api/v1/models")
        server.enqueue(fakeResponse { body("""{"data":[{"id":"old-model","object":"model"}]}""") }, "/v1/models")

        val models = runBlocking { chatEndpoint(server, LMSTUDIO_FLAVOR).use { it.models() } }

        assertEquals(listOf("old-model"), models.map { it.id })
    }

    @Test
    fun `a tool name the model wrote in snake case maps back to the one tool it can mean`() {
        val notes = testToolDefinition("notes.add")

        assertEquals("notes.add", called("notes_add", listOf(notes)))
        assertEquals("notes.add", called("notes-add", listOf(notes)))
        assertEquals("Notes_Add", called("Notes_Add", listOf(notes, testToolDefinition("notes_add"))))
    }

    @Test
    fun `the parallel field is never sent and the effort goes out as reasoning effort`() {
        val options = ModelOptions.builder().parallelToolCalls(false).reasoning(ReasoningEffort.MEDIUM).build()
        val efforts = setOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)
        val models = mapOf("chat-model" to ModelConfig(reasoningEfforts = efforts))

        val (events, body) = send(request { tools(listOf(testToolDefinition())).options(options) }, models = models)

        assertNull(body["parallel_tool_calls"])
        assertEquals(JsonPrimitive("medium"), body["reasoning_effort"])
        val warnings = (events.first() as ModelEvent.ResponseStarted).warnings
        assertTrue(warnings.single().message.endsWith("cannot keep it from calling tools in parallel."))
    }
}
