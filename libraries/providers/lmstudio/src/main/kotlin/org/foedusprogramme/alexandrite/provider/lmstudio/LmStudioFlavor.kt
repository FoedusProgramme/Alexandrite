package org.foedusprogramme.alexandrite.provider.lmstudio

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.provider.common.ModelFacts
import org.foedusprogramme.alexandrite.provider.common.ModelListing
import org.foedusprogramme.alexandrite.provider.common.boolean
import org.foedusprogramme.alexandrite.provider.common.chat.ChatFlavor
import org.foedusprogramme.alexandrite.provider.common.chat.ChatTools
import org.foedusprogramme.alexandrite.provider.common.obj
import org.foedusprogramme.alexandrite.provider.common.positiveInt
import org.foedusprogramme.alexandrite.provider.common.string
import org.foedusprogramme.alexandrite.provider.common.strings
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import org.foedusprogramme.alexandrite.sdk.tool.ToolNames
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind

internal val LMSTUDIO_FLAVOR: ChatFlavor =
    ChatFlavor(ChatFlavor.OPENAI_CHAT, listing = LmStudioListing, tools = LmStudioTools)

private val EFFORTS = mapOf(
    "low" to ReasoningEffort.LOW,
    "medium" to ReasoningEffort.MEDIUM,
    "high" to ReasoningEffort.HIGH,
)

internal object LmStudioListing : ModelListing() {
    override fun urls(baseUrl: String): List<String> =
        listOf("${baseUrl.removeSuffix("/v1")}/api/v1/models", "$baseUrl/models")

    override fun models(page: JsonObject): List<JsonObject> =
        (page["models"] as? JsonArray)?.filterIsInstance<JsonObject>()?.filter { it.string("type") == "llm" }
            ?: super.models(page)

    override fun facts(model: JsonObject): ModelFacts {
        val key = model.string("key") ?: return super.facts(model)
        val loaded = (model["loaded_instances"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            .mapNotNull { it.obj("config")?.positiveInt("context_length") }
        val capabilities = model.obj("capabilities")
        return ModelFacts(
            id = key,
            displayName = model.string("display_name"),
            contextWindow = loaded.minOrNull(),
            inputMedia = capabilities?.boolean("vision")?.let { if (it) setOf(MediaKind.IMAGE) else emptySet() },
            reasoningEfforts = capabilities?.obj("reasoning")?.strings("allowed_options")
                ?.mapNotNullTo(mutableSetOf()) { option -> EFFORTS[option] },
        )
    }
}

internal object LmStudioTools : ChatTools() {
    override val acceptsParallelToolCalls: Boolean = false

    /** The tool of [wire], also when the model wrote its name in snake case. */
    override fun callName(wire: String, offered: List<ToolDefinition>): String {
        offered.firstOrNull { ToolNames.wire(it.name) == wire }?.let { return it.name }
        val matches = offered.filter { snake(ToolNames.wire(it.name)) == snake(wire) }
        return matches.singleOrNull()?.name ?: super.callName(wire, offered)
    }

    private fun snake(name: String): String = name.lowercase().replace('-', '_')
}
