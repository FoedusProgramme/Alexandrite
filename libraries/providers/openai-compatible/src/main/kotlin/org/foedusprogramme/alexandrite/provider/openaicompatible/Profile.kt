package org.foedusprogramme.alexandrite.provider.openaicompatible

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.model.Warning
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolNames
import org.foedusprogramme.alexandrite.sdk.transcript.Dialect
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningPart
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningSeal
import org.foedusprogramme.alexandrite.sdk.transcript.SealKind

/** A family of backends that speak Chat Completions, with the quirks they share. */
internal enum class Profile(val id: String, val dialect: Dialect) {
    GENERIC("generic", OPENAI_CHAT) {
        override fun facts(model: JsonObject): ModelFacts = super.facts(model).copy(
            contextWindow = model.int("context_window") ?: model.int("context_length") ?: model.int("max_model_len"),
        )
    },

    DEEPSEEK("deepseek", Dialect("deepseek")) {
        override val bakesTurnContext: Boolean = true

        override val sendsParallelToolCalls: Boolean = false

        override val defaults: ModelFacts = DEFAULTS.copy(
            reasoningEfforts = setOf(
                ReasoningEffort.NONE,
                ReasoningEffort.LOW,
                ReasoningEffort.HIGH,
                ReasoningEffort.MAX,
            ),
        )

        override fun facts(model: JsonObject): ModelFacts = super.facts(model).copy(
            displayName = model.string("name"),
            contextWindow = model.int("context_window"),
            maxOutputTokens = model.int("max_output_tokens"),
            inputMedia = model.strings("input_modalities")?.let(::mediaKinds),
            reasoningEfforts = model.obj("effort")?.strings("supported_levels")
                ?.mapNotNullTo(mutableSetOf(ReasoningEffort.NONE)) { level -> known(level) },
        )

        override fun reasoning(effort: ReasoningEffort, info: ModelInfo, body: JsonObjectBuilder): Warning? {
            if (effort == ReasoningEffort.NONE) {
                body.putJsonObject("thinking") { put("type", "disabled") }
                return null
            }
            if (known(effort.id) == null) return unsupportedEffort(effort, info)
            body.putJsonObject("thinking") { put("type", "enabled") }
            body.put("reasoning_effort", effort.id)
            return null
        }

        override fun replay(parts: List<ReasoningPart>, request: ModelRequest, message: JsonObjectBuilder) {
            val text = parts.mapNotNull { part ->
                part.seal?.takeIf { it.dialect == dialect && it.origin.endpoint == request.model.endpoint }?.data
            }
            if (text.isNotEmpty()) message.put("reasoning_content", text.joinToString(""))
        }

        override fun seal(text: String, details: List<JsonObject>, origin: ModelRef): ReasoningSeal? =
            text.takeIf { it.isNotEmpty() }?.let { ReasoningSeal(origin, dialect, SealKind.PLAIN, it) }

        override fun failure(raw: String): ModelErrorKind? =
            ModelErrorKind.OVERLOADED.takeIf { raw == "insufficient_system_resource" }
    },

