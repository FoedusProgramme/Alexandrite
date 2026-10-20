package org.unitedsoftware.alexandrite.internal.text

/**
 * Text that a person reviews before a tool call runs, shown as it is rather than as it renders.
 *
 * Control, format (bidi controls, zero-width characters, tags), line and paragraph separator and lone surrogate code
 * points become `\uXXXX`, a supplementary one as its two UTF-16 units, and so do the fillers that render blank; a
 * backslash is doubled, so typed text cannot pass for an escape.
 */
public object ReviewText {
    /** [text] with every invisible or reordering code point escaped, where [keepNewlines] leaves `\n` as it is. */
    public fun escape(text: String, keepNewlines: Boolean = false): String {
        if (text.none { it.isSurrogate() || escapes(it.code, keepNewlines) }) return text
        val escaped = StringBuilder(text.length + 16)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val count = Character.charCount(codePoint)
            when {
                codePoint == '\\'.code -> escaped.append("\\\\")

                escapes(codePoint, keepNewlines) -> for (unit in index until index + count) {
                    escaped.append("\\u").append(text[unit].code.toString(HEX).uppercase().padStart(4, '0'))
                }

                else -> escaped.appendCodePoint(codePoint)
            }
            index += count
        }
        return escaped.toString()
    }

    private fun escapes(codePoint: Int, keepNewlines: Boolean): Boolean = when {
        keepNewlines && codePoint == '\n'.code -> false
        codePoint == '\\'.code || codePoint in FILLERS -> true
        else -> Character.getType(codePoint).toByte() in HIDDEN
    }

    private const val HEX = 16

    /** Letters and symbols that render as blank space. */
    private val FILLERS = setOf(0x115F, 0x1160, 0x2800, 0x3164, 0xFFA0)

    private val HIDDEN = setOf(
        Character.CONTROL,
        Character.FORMAT,
        Character.LINE_SEPARATOR,
        Character.PARAGRAPH_SEPARATOR,
        Character.SURROGATE,
    )
}
