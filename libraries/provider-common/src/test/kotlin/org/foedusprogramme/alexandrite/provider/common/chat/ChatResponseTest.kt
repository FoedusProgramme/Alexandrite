package org.foedusprogramme.alexandrite.provider.common.chat

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.provider.common.EndpointSettings
import org.foedusprogramme.alexandrite.provider.common.ModelConfig
import org.foedusprogramme.alexandrite.provider.common.TimeoutConfig
import org.foedusprogramme.alexandrite.provider.common.long
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.config.Secret
import org.foedusprogramme.alexandrite.sdk.model.FinishKind
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import org.foedusprogramme.alexandrite.sdk.model.ModelException
import org.foedusprogramme.alexandrite.sdk.model.ModelOptions
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.model.RequestIds
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.Dialect
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningPart
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningSeal
import org.foedusprogramme.alexandrite.sdk.transcript.SealKind
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ChatResponseTest {
    private val server = FakeModelServer()
    private val turn = testTurn()

    /** A flavor whose every response part reads its own way. */
    private val custom = ChatFlavor(
        Dialect("custom"),
        reasoning = object : ChatReasoning() {
            override fun seal(text: String, details: List<JsonObject>, origin: ModelRef): ReasoningSeal =
                ReasoningSeal(origin, Dialect("custom"), SealKind.PLAIN, text.uppercase())
        },
        tools = object : ChatTools() {
            override fun callName(wire: String, offered: List<ToolDefinition>): String = offered.first().name
        },
        caching = object : ChatCaching() {
            override fun cacheReadTokens(usage: JsonObject): Long? = usage.long("hits")

            override fun cacheWriteTokens(usage: JsonObject): Long? = usage.long("writes")
        },
        finishReasons = object : ChatFinishReasons() {
            override fun kind(raw: String): FinishKind = if (raw == "done") FinishKind.END_TURN else super.kind(raw)

            override fun failure(raw: String): ModelErrorKind? = ModelErrorKind.OVERLOADED.takeIf { raw == "busy" }
        },
    )

    @AfterTest
    fun close() {
        server.close()
    }

    private fun request(model: String = "chat-model", block: ModelRequest.Builder.() -> Unit = {}): ModelRequest =
        ModelRequest.builder(
            ModelRef(EndpointId("test"), model),
            listOf(testUserEntry(turn, "Hi")),
            0,
            RequestIds(turn.conversation, turn.id, 2),
        ).apply(block).build()

    private fun respond(
        response: FakeResponse,
        flavor: ChatFlavor = ChatFlavor.STANDARD,
        request: ModelRequest = request(),
    ): List<ModelEvent> = runBlocking {
        server.enqueue(response, "/v1/chat/completions")
        chatEndpoint(server, flavor).use { it.events(request) }
    }

    private fun fail(response: FakeResponse, flavor: ChatFlavor = ChatFlavor.STANDARD): ModelException =
        assertFailsWith<ModelException> { respond(response, flavor) }

    private fun completed(events: List<ModelEvent>): ModelEvent.Completed = events.last() as ModelEvent.Completed

    private fun call(index: Int, id: String? = null, name: String? = null, arguments: String? = null): JsonObject =
        buildJsonObject {
            put("index", index)
            id?.let { put("id", it) }
            putJsonObject("function") {
                name?.let { put("name", it) }
                arguments?.let { put("arguments", it) }
            }
        }

    private fun calls(vararg calls: JsonObject): JsonArray = JsonArray(calls.toList())

    @Test
    fun `the request carries the key, asks for a stream and the response starts with its id and warnings`() {
        val request = request { options(ModelOptions.builder().reasoning(ReasoningEffort.HIGH).build()) }

        val events = respond(chunks(chunk { with("content", "Hi") }, chunk("stop")), request = request)

        val recorded = server.requests.single()
        assertEquals("Bearer $FIXTURE_API_KEY", recorded.header("authorization"))
        assertEquals("text/event-stream", recorded.header("accept"))
        assertEquals("/v1/chat/completions", recorded.path)
        val started = events.first() as ModelEvent.ResponseStarted
        assertEquals("resp-1" to "chat-model", started.responseId to started.model)
        assertEquals(listOf("unsupported_option"), started.warnings.map { it.kind })
    }

    @Test
    fun `a call without an id gets the request's id and arguments before its name wait for it`() {
        val events = respond(
            chunks(
                chunk { with("tool_calls", calls(call(0, arguments = "{\"te"))) },
                chunk {
                    with(
                        "tool_calls",
                        calls(call(0, name = "notes-add", arguments = "xt\":"), call(0, arguments = "1}")),
                    )
                },
                chunk("tool_calls"),
            ),
            request = request { tools(listOf(testToolDefinition("notes.add"))) },
        )

        val started = events.filterIsInstance<ModelEvent.ToolCallStarted>().single()
        assertEquals(ToolCallId("call-test-turn-2-0") to "notes.add", started.id to started.name)
        val fragments = events.filterIsInstance<ModelEvent.ToolArgumentsDelta>().map { it.fragment }
        assertEquals(listOf("{\"text\":", "1}"), fragments)
        val part = completed(events).message.parts.single() as ToolCallPart
        assertEquals("{\"text\":1}", part.arguments)
    }

    @Test
    fun `a name that names no tool is kept as it came`() {
        val reply = chunks(chunk { with("tool_calls", calls(call(0, "c1", "Weird Name", "{}"))) }, chunk("tool_calls"))

        val events = respond(reply)

        assertEquals("Weird Name", (completed(events).message.parts.single() as ToolCallPart).name)
    }

    @Test
    fun `reasoning in reasoning_content or reasoning is shown, but the standard API seals none of it`() {
        for (field in listOf("reasoning_content", "reasoning")) {
            val reply = chunks(chunk { with(field, "Hm.") }, chunk { with("content", "Yes.") }, chunk("stop"))

            val events = respond(reply)

            val reasoning = completed(events).message.parts.first() as ReasoningPart
            assertEquals("Hm.", reasoning.text, field)
            assertNull(reasoning.seal, field)
            assertTrue(events.none { it is ModelEvent.ReasoningSealed }, field)
        }
    }

    @Test
    fun `reasoning details merge by index into what the flavor seals`() {
        fun detail(vararg fields: Pair<String, String>) = buildJsonArray {
            add(
                buildJsonObject {
                    put("type", "reasoning.text")
                    fields.forEach { (name, value) -> put(name, value) }
                    put("index", 0)
                },
            )
        }
        var sealed: List<JsonObject> = emptyList()
        val flavor = ChatFlavor(
            Dialect("custom"),
            reasoning = object : ChatReasoning() {
                override fun seal(text: String, details: List<JsonObject>, origin: ModelRef): ReasoningSeal? {
                    sealed = details
                    return null
                }
            },
        )

        val events = respond(
            chunks(
                chunk { with("reasoning", "Let ").with("reasoning_details", detail("text" to "Let ")) },
                chunk { with("reasoning", "me.").with("reasoning_details", detail("text" to "me.")) },
                chunk { with("reasoning_details", detail("signature" to "sig")) },
                chunk { with("content", "Done.") },
                chunk("stop"),
            ),
            flavor,
        )

        assertEquals("Let me.", (completed(events).message.parts.first() as ReasoningPart).text)
        assertEquals("""[{"type":"reasoning.text","text":"Let me.","index":0,"signature":"sig"}]""", "$sealed")
    }

    @Test
    fun `a flavor's parts seal, name calls, end and count the cache their own way`() {
        val usage = buildJsonObject {
            put("prompt_tokens", 100)
            put("hits", 60)
            put("writes", 30)
            put("completion_tokens", 20)
        }

        val events = respond(
            chunks(
                chunk { with("reasoning_content", "think") },
                chunk { with("tool_calls", calls(call(0, "c1", "anything", "{}"))) },
                chunk("done"),
                usageChunk(usage),
            ),
            custom,
            request { tools(listOf(testToolDefinition("notes.add"))) },
        )

        val completed = completed(events)
        val reasoning = completed.message.parts.first() as ReasoningPart
        assertEquals("THINK", reasoning.seal?.data)
        assertEquals("THINK", events.filterIsInstance<ModelEvent.ReasoningSealed>().single().seal.data)
        assertEquals("notes.add", (completed.message.parts[1] as ToolCallPart).name)
        assertEquals(FinishKind.END_TURN to "done", completed.finish.kind to completed.finish.raw)
        assertEquals(60L to 30L, completed.usage.cacheReadTokens to completed.usage.cacheWriteTokens)
        assertEquals(usage, completed.usage.raw)
    }

    @Test
    fun `an end the flavor counts as a failure fails at once`() {
        val error = fail(chunks(chunk { with("content", "Par") }, chunk("busy"), done = false), custom).error

        assertEquals(ModelErrorKind.OVERLOADED, error.kind)
        assertTrue(error.retryable && error.outputStarted)
        assertTrue("'busy'" in error.message)
    }

    @Test
    fun `finish reasons map to their kinds and keep the raw value`() {
        val finishes = listOf("stop", "length", "tool_calls", "content_filter", "abort", "error").map { raw ->
            completed(respond(chunks(chunk(raw)))).finish
        }

        val kinds = listOf(FinishKind.END_TURN, FinishKind.MAX_OUTPUT_TOKENS, FinishKind.TOOL_USE, FinishKind.REFUSAL)
        assertEquals(kinds + FinishKind.OTHER + FinishKind.OTHER, finishes.map { it.kind })
        assertEquals("abort", finishes[4].raw)
    }

    @Test
    fun `the standard API counts cached tokens and inconsistent counts are left out`() {
        val consistent = openAiUsage(100, 40, 20, 5)
        val inconsistent = buildJsonObject {
            put("prompt_tokens", 10)
            putJsonObject("prompt_tokens_details") { put("cached_tokens", 40) }
            put("completion_tokens", 5)
            putJsonObject("completion_tokens_details") { put("reasoning_tokens", 9) }
        }

        val good = completed(respond(chunks(chunk("stop"), usageChunk(consistent)))).usage
        val bad = completed(respond(chunks(chunk("stop"), usageChunk(inconsistent)))).usage

        assertEquals(
            listOf(100L, 40L, null, 20L, 5L, 100L),
            with(good) {
                listOf(inputTokens, cacheReadTokens, cacheWriteTokens, outputTokens, reasoningTokens, contextTokens)
            },
        )
        assertEquals(listOf(10L, null, null), with(bad) { listOf(inputTokens, cacheReadTokens, reasoningTokens) })
    }

    @Test
    fun `an error in the stream says what broke and whether output started`() {
        val failure = buildJsonObject {
            putJsonObject("error") {
                put("code", 502)
                put("message", "Provider returned error")
                putJsonObject("metadata") { put("error_type", "provider_unavailable") }
            }
            put("choices", buildJsonArray { add(chunk("error")["choices"]!!.let { (it as JsonArray)[0] }) })
        }

        val early = fail(chunks(failure)).error
        val late = fail(chunks(chunk { with("content", "Hi") }, failure)).error

        assertEquals(ModelErrorKind.SERVER_ERROR to "provider_unavailable", early.kind to early.rawType)
        assertFalse(early.outputStarted)
        assertTrue(late.outputStarted)
        assertTrue("Provider returned error" in late.message)
    }

    @Test
    fun `a stream that ends without a finish reason breaks the protocol`() {
        val error = fail(chunks(chunk { with("content", "Hi") })).error

        assertEquals(ModelErrorKind.PROTOCOL, error.kind)
        assertTrue(error.outputStarted)
    }

    @Test
    fun `null fields that some servers send in every chunk are absent fields`() {
        val response = fakeResponse {
            event(
                """{"id":"r","choices":[{"index":0,"delta":{"content":"Hi","reasoning":null,"tool_calls":null}}],""" +
                    """"usage":null,"error":null}""",
            )
            event("""{"choices":[{"index":0,"delta":{"content":null},"finish_reason":"stop"}],"usage":null}""")
        }

        val completed = completed(respond(response))

        assertEquals(listOf(TextPart("Hi")), completed.message.parts)
        assertEquals(null, completed.usage.inputTokens)
    }

    @Test
    fun `a finished stream completes without its done marker`() {
        val events = respond(chunks(chunk { with("content", "Hi") }, chunk("stop"), done = false))

        assertEquals(listOf(TextPart("Hi")), completed(events).message.parts)
    }

    @Test
    fun `keep-alive comments and blank events pass unnoticed`() {
        val response = fakeResponse {
            comment("keep-alive")
            event(chunk { with("content", "Hi") }.toString())
            comment("OPENROUTER PROCESSING")
            event(chunk("stop").toString())
            event("[DONE]")
        }

        assertEquals(listOf(TextPart("Hi")), completed(respond(response)).message.parts)
    }

    @Test
    fun `a response that is not streamed is answered as one chunk`() {
        val message = buildJsonObject {
            put("id", "resp-9")
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("index", 0)
                            putJsonObject("message") {
                                put("role", "assistant")
                                put("content", "Whole.")
                                put("tool_calls", calls(JsonObject(call(0, "c1", "notes-add", "{}") - "index")))
                            }
                            put("finish_reason", "tool_calls")
                        },
                    )
                },
            )
            put("usage", openAiUsage(5, 0, 3, 0))
        }

        val events = respond(fakeResponse { body(message.toString()) })

        val parts = completed(events).message.parts
        assertEquals(TextPart("Whole."), parts[0])
        assertEquals("notes.add", (parts[1] as ToolCallPart).name)
        assertEquals(5L, completed(events).usage.inputTokens)
    }

    @Test
    fun `a malformed chunk or an unknown media type breaks the protocol`() {
        assertEquals(ModelErrorKind.PROTOCOL, fail(fakeResponse { event("{not json") }).error.kind)
        val html = FakeResponse.builder(200).header("content-type", "text/html").body("<html>").build()
        assertEquals(ModelErrorKind.PROTOCOL, fail(html).error.kind)
    }

    @Test
    fun `a stream that stalls times out after its output started`() {
        val stalled = fakeResponse {
            event(chunk { with("content", "Hi") }.toString())
            pause(3.seconds)
            event(chunk("stop").toString())
        }
        server.enqueue(stalled)
        val quick = TimeoutConfig(connectSeconds = 1, firstByteSeconds = 1, idleSeconds = 1)
        val settings = EndpointSettings("${server.baseUrl}/v1", Secret(FIXTURE_API_KEY), timeouts = quick)

        val error = ChatEndpoint(EndpointId("test"), settings, ChatFlavor.STANDARD).use { endpoint ->
            assertFailsWith<ModelException> { runBlocking { endpoint.events(request()) } }
        }.error

        assertEquals(ModelErrorKind.TIMEOUT, error.kind)
        assertTrue(error.retryable && error.outputStarted)
    }

    @Test
    fun `a request for another endpoint or for tools a model cannot take never reaches the backend`() {
        val elsewhere =
            ModelRequest.builder(
                ModelRef(EndpointId("other"), "m"),
                listOf(testUserEntry(turn)),
                0,
                request().ids,
            ).build()
        val tools = request("plain") { tools(listOf(testToolDefinition())) }

        val plain = mapOf("plain" to ModelConfig(nativeTools = false))
        val errors = chatEndpoint(server, ChatFlavor.STANDARD, plain).use { endpoint ->
            listOf(elsewhere, tools).map {
                assertFailsWith<ModelException> { runBlocking { endpoint.events(it) } }.error
            }
        }

        assertEquals(listOf(ModelErrorKind.INVALID_REQUEST, ModelErrorKind.UNSUPPORTED), errors.map { it.kind })
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun `an assistant entry from this provider goes back as it came`() {
        val events = respond(chunks(chunk { with("content", "Hello.") }, chunk("stop")))
        val message: AssistantEntry = completed(events).message
        val history = listOf(testUserEntry(turn, "Hi"), message, testUserEntry(turn, "Bye"))
        val next = ChatRequest(
            ModelRequest.builder(message.producedBy, history, 2, request().ids).build(),
            chatEndpoint(server, ChatFlavor.STANDARD).use { it.describe("chat-model") },
            ChatFlavor.STANDARD,
            false,
        ).body

        val assistant = (next["messages"] as JsonArray)[1]
        assertEquals("""{"role":"assistant","content":"Hello."}""", assistant.toString())
    }
}
