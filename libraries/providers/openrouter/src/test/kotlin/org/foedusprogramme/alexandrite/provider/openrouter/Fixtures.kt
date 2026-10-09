package org.foedusprogramme.alexandrite.provider.openrouter

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.provider.common.chat.ChatFixture
import org.foedusprogramme.alexandrite.provider.common.chat.chunk
import org.foedusprogramme.alexandrite.provider.common.chat.with

/** The provider checks' fixture of an OpenRouter endpoint. */
internal class OpenRouterFixture : ChatFixture(OPENROUTER_FLAVOR) {
    override fun listing(): JsonObject = buildJsonObject {
        putJsonArray("data") {
            for ((id, rich) in listOf(model to true, plainModel to false)) {
                add(
                    buildJsonObject {
                        put("id", id)
                        put("name", "Router $id")
                        put("context_length", 200000)
                        putJsonObject("architecture") {
                            val modalities = listOfNotNull("text", "image".takeIf { rich })
                            put("input_modalities", JsonArray(modalities.map(::JsonPrimitive)))
                        }
                        putJsonObject("top_provider") { put("max_completion_tokens", 64000) }
                        val extra = listOf("tools", "tool_choice", "parallel_tool_calls", "reasoning").takeIf { rich }
                        val parameters = listOf("temperature") + extra.orEmpty()
                        put("supported_parameters", JsonArray(parameters.map(::JsonPrimitive)))
                    },
                )
            }
        }
    }

    override fun reasoningDelta(piece: String): JsonObject =
        JsonObject(emptyMap()).with("reasoning", piece).with("reasoning_details", details("text" to piece))

    override fun reasoningEnd(): List<JsonObject> = listOf(
        chunk {
            with("reasoning_details", details("signature" to "sig-1"))
        },
    )
}

/** One `reasoning.text` detail at index 0 with [fields]. */
internal fun details(vararg fields: Pair<String, String>): JsonArray = buildJsonArray {
    add(
        buildJsonObject {
            put("type", "reasoning.text")
            fields.forEach { (name, value) -> put(name, value) }
            put("format", "anthropic-claude-v1")
            put("index", 0)
        },
    )
}
