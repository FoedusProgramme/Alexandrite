package org.unitedsoftware.alexandrite.provider.deepseek

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.provider.common.ModelFacts
import org.unitedsoftware.alexandrite.provider.common.ModelListing
import org.unitedsoftware.alexandrite.provider.common.chat.ChatCaching
import org.unitedsoftware.alexandrite.provider.common.chat.ChatFinishReasons
import org.unitedsoftware.alexandrite.provider.common.chat.ChatFlavor
import org.unitedsoftware.alexandrite.provider.common.chat.ChatReasoning
import org.unitedsoftware.alexandrite.provider.common.chat.ChatReasoningReader
import org.unitedsoftware.alexandrite.provider.common.chat.ChatText
import org.unitedsoftware.alexandrite.provider.common.chat.ChatTextDelta
import org.unitedsoftware.alexandrite.provider.common.chat.ChatTextReader
import org.unitedsoftware.alexandrite.provider.common.chat.ChatTools
import org.unitedsoftware.alexandrite.provider.common.long
import org.unitedsoftware.alexandrite.provider.common.obj
import org.unitedsoftware.alexandrite.provider.common.positiveInt
import org.unitedsoftware.alexandrite.provider.common.string
import org.unitedsoftware.alexandrite.provider.common.strings
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.ToolChoice
import org.unitedsoftware.alexandrite.sdk.model.Warning
import org.unitedsoftware.alexandrite.sdk.model.WarningKind
import org.unitedsoftware.alexandrite.sdk.transcript.Dialect
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningPart
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningSeal
import org.unitedsoftware.alexandrite.sdk.transcript.SealKind

internal val DEEPSEEK: Dialect = Dialect("deepseek")

/** The levels DeepSeek maps each effort onto. */
private val LEVELS = mapOf(
    ReasoningEffort.MINIMAL to ReasoningEffort.LOW,
    ReasoningEffort.LOW to ReasoningEffort.LOW,
    ReasoningEffort.MEDIUM to ReasoningEffort.HIGH,
    ReasoningEffort.HIGH to ReasoningEffort.HIGH,
    ReasoningEffort.XHIGH to ReasoningEffort.HIGH,
    ReasoningEffort.MAX to ReasoningEffort.MAX,
)

internal val DEEPSEEK_FLAVOR: ChatFlavor = ChatFlavor(
    DEEPSEEK,
    DeepSeekListing,
    DeepSeekReasoning,
    DeepSeekTools,
    DeepSeekCaching,
    DeepSeekFinishReasons,
    DeepSeekText,
)

/** Whether the model thinks on [request]: always, unless its effort is NONE. */
private fun thinks(request: ModelRequest): Boolean = request.options.reasoning != ReasoningEffort.NONE

internal object DeepSeekListing : ModelListing() {
    override val defaults: ModelFacts = ModelFacts(
        id = null,
        nativeTools = true,
        parallelToolCalls = true,
        inputMedia = emptySet(),
        reasoningEfforts = setOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.HIGH, ReasoningEffort.MAX),
    )

    override fun facts(model: JsonObject): ModelFacts = ModelFacts(
        id = model.string("id"),
        displayName = model.string("name"),
        contextWindow = model.positiveInt("context_window"),
        maxOutputTokens = model.positiveInt("max_output_tokens"),
        inputMedia = model.strings("input_modalities")?.mapNotNullTo(mutableSetOf()) { modality ->
            MediaKind.entries.firstOrNull { it.id == modality }
        },
        reasoningEfforts = model.obj("effort")?.strings("supported_levels")
            ?.mapNotNullTo(mutableSetOf(ReasoningEffort.NONE)) { level -> LEVELS.keys.firstOrNull { it.id == level } },
    )
}

internal object DeepSeekReasoning : ChatReasoning() {
    override fun requestNoEffort(info: ModelInfo, body: JsonObjectBuilder) {
        body.putJsonObject("thinking") { put("type", "enabled") }
    }

