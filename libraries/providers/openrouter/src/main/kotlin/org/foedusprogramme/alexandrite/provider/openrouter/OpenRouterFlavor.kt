package org.foedusprogramme.alexandrite.provider.openrouter

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
import org.foedusprogramme.alexandrite.provider.common.long
import org.foedusprogramme.alexandrite.provider.common.obj
import org.foedusprogramme.alexandrite.provider.common.positiveInt
import org.foedusprogramme.alexandrite.provider.common.string
import org.foedusprogramme.alexandrite.provider.common.strings
import org.foedusprogramme.alexandrite.sdk.model.ModelErrorKind
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.model.ModelRequest
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.model.Warning
import org.foedusprogramme.alexandrite.sdk.transcript.Dialect
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningPart
import org.foedusprogramme.alexandrite.sdk.transcript.ReasoningSeal
import org.foedusprogramme.alexandrite.sdk.transcript.SealKind

internal val OPENROUTER: Dialect = Dialect("openrouter")

internal val OPENROUTER_FLAVOR: ChatFlavor = ChatFlavor(
    OPENROUTER,
    listing = OpenRouterListing,
    reasoning = OpenRouterReasoning,
    caching = OpenRouterCaching,
    finishReasons = OpenRouterFinishReasons,
)

private const val SESSION_ID_LIMIT = 256

internal object OpenRouterListing : ModelListing() {
    override fun next(page: JsonObject): String? = page.obj("links")?.string("next")

    override fun facts(model: JsonObject): ModelFacts {
        val parameters = model.strings("supported_parameters")
        val tools = parameters?.let { "tools" in it }
        return ModelFacts(
            id = model.string("id"),
            displayName = model.string("name"),
            contextWindow =
            model.positiveInt("context_length") ?: model.obj("top_provider")?.positiveInt("context_length"),
            maxOutputTokens = model.obj("top_provider")?.positiveInt("max_completion_tokens"),
            nativeTools = tools,
            parallelToolCalls = parameters?.let { tools == true && "parallel_tool_calls" in it },
            inputMedia = model.obj(
                "architecture",
            )?.strings("input_modalities")?.mapNotNullTo(mutableSetOf()) { modality ->
                MediaKind.entries.firstOrNull { it.id == modality }
            },
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
}

internal object OpenRouterReasoning : ChatReasoning() {
    override fun request(effort: ReasoningEffort, info: ModelInfo, body: JsonObjectBuilder): Warning? {
        if (effort !in info.reasoningEfforts) return unsupported(effort, info)
        body.putJsonObject("reasoning") { put("effort", effort.id) }
        return null
    }

    override fun replay(parts: List<ReasoningPart>, request: ModelRequest, message: JsonObjectBuilder) {
        val seals = parts.mapNotNull { part ->
            part.seal?.takeIf {
                it.dialect == OPENROUTER &&
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
        return ReasoningSeal(origin, OPENROUTER, kind, JsonArray(details).toString())
    }

    private fun parseArray(text: String): List<JsonElement> = try {
        (Json.parseToJsonElement(text) as? JsonArray).orEmpty()
    } catch (e: SerializationException) {
        emptyList()
    }
}

internal object OpenRouterCaching : ChatCaching() {
    override fun key(key: String, body: JsonObjectBuilder) {
        super.key(key, body)
        if (key.length <= SESSION_ID_LIMIT) body.put("session_id", key)
    }

    override fun cacheWriteTokens(usage: JsonObject): Long? =
        usage.obj("prompt_tokens_details")?.long("cache_write_tokens")
}

internal object OpenRouterFinishReasons : ChatFinishReasons() {
    override fun failure(raw: String): ModelErrorKind? = ModelErrorKind.SERVER_ERROR.takeIf { raw == "error" }
}