    LMSTUDIO("lmstudio", OPENAI_CHAT) {
        override val sendsParallelToolCalls: Boolean = false

        override fun listings(base: String): List<String> =
            listOf("${base.removeSuffix("/v1")}/api/v1/models", "$base/models")

        override fun models(listing: JsonObject): List<JsonObject> =
            (listing["models"] as? JsonArray)?.filterIsInstance<JsonObject>()?.filter { it.string("type") == "llm" }
                ?: super.models(listing)

        override fun facts(model: JsonObject): ModelFacts {
            val key = model.string("key") ?: return super.facts(model)
            val loaded = (model["loaded_instances"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
                .mapNotNull { it.obj("config")?.int("context_length") }
            val capabilities = model.obj("capabilities")
            return ModelFacts(
                id = key,
                displayName = model.string("display_name"),
                contextWindow = loaded.minOrNull(),
                inputMedia = capabilities?.boolean("vision")?.let { if (it) setOf(MediaKind.IMAGE) else emptySet() },
                reasoningEfforts = capabilities?.obj("reasoning")?.strings("allowed_options")
                    ?.mapNotNullTo(mutableSetOf()) { option -> LMSTUDIO_EFFORTS[option] },
            )
        }

        override fun toolName(wire: String, offered: List<ToolDefinition>): String {
            offered.firstOrNull { ToolNames.wire(it.name) == wire }?.let { return it.name }
            val matches = offered.filter { snake(ToolNames.wire(it.name)) == snake(wire) }
            return matches.singleOrNull()?.name ?: super.toolName(wire, offered)
        }

        private fun snake(name: String): String = name.lowercase().replace('-', '_')
    },

    VLLM("vllm", OPENAI_CHAT) {
        override val defaults: ModelFacts = DEFAULTS.copy(parallelToolCalls = false)

        override fun facts(model: JsonObject): ModelFacts =
            super.facts(model).copy(contextWindow = model.int("max_model_len"))
    },

    OPENROUTER("openrouter", Dialect("openrouter")) {
        override fun facts(model: JsonObject): ModelFacts {
            val parameters = model.strings("supported_parameters")
            val tools = parameters?.let { "tools" in it }
            return super.facts(model).copy(
                displayName = model.string("name"),
                contextWindow = model.int("context_length") ?: model.obj("top_provider")?.int("context_length"),
                maxOutputTokens = model.obj("top_provider")?.int("max_completion_tokens"),
                nativeTools = tools,
                parallelToolCalls = parameters?.let { tools == true && "parallel_tool_calls" in it },
                inputMedia = model.obj("architecture")?.strings("input_modalities")?.let(::mediaKinds),
                reasoningEfforts = parameters?.let {
                    if ("reasoning" in
                        it
                    ) {
                        ReasoningEffort.entries.toSet()
                    } else {
                        emptySet()
                    }
                },
            )
        }

        override fun next(listing: JsonObject): String? = listing.obj("links")?.string("next")

        override fun reasoning(effort: ReasoningEffort, info: ModelInfo, body: JsonObjectBuilder): Warning? {
            if (effort !in info.reasoningEfforts) return unsupportedEffort(effort, info)
            body.putJsonObject("reasoning") { put("effort", effort.id) }
            return null
        }

        override fun cacheKey(key: String, body: JsonObjectBuilder) {
            super.cacheKey(key, body)
            if (key.length <= SESSION_ID_LIMIT) body.put("session_id", key)
        }

        override fun replay(parts: List<ReasoningPart>, request: ModelRequest, message: JsonObjectBuilder) {
            val seals = parts.mapNotNull { part ->
                part.seal?.takeIf {
                    it.dialect == dialect &&
                        it.origin == request.model
                }
            }
            val details = seals.flatMap { parseArray(it.data) }
            if (details.isNotEmpty()) message.put("reasoning_details", JsonArray(details))
        }

        override fun seal(text: String, details: List<JsonObject>, origin: ModelRef): ReasoningSeal? {
            if (details.isEmpty()) return null
            val types = details.mapNotNull { it.string("type") }
            val kind = when {
                "reasoning.encrypted" in types -> SealKind.ENCRYPTED
                details.any { it.string("signature") != null } -> SealKind.SIGNATURE
                else -> SealKind.PLAIN
            }
            return ReasoningSeal(origin, dialect, kind, JsonArray(details).toString())
        }
    },
    ;

    /** Whether turn context is baked into the user entry unless the config says otherwise. */
    open val bakesTurnContext: Boolean = false

    /** Whether requests may say whether the model calls tools in parallel. */
    open val sendsParallelToolCalls: Boolean = true

    /** What a model can do when neither the backend nor the operator says. */
    open val defaults: ModelFacts = DEFAULTS

    /** The URLs that list the models below [base], tried in order until one answers. */
    open fun listings(base: String): List<String> = listOf("$base/models")

    /** The model objects of one page of a listing. */
    open fun models(listing: JsonObject): List<JsonObject> =
        (listing["data"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

    /** The URL of the listing's next page, null when it has none. */
    open fun next(listing: JsonObject): String? = null

    /** What a listed [model] says about itself, its id null when it names none. */
    open fun facts(model: JsonObject): ModelFacts = ModelFacts(id = model.string("id"))

    /** Adds what asks [info]'s model to reason with [effort] to [body], or the warning why it cannot. */
    open fun reasoning(effort: ReasoningEffort, info: ModelInfo, body: JsonObjectBuilder): Warning? {
        if (effort !in info.reasoningEfforts) return unsupportedEffort(effort, info)
        body.put("reasoning_effort", effort.id)
        return null
    }

    /** Adds what keeps requests that share [key] on one prompt cache to [body]. */
    open fun cacheKey(key: String, body: JsonObjectBuilder) {
        body.put("prompt_cache_key", key)
    }

    /** Adds the reasoning of [parts] that [request]'s model must get back to the assistant [message]. */
    open fun replay(parts: List<ReasoningPart>, request: ModelRequest, message: JsonObjectBuilder) {}

    /** The seal of reasoning made of [text] and [details], null when the model needs none of it back. */
    open fun seal(text: String, details: List<JsonObject>, origin: ModelRef): ReasoningSeal? = null

    /** The tool name of a call that names [wire], given the [offered] tools. */
    open fun toolName(wire: String, offered: List<ToolDefinition>): String = ToolNames.fromWire(wire) ?: wire

    /** What kind of failure a response that ends for the reason [raw] is, null when it is none. */
    open fun failure(raw: String): ModelErrorKind? = null

    companion object {
        fun of(id: String): Profile? = entries.firstOrNull { it.id == id }
    }
}

private val OPENAI_CHAT = Dialect("openai-chat")

private val DEFAULTS = ModelFacts(
    id = null,
    nativeTools = true,
    parallelToolCalls = true,
    inputMedia = emptySet(),
    reasoningEfforts = emptySet(),
)

private const val SESSION_ID_LIMIT = 256

private val LMSTUDIO_EFFORTS = mapOf(
    "low" to ReasoningEffort.LOW,
    "medium" to ReasoningEffort.MEDIUM,
    "high" to ReasoningEffort.HIGH,
)

/** The effort DeepSeek takes for [id], which it maps onto its own levels. */
private fun known(id: String): ReasoningEffort? =
    ReasoningEffort.entries.firstOrNull { it.id == id && it != ReasoningEffort.NONE }

private fun mediaKinds(modalities: List<String>): Set<MediaKind> =
    modalities.mapNotNullTo(mutableSetOf()) { modality -> MediaKind.entries.firstOrNull { it.id == modality } }

private fun unsupportedEffort(effort: ReasoningEffort, info: ModelInfo): Warning = Warning(
    "unsupported_option",
    "Model '${info.id}' takes no reasoning effort '$effort', so the request asks for none.",
)

private fun parseArray(text: String): List<JsonElement> = try {
    (Json.parseToJsonElement(text) as? JsonArray).orEmpty()
} catch (e: SerializationException) {
    emptyList()
}
