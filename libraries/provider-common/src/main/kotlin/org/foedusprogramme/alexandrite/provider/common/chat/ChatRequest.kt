package org.foedusprogramme.alexandrite.provider.common.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.Warning
import org.foedusprogramme.alexandrite.sdk.tool.ToolNames
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.ContextPart
import org.foedusprogramme.alexandrite.sdk.transcript.InlineMedia
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.sdk.transcript.MediaPart
import org.foedusprogramme.alexandrite.sdk.transcript.Part
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningPart
import org.foedusprogramme.alexandrite.sdk.transcript.SummaryEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import java.util.Base64

/**
 * The Chat Completions body of [request] to [info]'s model, whose turn context goes after every persisted message: into
 * the last user message, or into a user message of its own.
 */
internal class ChatRequest(
    private val request: ModelRequest,
    private val info: ModelInfo,
    private val flavor: ChatFlavor,
    private val sendCacheKey: Boolean,
) {
    /** What the request asks for that the body leaves out. */
    val warnings: MutableList<Warning> = mutableListOf()

    private val messages = mutableListOf<Message>()

    val body: JsonObject = build()

    private fun build(): JsonObject {
        val system = request.instructions.joinToString(SEPARATOR) { it.text }
        if (system.isNotEmpty()) {
            messages += Message.Plain(
                buildJsonObject {
                    put("role", "system")
                    put("content", system)
                },
            )
        }
        for (entry in request.history) {
            when (entry) {
                is UserEntry -> user(entry.parts.flatMap(::content))
                is SummaryEntry -> user(listOf(Content.Text(entry.text)))
                is AssistantEntry -> messages += Message.Plain(assistant(entry))
                is ToolResultEntry -> messages += Message.Plain(tool(entry))
            }
        }
        if (request.turnContext.isNotEmpty()) {
            user(listOf(Content.Text(request.turnContext.joinToString(SEPARATOR) { it.text })))
        }
        return buildJsonObject {
            put("model", request.model.model)
            put("messages", JsonArray(messages.map(Message::json)))
            tools()
            options()
            put("stream", true)
            putJsonObject("stream_options") { put("include_usage", true) }
            request.cacheKey?.takeIf { sendCacheKey }?.let { flavor.caching.key(it, this) }
        }
    }

    private fun JsonObjectBuilder.tools() {
        if (request.tools.isEmpty()) return
        putJsonArray("tools") {
            for (tool in request.tools) {
                addJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", ToolNames.wire(tool.name))
                        put("description", tool.description)
                        put("parameters", tool.parameters)
                    }
                }
            }
        }
        flavor.tools.toolChoice(request, this)?.let(warnings::add)
        val parallel = request.options.parallelToolCalls ?: return
        when {
            parallel && !info.parallelToolCalls ->
                warnings += Warning("unsupported_option", "Model '${info.id}' calls no tools in parallel.")

            flavor.tools.acceptsParallelToolCalls -> put("parallel_tool_calls", parallel)

            !parallel -> warnings += Warning(
                "unsupported_option",
                "The backend of model '${info.id}' cannot keep it from calling tools in parallel.",
            )
        }
    }

    private fun JsonObjectBuilder.options() {
        val options = request.options
        options.maxOutputTokens?.let { put("max_tokens", it) }
        options.temperature?.let { put("temperature", it) }
        options.topP?.let { put("top_p", it) }
        if (options.stopSequences.isNotEmpty()) {
            putJsonArray("stop") { options.stopSequences.forEach { add(JsonPrimitive(it)) } }
        }
        options.reasoning?.let { effort -> flavor.reasoning.request(effort, info, this)?.let(warnings::add) }
    }

    private fun user(contents: List<Content>) {
        if (contents.isEmpty()) return
        val last = messages.lastOrNull()
        if (last is Message.User) last.contents += contents else messages += Message.User(contents.toMutableList())
    }

    private fun content(part: Part): List<Content> = when (part) {
        is TextPart -> listOf(Content.Text(part.text))
        is ContextPart -> listOf(Content.Text(part.text))
        is MediaPart -> listOf(media(part))
        else -> emptyList()
    }

    private fun media(part: MediaPart): Content {
        val source = part.source
        if (part.kind == MediaKind.IMAGE && MediaKind.IMAGE in info.inputMedia && source is InlineMedia) {
            val encoded = Base64.getEncoder().encodeToString(source.bytes())
            return Content.Image("data:${part.mediaType};base64,$encoded")
        }
        warnings += Warning("unsupported_media", "Model '${info.id}' cannot take ${part.kind} ${part.mediaType}.")
        return Content.Text(placeholder(part, "the model cannot take it"))
    }

    private fun assistant(entry: AssistantEntry): JsonObject = buildJsonObject {
        put("role", "assistant")
        put("content", entry.parts.filterIsInstance<TextPart>().joinToString(SEPARATOR) { it.text })
        flavor.reasoning.replay(entry.parts.filterIsInstance<ReasoningPart>(), request, this)
        val calls = entry.parts.filterIsInstance<ToolCallPart>()
        if (calls.isEmpty()) return@buildJsonObject
        putJsonArray("tool_calls") {
            for (call in calls) {
                addJsonObject {
                    put("id", call.id.value)
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", if (ToolNames.PATTERN.matches(call.name)) ToolNames.wire(call.name) else call.name)
                        put("arguments", call.arguments.ifEmpty { "{}" })
                    }
                }
            }
        }
    }

    private fun tool(entry: ToolResultEntry): JsonObject = buildJsonObject {
        put("role", "tool")
        put("tool_call_id", entry.callId.value)
        val text = entry.content.mapNotNull { part ->
            when (part) {
                is TextPart -> part.text
                is MediaPart -> placeholder(part, "a tool result carries text only")
                else -> null
            }
        }
        put("content", text.joinToString(SEPARATOR))
    }

    private fun placeholder(part: MediaPart, why: String): String =
        "[${part.kind} ${part.name ?: part.mediaType} not shown: $why]"

    private sealed interface Content {
        class Text(val text: String) : Content

        class Image(val url: String) : Content
    }

    private sealed interface Message {
        fun json(): JsonElement

        class Plain(val json: JsonObject) : Message {
            override fun json(): JsonElement = json
        }

        /** A user message, which takes the contents of every user entry that follows it directly. */
        class User(val contents: MutableList<Content>) : Message {
            override fun json(): JsonElement = buildJsonObject {
                put("role", "user")
                if (contents.all { it is Content.Text }) {
                    put("content", contents.joinToString(SEPARATOR) { (it as Content.Text).text })
                } else {
                    put(
                        "content",
                        buildJsonArray {
                            for (content in contents) {
                                addJsonObject {
                                    when (content) {
                                        is Content.Text -> {
                                            put("type", "text")
                                            put("text", content.text)
                                        }

                                        is Content.Image -> {
                                            put("type", "image_url")
                                            putJsonObject("image_url") { put("url", content.url) }
                                        }
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    private companion object {
        const val SEPARATOR = "\n\n"
    }
}
