package org.foedusprogramme.alexandrite.internal.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** The text of a UTF-8 file, kept apart from its byte order mark. */
public class Utf8Text private constructor(
    /** Without the byte order mark. */
    public val text: String,
    private val byteOrderMark: Boolean,
) {
    /** `\r\n` when more lines of [text] end with it than with a bare `\n`, else `\n`. */
    public val lineBreak: String = dominantLineBreak(text)

    /** [other] with each of its line breaks made [lineBreak]. */
    public fun withLineBreaks(other: String): String {
        val plain = other.replace("\r\n", "\n")
        return if (lineBreak == "\n") plain else plain.replace("\n", lineBreak)
    }

    /** [other] in UTF-8, after a byte order mark when this text's file starts with one. */
    public fun encode(other: String): ByteArray {
        val bytes = other.toByteArray(Charsets.UTF_8)
        return if (byteOrderMark) BYTE_ORDER_MARK + bytes else bytes
    }

    public companion object {
        /** The text of [bytes], null when they are no UTF-8 or hold a NUL character. */
        public fun decode(bytes: ByteArray): Utf8Text? {
            val marked = bytes.size >= BYTE_ORDER_MARK.size &&
                BYTE_ORDER_MARK.indices.all { bytes[it] == BYTE_ORDER_MARK[it] }
            val start = if (marked) BYTE_ORDER_MARK.size else 0
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val text = try {
                decoder.decode(ByteBuffer.wrap(bytes, start, bytes.size - start)).toString()
            } catch (e: CharacterCodingException) {
                return null
            }
            return if ('\u0000' in text) null else Utf8Text(text, marked)
        }
    }
}

private val BYTE_ORDER_MARK = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

private fun dominantLineBreak(text: String): String {
    var crlf = 0
    var lf = 0
    var index = text.indexOf('\n')
    while (index >= 0) {
        if (index > 0 && text[index - 1] == '\r') crlf++ else lf++
        index = text.indexOf('\n', index + 1)
    }
    return if (crlf > lf) "\r\n" else "\n"
}
