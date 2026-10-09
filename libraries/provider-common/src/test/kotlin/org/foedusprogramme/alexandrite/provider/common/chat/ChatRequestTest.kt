package org.foedusprogramme.alexandrite.provider.common.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.model.ModelOptions
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.PromptSection
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.model.RequestIds
import org.foedusprogramme.alexandrite.sdk.model.ToolChoice
import org.foedusprogramme.alexandrite.sdk.model.Trust
import org.foedusprogramme.alexandrite.sdk.model.TurnContextItem
import org.foedusprogramme.alexandrite.sdk.model.Warning
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.ContextPart
import org.foedusprogramme.alexandrite.sdk.transcript.Dialect
import org.foedusprogramme.alexandrite.sdk.transcript.EndpointId
import org.foedusprogramme.alexandrite.sdk.transcript.EntryId
import org.foedusprogramme.alexandrite.sdk.transcript.InlineMedia
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.sdk.transcript.MediaPart
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeEntry
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeKind
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningPart
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningSeal
import org.foedusprogramme.alexandrite.sdk.transcript.SealKind
import org.foedusprogramme.alexandrite.sdk.transcript.SummaryEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.testkit.testToolDefinition
import org.foedusprogramme.alexandrite.testkit.testTurn
import org.foedusprogramme.alexandrite.testkit.testUserEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatRequestTest {
    private val turn = testTurn()
    private val model = ModelRef(EndpointId("test"), "chat-model")
    private val info = ModelInfo.builder("chat-model", ChatFlavor.OPENAI_CHAT)
        .nativeTools(true)
        .parallelToolCalls(true)
        .inputMedia(setOf(MediaKind.IMAGE))
        .reasoningEfforts(setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH))
        .streaming(true)
        .build()
    private val call = ToolCallPart(ToolCallId("call_1"), "notes.add", "")
    private val context = TurnContextItem("recall", "Recalled: the user likes tea.", Trust.TRUSTED)

    /** A flavor whose every request part writes its own fields. */
    private val custom = ChatFlavor(
        Dialect("custom"),
        reasoning = object : ChatReasoning() {
            override fun request(effort: ReasoningEffort, info: ModelInfo, body: JsonObjectBuilder): Warning? {
                body.put("thinking", effort.id)
                return null
            }

            override fun replay(parts: List<ReasoningPart>, request: ModelRequest, message: JsonObjectBuilder) {
                message.put("thoughts", parts.mapNotNull { it.seal?.data }.joinToString())
            }
        },
        tools = object : ChatTools() {
            override val acceptsParallelToolCalls: Boolean = false

            override fun toolChoice(request: ModelRequest, body: JsonObjectBuilder): Warning? =
                Warning("unsupported_option", "No choice here.")
        },
        caching = object : ChatCaching() {
            override fun key(key: String, body: JsonObjectBuilder) {
                body.put("session", key)
            }
        },
    )

    private fun user(text: String) = testUserEntry(turn, text)

    private fun request(
        history: List<TranscriptEntry>,
        round: Int = 0,
        block: ModelRequest.Builder.() -> Unit = {},
    ): ModelRequest = ModelRequest.builder(model, history, 0, RequestIds(turn.conversation, turn.id, round))
        .apply(block)
        .build()

    private fun chat(
        request: ModelRequest,
        flavor: ChatFlavor = ChatFlavor.STANDARD,
        info: ModelInfo = this.info,
        cacheKey: Boolean = false,
    ): ChatRequest = ChatRequest(request, info, flavor, cacheKey)

    private fun body(request: ModelRequest, flavor: ChatFlavor = ChatFlavor.STANDARD): JsonObject =
        chat(request, flavor).body

    private fun JsonObject.messages(): List<JsonObject> = this["messages"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content

    @Test
    fun `instructions become one system message and entries keep their order`() {
        val history = listOf(
            SummaryEntry(null, "Earlier: we planned a trip.", EntryId(3)),
            user("Hello"),
            NoticeEntry(null, "Something failed.", NoticeKind.FAILED),
            AssistantEntry(null, listOf(TextPart("Hi.")), model),
            user("And?"),
            user("Anyone?"),
        )
        val sections = listOf(PromptSection("role", "You help.", true), PromptSection("time", "It is noon.", false))

        val messages = body(request(history) { instructions(sections) }).messages()

        assertEquals(
            listOf(
                "system" to "You help.\n\nIt is noon.",
                "user" to "Earlier: we planned a trip.\n\nHello",
                "assistant" to "Hi.",
                "user" to "And?\n\nAnyone?",
            ),
            messages.map { it.text("role") to it.text("content") },
        )
    }

    @Test
    fun `a tool round goes out as calls, results and a follow-up after them`() {
        val history = listOf(
            user("Note milk."),
            AssistantEntry(null, listOf(TextPart("Saving."), call), model),
            ToolResultEntry(null, call.id, call.name, listOf(TextPart("Saved.")), ToolOutcome.Succeeded),
            user("Also eggs."),
        )

        val messages = body(request(history) { tools(listOf(testToolDefinition("notes.add"))) }).messages()

        val assistant = messages[1]
        assertEquals("Saving.", assistant.text("content"))
        val sent = assistant["tool_calls"]!!.jsonArray.single().jsonObject
        assertEquals("call_1", sent.text("id"))
        assertEquals("notes-add", sent["function"]!!.jsonObject.text("name"))
        assertEquals("{}", sent["function"]!!.jsonObject.text("arguments"))
        val result = messages[2]
        assertEquals(
            listOf("tool", "call_1", "Saved."),
            listOf("role", "tool_call_id", "content").map {
                result.text(it)
            },
        )
        assertEquals("user" to "Also eggs.", messages[3].text("role") to messages[3].text("content"))
    }

    @Test
    fun `turn context goes after everything persisted and never into an earlier message`() {
        val first = listOf(user("Plan the trip."))
        val round0 = body(request(first) { turnContext(listOf(context)) }).messages()
        val afterTools = first + AssistantEntry(null, listOf(call), model) +
            ToolResultEntry(null, call.id, call.name, listOf(TextPart("Saved.")), ToolOutcome.Succeeded)
        val round1 = body(request(afterTools, round = 1) { turnContext(listOf(context)) }).messages()
        val nextTurn = afterTools + AssistantEntry(null, listOf(TextPart("Done.")), model) + user("Thanks.")
        val next = body(request(nextTurn)).messages()

        assertEquals("Plan the trip.\n\nRecalled: the user likes tea.", round0.single().text("content"))
        assertEquals("Plan the trip.", round1.first().text("content"))
        assertEquals(
            listOf("user", "Recalled: the user likes tea."),
            listOf("role", "content").map {
                round1.last().text(it)
            },
        )
        assertEquals(round1.dropLast(1), next.take(round1.size - 1))
        assertFalse(next.any { "Recalled" in it.toString() })
    }

    @Test
    fun `images go inline as data URLs to a model that takes them and as placeholders to others`() {
        val image = MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(byteArrayOf(1, 2, 3)), "dot.png")
        val parts = listOf(ContextPart("framing", "From Ada:"), TextPart("Look."), image)
        val entry = UserEntry(null, parts, user("x").origin)
        val plain = info.toBuilder().inputMedia(emptySet()).build()

        val seen = body(request(listOf(entry))).messages().single()["content"]!!.jsonArray.map { it.jsonObject }
        val blind = chat(request(listOf(entry)), info = plain)
        val unseen = blind.body.messages().single().text("content")

        assertEquals(listOf("text", "text", "image_url"), seen.map { it.text("type") })
        assertEquals("data:image/png;base64,AQID", seen[2]["image_url"]!!.jsonObject.text("url"))
        assertEquals("From Ada:\n\nLook.\n\n[image dot.png not shown: the model cannot take it]", unseen)
        assertEquals(listOf("unsupported_media"), blind.warnings.map { it.kind })
    }

    @Test
    fun `tools, tool choice and options map onto their fields`() {
        val options = ModelOptions.builder().maxOutputTokens(512).temperature(0.2).topP(0.9)
            .stopSequences(listOf("END")).parallelToolCalls(false).build()
        val tools = listOf(testToolDefinition("notes.add"), testToolDefinition("files.read"))

        val body = body(
            request(listOf(user("Hi"))) { tools(tools).toolChoice(ToolChoice.Named("files.read")).options(options) },
        )

        val names = body["tools"]!!.jsonArray.map { it.jsonObject["function"]!!.jsonObject.text("name") }
        assertEquals(listOf("notes-add", "files-read"), names)
        assertEquals("files-read", body["tool_choice"]!!.jsonObject["function"]!!.jsonObject.text("name"))
        assertEquals("false", body.text("parallel_tool_calls"))
        assertEquals(listOf("512", "0.2", "0.9"), listOf("max_tokens", "temperature", "top_p").map { body.text(it) })
        assertEquals(JsonArray(listOf(JsonPrimitive("END"))), body["stop"])
        assertEquals("true", body.text("stream"))
        assertEquals("true", body["stream_options"]!!.jsonObject.text("include_usage"))
    }

    @Test
    fun `automatic tool choice is left to the backend and no tools mean no tool fields`() {
        val auto = body(request(listOf(user("Hi"))) { tools(listOf(testToolDefinition("notes.add"))) })
        val required = body(
            request(listOf(user("Hi"))) {
                tools(listOf(testToolDefinition("notes.add"))).toolChoice(ToolChoice.Required)
            },
        )
        val none = body(request(listOf(user("Hi"))))

        assertNull(auto["tool_choice"])
        assertEquals("required", required.text("tool_choice"))
        assertEquals(listOf("model", "messages", "stream", "stream_options"), none.keys.toList())
    }

    @Test
    fun `the standard API asks for an effort the model takes and warns about one it does not`() {
        fun effort(effort: ReasoningEffort): ChatRequest =
            chat(request(listOf(user("Hi"))) { options(ModelOptions.builder().reasoning(effort).build()) })

        assertEquals("high", effort(ReasoningEffort.HIGH).body.text("reasoning_effort"))
        val refused = effort(ReasoningEffort.MAX)
        assertNull(refused.body["reasoning_effort"])
        assertEquals(listOf("unsupported_option"), refused.warnings.map { it.kind })
    }

    @Test
    fun `the cache key goes out only when configured`() {
        val keyed = request(listOf(user("Hi"))) { cacheKey("chat-42") }

        assertNull(chat(keyed).body["prompt_cache_key"])
        assertEquals("chat-42", chat(keyed, cacheKey = true).body.text("prompt_cache_key"))
    }

    @Test
    fun `the standard API sends no reasoning back`() {
        val seal = ReasoningSeal(model, ChatFlavor.OPENAI_CHAT, SealKind.PLAIN, "Think.")
        val history = listOf(
            user("Hi"),
            AssistantEntry(null, listOf(ReasoningPart("Think.", null, seal), TextPart("Hello.")), model),
            user("Again"),
        )

        assertEquals(listOf("role", "content"), body(request(history)).messages()[1].keys.toList())
    }

    @Test
    fun `a flavor's parts write the reasoning, tool choice, cache key and replay fields`() {
        val seal = ReasoningSeal(model, Dialect("custom"), SealKind.PLAIN, "Think.")
        val history = listOf(
            user("Hi"),
            AssistantEntry(null, listOf(ReasoningPart("Think.", null, seal), TextPart("Hello.")), model),
            user("Again"),
        )
        val options = ModelOptions.builder().reasoning(ReasoningEffort.LOW).parallelToolCalls(true).build()

        val chat = chat(
            request(history) {
                tools(listOf(testToolDefinition("notes.add"))).toolChoice(ToolChoice.Required).options(options)
                    .cacheKey("chat-42")
            },
            custom,
            cacheKey = true,
        )

        assertEquals("low", chat.body.text("thinking"))
        assertNull(chat.body["reasoning_effort"])
        assertNull(chat.body["tool_choice"])
        assertNull(chat.body["parallel_tool_calls"])
        assertEquals("chat-42" to null, chat.body.text("session") to chat.body["prompt_cache_key"])
        assertEquals("Think.", chat.body.messages()[1].text("thoughts"))
        assertEquals(listOf("No choice here."), chat.warnings.map { it.message })
    }

    @Test
    fun `a backend without the parallel field cannot be asked to call tools one at a time`() {
        val options = ModelOptions.builder().parallelToolCalls(false).build()

        val chat = chat(
            request(listOf(user("Hi"))) { tools(listOf(testToolDefinition("notes.add"))).options(options) },
            custom,
        )

        assertNull(chat.body["parallel_tool_calls"])
        assertTrue(chat.warnings.any { "cannot keep it from calling tools in parallel" in it.message })
    }

    @Test
    fun `the same request gives the same bytes`() {
        val history = listOf(user("Hi"), AssistantEntry(null, listOf(TextPart("Hello."), call), model))
        val results = history + ToolResultEntry(null, call.id, call.name, listOf(TextPart("ok")), ToolOutcome.Failed)

        val first = body(request(results) { tools(listOf(testToolDefinition("notes.add"))) }).toString()
        val second = body(request(results) { tools(listOf(testToolDefinition("notes.add"))) }).toString()

        assertEquals(first, second)
        assertTrue("\"content\":\"ok\"" in first)
    }
}
