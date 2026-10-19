package org.unitedsoftware.alexandrite.agent.tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.agent.prompt.oneLine
import org.unitedsoftware.alexandrite.internal.text.cutWithEllipsis
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import java.nio.file.Path

/** What a call of [definition] is titled: every [ToolDefinition.titleArguments] present in [arguments], joined. */
internal fun toolTitle(definition: ToolDefinition, arguments: JsonObject?): String =
    arguments?.let { titleArguments(definition, it) }.orEmpty().ifBlank { definition.name }

/**
 * The title of a call of [definition] with [arguments] on one line and cut short: its title arguments, or its first text
 * argument when [definition] declares none.
 */
internal fun callTitle(definition: ToolDefinition, arguments: JsonObject): String {
    val main = if (definition.titleArguments.isEmpty()) {
        arguments.values.firstNotNullOfOrNull(::textArgument).orEmpty()
    } else {
        titleArguments(definition, arguments)
    }
    return cutWithEllipsis(oneLine(main).trim(), MAX_CALL_TITLE_CHARS)
}

private fun titleArguments(definition: ToolDefinition, arguments: JsonObject): String =
    definition.titleArguments.mapNotNull { titleText(arguments[it])?.takeIf(String::isNotBlank) }.joinToString(" ")

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

/** A string, or the strings of an array joined by spaces, null for anything else or blank text. */
private fun textArgument(value: JsonElement): String? = when (value) {
    is JsonPrimitive -> value.content.takeIf { value.isString && it.isNotBlank() }

    is JsonArray -> value.mapNotNull { item -> (item as? JsonPrimitive)?.takeIf { it.isString }?.content }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" ")

    else -> null
}

private const val MAX_CALL_TITLE_CHARS = 80
