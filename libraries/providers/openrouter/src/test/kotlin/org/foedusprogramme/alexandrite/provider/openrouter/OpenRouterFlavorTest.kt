package org.foedusprogramme.alexandrite.provider.openrouter

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.provider.common.ModelConfig
import org.foedusprogramme.alexandrite.provider.common.chat.chatEndpoint
import org.foedusprogramme.alexandrite.provider.common.chat.chunk
import org.foedusprogramme.alexandrite.provider.common.chat.chunks
import org.foedusprogramme.alexandrite.provider.common.chat.events
import org.foedusprogramme.alexandrite.provider.common.chat.messages
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
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
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
import org.foedusprogramme.alexandrite.testkit.testTurn
import org.foedusprogramme.alexandrite.testkit.testUserEntry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenRouterFlavorTest {
    private val server = FakeModelServer()
    private val turn = testTurn()
    private val model = ModelRef(EndpointId("test"), "chat-model")
    private val reasoner = mapOf("chat-model" to ModelConfig(reasoningEfforts = setOf("high", "max")))

    @AfterTest
    fun close() {
        server.close()
    }

    private fun request(
        history: List<TranscriptEntry> = listOf(testUserEntry(turn, "Hi")),
        block: ModelRequest.Builder.() -> Unit = {},
    ): ModelRequest =
        ModelRequest.builder(model, history, 0, RequestIds(turn.conversation, turn.id, 0)).apply(block).build()

    /** The events of [request]'s response, [response], and the body the request sent. */
    private fun send(
        request: ModelRequest,
        models: Map<String, ModelConfig> = emptyMap(),
        promptCacheKey: Boolean = false,
        response: FakeResponse = chunks(chunk { with("content", "Ok.") }, chunk("stop")),
    ): Pair<List<ModelEvent>, JsonObject> = runBlocking {
        server.enqueue(response, "/v1/chat/completions")
        val endpoint = chatEndpoint(server, OPENROUTER_FLAVOR, models, promptCacheKey = promptCacheKey)
        val events = endpoint.use { it.events(request) }
        events to server.requests.last().json()
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content

    @Test
    fun `reasoning is asked for as reasoning effort of a model that reasons, and refused to others`() {
        val max = request { options(ModelOptions.builder().reasoning(ReasoningEffort.MAX).build()) }

        val (_, asked) = send(max, reasoner)
        val (refused, plain) = send(max)

        assertEquals("max", (asked["reasoning"] as JsonObject).text("effort"))
        assertNull(asked["reasoning_effort"])
        assertNull(plain["reasoning"])
        assertEquals(
            listOf("unsupported_option"),
            (refused.first() as ModelEvent.ResponseStarted).warnings.map {
                it.kind
            },
        )
    }

    @Test
    fun `the cache key goes out with a session when configured and short enough`() {
        val long = "k".repeat(300)

        val (_, keyed) = send(request { cacheKey("chat-42") }, promptCacheKey = true)
        val (_, longKey) = send(request { cacheKey(long) }, promptCacheKey = true)
        val (_, unkeyed) = send(request { cacheKey("chat-42") })

        assertEquals("chat-42" to "chat-42", keyed.text("prompt_cache_key") to keyed.text("session_id"))
        assertEquals(long to null, longKey.text("prompt_cache_key") to longKey.text("session_id"))
        assertNull(unkeyed["prompt_cache_key"] ?: unkeyed["session_id"])
    }

    @Test
    fun `reasoning details go back unchanged to the model that made them`() {
        val sent = """[{"type":"reasoning.text","text":"Hm.","signature":"s1",""" +
            """"format":"anthropic-claude-v1","index":0}]"""
        val same = ReasoningSeal(model, OPENROUTER, SealKind.SIGNATURE, sent)
        val other = ReasoningSeal(ModelRef(EndpointId("test"), "other"), OPENROUTER, SealKind.SIGNATURE, sent)
        val call = ToolCallPart(ToolCallId("call_1"), "notes.add", "{}")
        val history = listOf(
            testUserEntry(turn, "Hi"),
            AssistantEntry(null, listOf(ReasoningPart("Hm.", null, same), call), model),
            ToolResultEntry(null, call.id, call.name, listOf(TextPart("ok")), ToolOutcome.Succeeded),
            AssistantEntry(null, listOf(ReasoningPart("Hm.", null, other), TextPart("Done.")), model),
            testUserEntry(turn, "Next"),
        )

        send(request(history))

        val messages = server.requests.last().messages()
        assertEquals(sent, messages[1]["reasoning_details"].toString())
        assertNull(messages[3]["reasoning_details"])
    }

    @Test
    fun `reasoning details merge by index into a signed or encrypted seal`() {
        val signed = chunks(
            chunk { with("reasoning", "Let ").with("reasoning_details", details("text" to "Let ")) },
            chunk { with("reasoning", "me.").with("reasoning_details", details("text" to "me.")) },
            chunk { with("reasoning_details", details("signature" to "sig")) },
            chunk { with("content", "Done.") },
            chunk("stop"),
        )
        val encrypted = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "reasoning.encrypted")
                    put("data", "opaque")
                    put("index", 0)
                },
            )
        }

        val (events, _) = send(request(), response = signed)
        val (hidden, _) = send(
            request(),
            response = chunks(
                chunk {
                    with("reasoning_details", encrypted)
                },
                chunk { with("content", "Ok.") },
                chunk("stop"),
            ),
        )

        val seal = ((events.last() as ModelEvent.Completed).message.parts.first() as ReasoningPart).seal!!
        assertEquals(SealKind.SIGNATURE to OPENROUTER, seal.kind to seal.dialect)
        assertEquals(
            """[{"type":"reasoning.text","text":"Let me.","format":"anthropic-claude-v1","index":0,"signature":"sig"}]""",
            seal.data,
        )
        val opaque = ((hidden.last() as ModelEvent.Completed).message.parts.first() as ReasoningPart)
        assertEquals(SealKind.ENCRYPTED to null, opaque.seal?.kind to opaque.text)
    }

    @Test
    fun `cache reads and writes are counted`() {
        val usage = buildJsonObject {
            put("prompt_tokens", 100)
            putJsonObject("prompt_tokens_details") {
                put("cached_tokens", 60)
                put("cache_write_tokens", 30)
            }
            put("completion_tokens", 20)
        }

        val (events, _) = send(
            request(),
            response = chunks(
                chunk {
                    with("content", "Ok.")
                },
                chunk("stop"),
                usageChunk(usage),
            ),
        )

        val counted = (events.last() as ModelEvent.Completed).usage
        assertEquals(60L to 30L, counted.cacheReadTokens to counted.cacheWriteTokens)
    }

    @Test
    fun `a response that ends in error is a server error`() {
        val broken = chunks(chunk { with("content", "Hi") }, chunk("error"))

        val error = assertFailsWith<ModelException> { send(request(), response = broken) }.error

        assertEquals(ModelErrorKind.SERVER_ERROR, error.kind)
        assertTrue(error.retryable && error.outputStarted)
    }

    @Test
    fun `the listing pages and reads each model's parameters`() {
        val data = OpenRouterFixture().listing()["data"]!!.jsonArray
        val first = buildJsonObject {
            putJsonArray("data") { add(data[0]) }
            putJsonObject("links") { put("next", "/v1/models?offset=1") }
        }
        val second = buildJsonObject { putJsonArray("data") { add(data[1]) } }
        server.enqueue(fakeResponse { body(first.toString()) }, "/v1/models")
        server.enqueue(fakeResponse { body(second.toString()) }, "/v1/models")

        val models = runBlocking { chatEndpoint(server, OPENROUTER_FLAVOR).use { it.models() } }.associateBy { it.id }

        val chat = models.getValue("chat-model")
        assertTrue(chat.nativeTools && chat.parallelToolCalls)
        assertEquals(200000 to 64000, chat.contextWindow to chat.maxOutputTokens)
        assertEquals("Router chat-model" to OPENROUTER, chat.displayName to chat.dialect)
        assertEquals(setOf(MediaKind.IMAGE), chat.inputMedia)
        assertEquals(ReasoningEffort.entries.toSet(), chat.reasoningEfforts)
        val plain = models.getValue("plain-model")
        assertFalse(plain.nativeTools)
        assertEquals(emptySet(), plain.reasoningEfforts)
        assertEquals("/v1/models?offset=1", server.requests.last().path)
    }
}
