package org.foedusprogramme.alexandrite.provider.openaicompatible

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    private val info = ModelInfo.builder("chat-model", Dialect("openai-chat"))
        .nativeTools(true)
        .parallelToolCalls(true)
        .inputMedia(setOf(MediaKind.IMAGE))
        .reasoningEfforts(setOf(ReasoningEffort.LOW, ReasoningEffort.HIGH))
        .streaming(true)
        .build()
    private val call = ToolCallPart(ToolCallId("call_1"), "notes.add", "")
    private val context = TurnContextItem("recall", "Recalled: the user likes tea.", Trust.TRUSTED)

    private fun user(text: String) = testUserEntry(turn, text)

    private fun request(
        history: List<TranscriptEntry>,
        round: Int = 0,
        block: ModelRequest.Builder.() -> Unit = {},
    ): ModelRequest = ModelRequest.builder(model, history, 0, RequestIds(turn.conversation, turn.id, round))
        .apply(block)
        .build()

    private fun body(
        request: ModelRequest,
        profile: Profile = Profile.GENERIC,
        info: ModelInfo = this.info,
        cacheKey: Boolean = false,
    ): JsonObject = ChatRequest(request, info, profile, cacheKey).body

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
        val blind = ChatRequest(request(listOf(entry)), plain, Profile.GENERIC, false)
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
        val none = body(request(listOf(user("Hi"))))

        assertNull(auto["tool_choice"])
        assertEquals(listOf("model", "messages", "stream", "stream_options"), none.keys.toList())
    }

    @Test
    fun `each profile asks for reasoning its own way and warns when it cannot`() {
        fun effort(effort: ReasoningEffort, profile: Profile): ChatRequest = ChatRequest(
            request(listOf(user("Hi"))) { options(ModelOptions.builder().reasoning(effort).build()) },
            info.toBuilder().reasoningEfforts(ReasoningEffort.entries.toSet()).build(),
            profile,
            false,
        )

        assertEquals("high", effort(ReasoningEffort.HIGH, Profile.GENERIC).body.text("reasoning_effort"))
        val disabled = effort(ReasoningEffort.NONE, Profile.DEEPSEEK).body
        assertEquals("disabled", disabled["thinking"]!!.jsonObject.text("type"))
        assertNull(disabled["reasoning_effort"])
        val enabled = effort(ReasoningEffort.MEDIUM, Profile.DEEPSEEK).body
        assertEquals("enabled", enabled["thinking"]!!.jsonObject.text("type"))
        assertEquals("medium", enabled.text("reasoning_effort"))
        assertEquals(
            "max",
            effort(ReasoningEffort.MAX, Profile.OPENROUTER).body["reasoning"]!!.jsonObject.text("effort"),
        )
        val refused = ChatRequest(
            request(listOf(user("Hi"))) { options(ModelOptions.builder().reasoning(ReasoningEffort.MAX).build()) },
            info,
            Profile.LMSTUDIO,
            false,
        )
        assertNull(refused.body["reasoning_effort"])
        assertEquals(listOf("unsupported_option"), refused.warnings.map { it.kind })
    }

    @Test
    fun `the cache key goes out only when configured, with a session on OpenRouter`() {
        val keyed = request(listOf(user("Hi"))) { cacheKey("chat-42") }

        assertNull(body(keyed)["prompt_cache_key"])
        assertEquals("chat-42", body(keyed, cacheKey = true).text("prompt_cache_key"))
        val routed = body(keyed, Profile.OPENROUTER, cacheKey = true)
        assertEquals("chat-42" to "chat-42", routed.text("prompt_cache_key") to routed.text("session_id"))
    }

    @Test
    fun `DeepSeek gets back the reasoning of its own endpoint in every request`() {
        val deepseek = Dialect("deepseek")
        val own = ReasoningSeal(ModelRef(EndpointId("test"), "other-model"), deepseek, SealKind.PLAIN, "Think.")
        val foreign = ReasoningSeal(ModelRef(EndpointId("elsewhere"), "chat-model"), deepseek, SealKind.PLAIN, "No.")
        val history = listOf(
            user("Hi"),
            AssistantEntry(null, listOf(ReasoningPart("Think.", null, own), TextPart("Hello.")), model),
            user("Again"),
            AssistantEntry(null, listOf(ReasoningPart("No.", null, foreign), TextPart("Hi.")), model),
            user("Bye"),
        )

        val messages = body(request(history), Profile.DEEPSEEK).messages()

        assertEquals("Think.", messages[1].text("reasoning_content"))
        assertNull(messages[3]["reasoning_content"])
        assertEquals(listOf("role", "content", "reasoning_content"), messages[1].keys.toList())
        assertNull(body(request(history)).messages()[1]["reasoning_content"])
    }

    @Test
    fun `OpenRouter gets back the reasoning details of the same model unchanged`() {
        val details =
            """[{"type":"reasoning.text","text":"Hm.","signature":"s1","format":"anthropic-claude-v1","index":0}]"""
        val same = ReasoningSeal(model, Dialect("openrouter"), SealKind.SIGNATURE, details)
        val other =
            ReasoningSeal(ModelRef(EndpointId("test"), "other"), Dialect("openrouter"), SealKind.SIGNATURE, details)
        val history = listOf(
            user("Hi"),
            AssistantEntry(null, listOf(ReasoningPart("Hm.", null, same), call), model),
            ToolResultEntry(null, call.id, call.name, listOf(TextPart("ok")), ToolOutcome.Succeeded),
            AssistantEntry(null, listOf(ReasoningPart("Hm.", null, other), TextPart("Done.")), model),
            user("Next"),
        )

        val messages = body(request(history), Profile.OPENROUTER).messages()

        assertEquals(details, messages[1]["reasoning_details"].toString())
        assertNull(messages[3]["reasoning_details"])
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
