package org.foedusprogramme.alexandrite.agent.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition

/** What a call of [definition] is titled: the first of its [ToolDefinition.titleArguments] present in [arguments]. */
internal fun toolTitle(definition: ToolDefinition, arguments: JsonObject?): String {
    val args = arguments ?: return definition.name
    for (name in definition.titleArguments) {
        val text = titleText(args[name])
        if (!text.isNullOrBlank()) return text
    }
    return definition.name
}

/** [value] as it titles a call: an array joined with spaces, a primitive's content, null for anything else. */
private fun titleText(value: JsonElement?): String? = when (value) {
    null, JsonNull -> null

    is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.takeUnless { p -> p is JsonNull }?.content }
        .joinToString(" ")
        .takeIf { it.isNotBlank() }

    is JsonPrimitive -> value.content

    else -> null
}
