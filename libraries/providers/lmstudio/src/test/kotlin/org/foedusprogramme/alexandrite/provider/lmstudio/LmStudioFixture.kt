package org.foedusprogramme.alexandrite.provider.lmstudio

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.foedusprogramme.alexandrite.provider.common.chat.ChatFixture

/** The provider checks' fixture of an LM Studio server. */
internal class LmStudioFixture : ChatFixture(LMSTUDIO_FLAVOR) {
    override val listingPath: String = "/api/v1/models"

    override fun listing(): JsonObject = buildJsonObject {
        putJsonArray("models") {
            add(studioModel(model, vision = true, loaded = 32768))
            add(studioModel(plainModel, vision = false, loaded = null))
            add(
                buildJsonObject {
                    put("type", "embedding")
                    put("key", "embedder")
                },
            )
        }
    }

    private fun studioModel(key: String, vision: Boolean, loaded: Int?): JsonObject = buildJsonObject {
        put("type", "llm")
        put("key", key)
        put("display_name", "Studio $key")
        put("max_context_length", 131072)
        putJsonArray("loaded_instances") {
            loaded?.let { length ->
                add(
                    buildJsonObject {
                        put("id", key)
                        putJsonObject("config") { put("context_length", length) }
                    },
                )
            }
        }
        putJsonObject("capabilities") {
            put("vision", vision)
            put("trained_for_tool_use", true)
            putJsonObject("reasoning") {
                put("allowed_options", JsonArray(listOf("off", "low", "medium", "high").map(::JsonPrimitive)))
                put("default", "medium")
            }
        }
    }
}