    override fun request(effort: ReasoningEffort, info: ModelInfo, body: JsonObjectBuilder): Warning? {
        if (effort == ReasoningEffort.NONE) {
            body.putJsonObject("thinking") { put("type", "disabled") }
            return null
        }
        val level = effort.takeIf { it in info.reasoningEfforts }
            ?: LEVELS[effort]?.takeIf { it in info.reasoningEfforts }
            ?: return unsupported(effort, info)
        body.putJsonObject("thinking") { put("type", "enabled") }
        body.put("reasoning_effort", level.id)
        if (level == effort) return null
        return Warning(
            WarningKind.UNSUPPORTED_OPTION,
            "Model '${info.id}' does not accept the reasoning effort '$effort', so the request asks for '$level' " +
                "instead.",
        )
    }

    override fun replay(parts: List<ReasoningPart>, request: ModelRequest, message: JsonObjectBuilder) {
        val text = parts.mapNotNull { part ->
            part.seal?.takeIf { it.dialect == DEEPSEEK && it.origin.endpoint == request.model.endpoint }?.data
        }
        if (text.isNotEmpty()) message.put("reasoning_content", text.joinToString(""))
    }

    override fun reader(origin: ModelRef): ChatReasoningReader = object : ChatReasoningReader() {
        override fun seal(text: String): ReasoningSeal? =
            text.takeIf { it.isNotEmpty() }?.let { ReasoningSeal(origin, DEEPSEEK, SealKind.PLAIN, it) }
    }
}

internal object DeepSeekTools : ChatTools() {
    override val acceptsParallelToolCalls: Boolean = false

    override fun toolChoice(request: ModelRequest, body: JsonObjectBuilder): Warning? {
        val forced = request.toolChoice == ToolChoice.Required || request.toolChoice is ToolChoice.Named
        if (!forced || !thinks(request)) return super.toolChoice(request, body)
        return Warning(
            WarningKind.UNSUPPORTED_OPTION,
            "Model '${request.model.model}' takes no forced tool choice while it thinks, so the model decides.",
        )
    }
}

internal object DeepSeekCaching : ChatCaching() {
    override fun cacheReadTokens(usage: JsonObject): Long? =
        usage.long("prompt_cache_hit_tokens") ?: super.cacheReadTokens(usage)
}

internal object DeepSeekFinishReasons : ChatFinishReasons() {
    override fun failure(raw: String): ModelErrorKind? =
        ModelErrorKind.OVERLOADED.takeIf { raw == "insufficient_system_resource" }
}

internal object DeepSeekText : ChatText() {
    override fun reader(): ChatTextReader = MarkupReader()
}

/** Pieces of the markup of a tool call that the model writes as text, each with the name of its markup. */
private val MARKERS = mapOf(
    "\uFF5CDSML" to "DSML markup",
    "DSML\uFF5C" to "DSML markup",
    "<\uFF5Ctool\u2581" to "tool-call tokens",
)

private val LONGEST_MARKER = MARKERS.keys.maxOf { it.length }

/** Holds back text from where a marker of [MARKERS] may start, and finds the output malformed once one is whole. */
private class MarkupReader : ChatTextReader() {
    private var held = ""

    override fun read(text: String): ChatTextDelta {
        val all = held + text
        MARKERS.entries.firstOrNull { it.key in all }?.let { (_, markup) ->
            return ChatTextDelta("", "The model wrote a tool call as text ($markup).")
        }
        val start = heldFrom(all)
        held = all.substring(start)
        return ChatTextDelta(all.substring(0, start))
    }

    override fun release(): String = held.also { held = "" }

    /** Where the end of [text] that may start a marker begins, its length when it holds none. */
    private fun heldFrom(text: String): Int {
        for (at in maxOf(0, text.length - LONGEST_MARKER + 1) until text.length) {
            val tail = text.length - at
            if (MARKERS.keys.any { tail < it.length && it.regionMatches(0, text, at, tail) }) return at
        }
        return text.length
    }
}
