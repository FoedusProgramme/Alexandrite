package org.foedusprogramme.alexandrite.provider.deepseek

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.provider.common.ModelFacts
import org.foedusprogramme.alexandrite.provider.common.ModelListing
import org.foedusprogramme.alexandrite.provider.common.chat.ChatCaching
import org.foedusprogramme.alexandrite.provider.common.chat.ChatFinishReasons
import org.foedusprogramme.alexandrite.provider.common.chat.ChatFlavor
import org.foedusprogramme.alexandrite.provider.common.chat.ChatReasoning
import org.foedusprogramme.alexandrite.provider.common.chat.ChatTools
import org.foedusprogramme.alexandrite.provider.common.long
import org.foedusprogramme.alexandrite.provider.common.obj
import org.foedusprogramme.alexandrite.provider.common.positiveInt
import org.foedusprogramme.alexandrite.provider.common.string
import org.foedusprogramme.alexandrite.provider.common.strings
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.model.ToolChoice
import org.foedusprogramme.alexandrite.sdk.model.Warning
import org.foedusprogramme.alexandrite.sdk.transcript.Dialect
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningPart
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningSeal
import org.foedusprogramme.alexandrite.sdk.transcript.SealKind

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

internal val DEEPSEEK_FLAVOR: ChatFlavor =
    ChatFlavor(DEEPSEEK, DeepSeekListing, DeepSeekReasoning, DeepSeekTools, DeepSeekCaching, DeepSeekFinishReasons)

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
            "unsupported_option",
            "Model '${info.id}' takes no reasoning effort '$effort', so the request asks for '$level'.",
        )
    }

    override fun replay(parts: List<ReasoningPart>, request: ModelRequest, message: JsonObjectBuilder) {
        val text = parts.mapNotNull { part ->
            part.seal?.takeIf { it.dialect == DEEPSEEK && it.origin.endpoint == request.model.endpoint }?.data
        }
        if (text.isNotEmpty()) message.put("reasoning_content", text.joinToString(""))
    }

    override fun seal(text: String, details: List<JsonObject>, origin: ModelRef): ReasoningSeal? =
        text.takeIf { it.isNotEmpty() }?.let { ReasoningSeal(origin, DEEPSEEK, SealKind.PLAIN, it) }
}

internal object DeepSeekTools : ChatTools() {
    override val acceptsParallelToolCalls: Boolean = false

    override fun toolChoice(request: ModelRequest, body: JsonObjectBuilder): Warning? {
        val forced = request.toolChoice == ToolChoice.Required || request.toolChoice is ToolChoice.Named
        if (!forced || !thinks(request)) return super.toolChoice(request, body)
        return Warning(
            "unsupported_option",
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
