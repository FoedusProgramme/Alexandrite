package org.foedusprogramme.alexandrite.provider.common

import org.foedusprogramme.alexandrite.sdk.tool.ToolNames
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import java.security.MessageDigest
import java.util.HexFormat

/** The form a wire API takes of a stored value: the value where it matches [grammar], else [MAPPED] and its hash. */
internal class WireForm(private val grammar: Regex) {
    fun of(value: String): String = if (grammar.matches(value) && !value.startsWith(MAPPED)) value else mapped(value)
}

/** Ids of letters, digits, `_` and `-`, as the Messages API takes them. */
internal val MESSAGES_CALL_IDS: WireForm = WireForm(Regex("[A-Za-z0-9_-]{1,64}"))

/** Ids of 40 printable ASCII characters at most, as Chat Completions takes them. */
internal val CHAT_CALL_IDS: WireForm = WireForm(Regex("[!-~]{1,40}"))

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

/** Starts with an uppercase letter, which no wire form of a tool name of [ToolNames.PATTERN] does. */
private const val MAPPED = "X"

private fun mapped(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return MAPPED + HexFormat.of().formatHex(digest, 0, HASH_BYTES)
}

private const val HASH_BYTES = 16
