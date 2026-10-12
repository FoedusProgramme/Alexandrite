package org.foedusprogramme.alexandrite.agent.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.sdk.tool.ToolDefinition
import java.nio.file.Path

/** What a call of [definition] is titled: every [ToolDefinition.titleArguments] present in [arguments], joined. */
internal fun toolTitle(definition: ToolDefinition, arguments: JsonObject?): String {
    val args = arguments ?: return definition.name
    val parts = definition.titleArguments.mapNotNull { titleText(args[it])?.takeIf(String::isNotBlank) }
    return parts.joinToString(" ").ifBlank { definition.name }
}

/** [title], with a token that is [workspace] or one of its paths shown relative to it, for display only. */
internal fun relativized(title: String, workspace: Path?): String {
    workspace ?: return title
    val base = "$workspace"
    return title.splitToSequence(' ').joinToString(" ") { token ->
        when {
            token == base -> "."
            token.startsWith("$base/") -> token.removePrefix("$base/")
            else -> token
        }
    }
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
