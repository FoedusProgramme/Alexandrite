package org.foedusprogramme.alexandrite.provider.common.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.provider.common.ModelListing
import org.foedusprogramme.alexandrite.provider.common.long
import org.foedusprogramme.alexandrite.provider.common.obj
import org.foedusprogramme.alexandrite.sdk.model.FinishKind
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.model.ToolChoice
import org.foedusprogramme.alexandrite.sdk.model.Warning
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolNames
import org.foedusprogramme.alexandrite.sdk.transcript.Dialect
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningPart
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningSeal

/**
 * How a family of backends speaks Chat Completions: its wire dialect and, one part per concern, what it does its own
 * way. This library may change between releases more freely than the SDK.
 */
public class ChatFlavor(
    /** The wire dialect of the flavor's models and reasoning seals. */
    public val dialect: Dialect,
    public val listing: ModelListing = ModelListing(),
    public val reasoning: ChatReasoning = ChatReasoning(),
    public val tools: ChatTools = ChatTools(),
    public val caching: ChatCaching = ChatCaching(),
    public val finishReasons: ChatFinishReasons = ChatFinishReasons(),
) {
    public companion object {
        /** The dialect of the standard API. */
        public val OPENAI_CHAT: Dialect = Dialect("openai-chat")

        /** The standard API. */
        public val STANDARD: ChatFlavor = ChatFlavor(OPENAI_CHAT)
    }
}

/** How a backend asks a model to reason and gets the model's reasoning back to it. */
public open class ChatReasoning {
    /** Adds the fields that ask [info]'s model to reason with [effort] to [body], or returns why it adds none. */
    public open fun request(effort: ReasoningEffort, info: ModelInfo, body: JsonObjectBuilder): Warning? {
        if (effort !in info.reasoningEfforts) return unsupported(effort, info)
        body.put("reasoning_effort", effort.id)
        return null
    }

    /** Adds the reasoning of [parts] that [request]'s model must get back to the assistant [message]. */
    public open fun replay(parts: List<ReasoningPart>, request: ModelRequest, message: JsonObjectBuilder) {}

    /**
     * The seal of the reasoning [text] that [origin] streamed with the `reasoning_details` [details], null when the
     * model needs none of it back.
     */
    public open fun seal(text: String, details: List<JsonObject>, origin: ModelRef): ReasoningSeal? = null

    /** The warning that [info]'s model takes no [effort], so the request asks for none. */
    protected fun unsupported(effort: ReasoningEffort, info: ModelInfo): Warning = Warning(
        "unsupported_option",
        "Model '${info.id}' takes no reasoning effort '$effort', so the request asks for none.",
    )
}

/** How a backend takes tools and names the calls of its models. */
public open class ChatTools {
    /** Whether the backend takes the `parallel_tool_calls` field. */
    public open val acceptsParallelToolCalls: Boolean = true

    /** Adds the field that asks for [request]'s tool choice to [body], or returns why it adds none. */
    public open fun toolChoice(request: ModelRequest, body: JsonObjectBuilder): Warning? {
        when (val choice = request.toolChoice) {
            ToolChoice.Auto -> Unit

            ToolChoice.None -> body.put("tool_choice", "none")

            ToolChoice.Required -> body.put("tool_choice", "required")

            is ToolChoice.Named -> body.putJsonObject("tool_choice") {
                put("type", "function")
                putJsonObject("function") { put("name", ToolNames.wire(choice.name)) }
            }

            else -> return Warning("unsupported_option", "The tool choice $choice is unknown, so the model decides.")
        }
        return null
    }

    /** The tool that a call naming [wire] calls, given the [offered] tools. */
    public open fun callName(wire: String, offered: List<ToolDefinition>): String = ToolNames.fromWire(wire) ?: wire
}

/** How a backend keeps requests on one prompt cache and counts what the cache held. */
public open class ChatCaching {
    /** Adds the fields that keep the requests that share [key] on one prompt cache to [body]. */
    public open fun key(key: String, body: JsonObjectBuilder) {
        body.put("prompt_cache_key", key)
    }

    /** The prompt tokens read from the cache as the `usage` object [usage] counts them, null when it does not. */
    public open fun cacheReadTokens(usage: JsonObject): Long? =
        usage.obj("prompt_tokens_details")?.long("cached_tokens")

    /** The prompt tokens written to the cache as the `usage` object [usage] counts them, null when it does not. */
    public open fun cacheWriteTokens(usage: JsonObject): Long? = null
}

/** How a backend says why a response ended. */
public open class ChatFinishReasons {
    /** The kind of an end for the reason [raw]. */
    public open fun kind(raw: String): FinishKind = when (raw) {
        "stop" -> FinishKind.END_TURN
        "length" -> FinishKind.MAX_OUTPUT_TOKENS
        "tool_calls", "function_call" -> FinishKind.TOOL_USE
        "content_filter" -> FinishKind.REFUSAL
        else -> FinishKind.OTHER
    }

    /** The kind of failure that an end for the reason [raw] is, null when it is none. */
    public open fun failure(raw: String): ModelErrorKind? = null
}
