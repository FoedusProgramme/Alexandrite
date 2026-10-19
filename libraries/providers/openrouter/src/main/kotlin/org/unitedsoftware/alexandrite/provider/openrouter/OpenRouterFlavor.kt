package org.unitedsoftware.alexandrite.provider.openrouter

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.unitedsoftware.alexandrite.provider.common.ModelFacts
import org.unitedsoftware.alexandrite.provider.common.ModelListing
import org.unitedsoftware.alexandrite.provider.common.chat.ChatCaching
import org.unitedsoftware.alexandrite.provider.common.chat.ChatFinishReasons
import org.unitedsoftware.alexandrite.provider.common.chat.ChatFlavor
import org.unitedsoftware.alexandrite.provider.common.chat.ChatReasoning
import org.unitedsoftware.alexandrite.provider.common.chat.ChatReasoningDelta
import org.unitedsoftware.alexandrite.provider.common.chat.ChatReasoningReader
import org.unitedsoftware.alexandrite.provider.common.long
import org.unitedsoftware.alexandrite.provider.common.obj
import org.unitedsoftware.alexandrite.provider.common.positiveInt
import org.unitedsoftware.alexandrite.provider.common.string
import org.unitedsoftware.alexandrite.provider.common.stringContent
import org.unitedsoftware.alexandrite.provider.common.strings
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelPrice
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.Warning
import org.unitedsoftware.alexandrite.sdk.transcript.Dialect
import org.unitedsoftware.alexandrite.sdk.transcript.MediaKind
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningPart
import org.unitedsoftware.alexandrite.sdk.transcript.ReasoningSeal
import org.unitedsoftware.alexandrite.sdk.transcript.SealKind
import java.math.BigDecimal

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
            reasoningEfforts = parameters?.let { if ("reasoning" in it) EFFORTS else emptySet() },
            price = model.obj("pricing")?.let(::price),
        )
    }

    /** The price that [pricing] lists in dollars per token, null when it lists no input or output price. */
    private fun price(pricing: JsonObject): ModelPrice? {
        val input = pricing.perMillion("prompt") ?: return null
        val output = pricing.perMillion("completion") ?: return null
        return ModelPrice(input, output, pricing.perMillion("input_cache_read"))
    }

    private fun JsonObject.perMillion(name: String): BigDecimal? =
        string(name)?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }?.movePointRight(6)?.stripTrailingZeros()
}

/** The efforts that OpenRouter's `reasoning.effort` takes. */
private val EFFORTS = setOf(
    ReasoningEffort.NONE,
    ReasoningEffort.MINIMAL,
    ReasoningEffort.LOW,
    ReasoningEffort.MEDIUM,
    ReasoningEffort.HIGH,
    ReasoningEffort.XHIGH,
)

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

    override fun reader(origin: ModelRef): ChatReasoningReader = DetailsReader(origin)

    private fun parseArray(text: String): List<JsonElement> = try {
        (Json.parseToJsonElement(text) as? JsonArray).orEmpty()
    } catch (e: SerializationException) {
        emptyList()
    }
}

/** Reads the `reasoning_details` of one reasoning part, merging the fragments of each block. */
private class DetailsReader(private val origin: ModelRef) : ChatReasoningReader() {
    private val details = mutableListOf<JsonObject>()

    override fun read(delta: JsonObject): ChatReasoningDelta? {
        val added = (delta["reasoning_details"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
        if (added.isEmpty()) return null
        added.forEach(::merge)
        return ChatReasoningDelta(joined(added, "text"), joined(added, "summary"))
    }

    override fun seal(text: String): ReasoningSeal? {
        if (details.isEmpty()) return null
        val types = details.mapNotNull { it.string("type") }
        val kind = when {
            "reasoning.encrypted" in types -> SealKind.ENCRYPTED
            details.any { it.string("signature") != null } -> SealKind.SIGNATURE
            else -> SealKind.PLAIN
        }
        return ReasoningSeal(origin, OPENROUTER, kind, JsonArray(details).toString())
    }

    private fun joined(details: List<JsonObject>, field: String): String? =
        details.mapNotNull { it.string(field) }.joinToString("").ifEmpty { null }

    /** Adds [detail] to the block of the same index and type, or as a block of its own. */
    private fun merge(detail: JsonObject) {
        val last = details.lastOrNull()
        if (last?.get("index") == null || last["index"] != detail["index"] || last["type"] != detail["type"]) {
            details += detail
            return
        }
        val merged = last.toMutableMap()
        for ((name, value) in detail) {
            val before = merged[name].stringContent()
            val added = value.stringContent()
            merged[name] = when {
                value is JsonNull -> merged[name] ?: value
                name in CONCATENATED && before != null && added != null -> JsonPrimitive(before + added)
                else -> value
            }
        }
        details[details.lastIndex] = JsonObject(merged)
    }

    private companion object {
        /** The fields of a reasoning detail that its stream splits into fragments. */
        val CONCATENATED = setOf("text", "summary", "data")
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
