package org.foedusprogramme.alexandrite.provider.deepseek

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.provider.common.chat.ChatFixture
import org.foedusprogramme.alexandrite.sdk.model.Usage

/** The provider checks' fixture of a DeepSeek endpoint. */
internal class DeepSeekFixture : ChatFixture(DEEPSEEK_FLAVOR) {
    override fun listing(): JsonObject = buildJsonObject {
        put("object", "list")
        putJsonArray("data") {
            for ((id, image) in listOf(model to true, plainModel to false)) {
                add(
                    buildJsonObject {
                        put("id", id)
                        put("object", "model")
                        put("name", "DeepSeek $id")
                        put("context_window", 1048576)
                        put("max_output_tokens", 393216)
                        val modalities = listOfNotNull("text", "image".takeIf { image })
                        put("input_modalities", JsonArray(modalities.map(::JsonPrimitive)))
                        putJsonObject("effort") {
                            put("supported_levels", JsonArray(listOf("low", "high", "max").map(::JsonPrimitive)))
                            put("default_level", "high")
                        }
                    },
                )
            }
        }
    }

    override fun usageObject(usage: Usage): JsonObject = deepSeekUsage(
        usage.inputTokens!!,
        usage.cacheReadTokens!!,
        usage.outputTokens!!,
        usage.reasoningTokens!!,
    )
}

internal fun deepSeekUsage(input: Long, hit: Long, output: Long, reasoning: Long): JsonObject = buildJsonObject {
    put("prompt_tokens", input)
    put("prompt_cache_hit_tokens", hit)
    put("prompt_cache_miss_tokens", input - hit)
    put("completion_tokens", output)
    putJsonObject("completion_tokens_details") { put("reasoning_tokens", reasoning) }
    put("total_tokens", input + output)
}
