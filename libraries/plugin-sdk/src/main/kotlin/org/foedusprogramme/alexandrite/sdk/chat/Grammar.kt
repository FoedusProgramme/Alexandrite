package org.foedusprogramme.alexandrite.sdk.chat

import org.foedusprogramme.alexandrite.sdk.InternalAlexandriteApi
import org.foedusprogramme.alexandrite.sdk.plugin.PluginIds

@OptIn(InternalAlexandriteApi::class)
internal fun isId(value: String): Boolean = PluginIds.PATTERN.matches(value)

internal fun requireId(value: String, what: String) {
    require(isId(value)) {
        "Malformed $what '$value': it must be lowercase words of letters and digits, each starting with a letter, " +
            "joined by single hyphens, such as \"main\"."
    }
}

/** Whether [value] can be a chat, thread, user or message id that a channel defines. */
internal fun isOpaqueId(value: String): Boolean = value.isNotEmpty() && value.none(Char::isISOControl)

internal fun requireOpaqueId(value: String, what: String) {
    require(isOpaqueId(value)) { "Malformed $what id '$value': it must be non-empty and hold no control characters." }
}

/** [value] with `%` and each of [reserved] percent-encoded. */
internal fun encodeId(value: String, reserved: String): String {
    if (value.none { it == '%' || it in reserved }) return value
    return buildString {
        for (char in value) {
            if (char == '%' || char in reserved) append(escape(char)) else append(char)
        }
    }
}

/** The id [encodeId] made [text] from, or null when [text] is no such encoding. */
internal fun decodeId(text: String, reserved: String): String? {
    if ('%' !in text) return text.takeIf { it.none { char -> char in reserved } }
    val decoded = StringBuilder()
    var index = 0
    while (index < text.length) {
        val char = text[index]
        when {
            char in reserved -> return null

            char == '%' -> {
                val escaped = (reserved + '%').firstOrNull { text.startsWith(escape(it), index) } ?: return null
                decoded.append(escaped)
                index += 3
                continue
            }

            else -> decoded.append(char)
        }
        index++
    }
    return decoded.toString()
}

private fun escape(char: Char): String = "%%%02X".format(char.code)
