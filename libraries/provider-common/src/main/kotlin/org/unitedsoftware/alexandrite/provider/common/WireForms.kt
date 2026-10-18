package org.unitedsoftware.alexandrite.provider.common

import org.unitedsoftware.alexandrite.internal.hash.sha256Hex
import org.unitedsoftware.alexandrite.sdk.model.ModelInfo
import org.unitedsoftware.alexandrite.sdk.model.ReasoningEffort
import org.unitedsoftware.alexandrite.sdk.model.Warning
import org.unitedsoftware.alexandrite.sdk.tool.ToolNames
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart

/** The form a wire API takes of a stored value: the value where it matches [grammar], else [MAPPED] and its hash. */
internal class WireForm(private val grammar: Regex) {
    fun of(value: String): String = if (grammar.matches(value) && !value.startsWith(MAPPED)) value else mapped(value)
}

/** Ids of letters, digits, `_` and `-`, as the Messages API takes them. */
internal val MESSAGES_CALL_IDS: WireForm = WireForm(Regex("[A-Za-z0-9_-]{1,64}"))

/** Ids of 40 printable ASCII characters at most, as Chat Completions takes them. */
internal val CHAT_CALL_IDS: WireForm = WireForm(Regex("[!-~]{1,40}"))

/** Ids of 64 printable ASCII characters at most, as the Responses API takes them. */
internal val RESPONSES_CALL_IDS: WireForm = WireForm(Regex("[!-~]{1,64}"))

private val TOOL_NAMES = WireForm(Regex("[A-Za-z0-9_-]{1,64}"))

/** The wire form of the tool name [name]: [ToolNames.wire] of a name of its grammar, a form of [TOOL_NAMES] else. */
internal fun wireToolName(name: String): String = when {
    ToolNames.PATTERN.matches(name) -> ToolNames.wire(name)
    ToolNames.fromWire(name) == null -> TOOL_NAMES.of(name)
    else -> mapped(name)
}

/** The text that stands for [call] of the tool [name] in a request that offers no tools. */
internal fun callText(call: ToolCallPart, name: String): String =
    "[Called tool $name with ${call.arguments.ifEmpty { "{}" }}]"

/** The heading of the text that stands for a result of the tool [name] in a request that offers no tools. */
internal fun resultHeading(name: String, failed: Boolean): String =
    "[Result of tool $name${if (failed) ", which failed" else ""}]"

/** The image types that the Messages and Responses APIs take. */
internal val IMAGE_TYPES: Set<String> = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

/** The warning that [info]'s model takes no [effort], so the request asks for none. */
internal fun unsupportedEffort(effort: ReasoningEffort, info: ModelInfo): Warning = Warning(
    "unsupported_option",
    "Model '${info.id}' takes no reasoning effort '$effort', so the request asks for none.",
)

/** Starts with an uppercase letter, which no wire form of a tool name of [ToolNames.PATTERN] does. */
private const val MAPPED = "X"

private fun mapped(value: String): String = MAPPED + sha256Hex(value).take(HASH_DIGITS)

private const val HASH_DIGITS = 32
