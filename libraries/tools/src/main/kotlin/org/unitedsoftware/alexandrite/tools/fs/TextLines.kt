package org.unitedsoftware.alexandrite.tools.fs

import org.unitedsoftware.alexandrite.internal.path.openRegularFile
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/** A line of a text file without its line break, [cut] when it held more characters than [text] keeps. */
internal class Line(val text: String, val cut: Boolean)

/** Thrown for a file that is no UTF-8 text. */
internal class NotText : Exception()

/** The UTF-8 lines of [source] within its first [maxBytes] bytes, each kept to [maxChars] characters. */
internal class TextLines(source: InputStream, private val maxChars: Int, maxBytes: Long) : Closeable {
    constructor(file: Path, maxChars: Int, maxBytes: Long) : this(openRegularFile(file), maxChars, maxBytes)

    private val input = BoundedInput(source, maxBytes)
    private val reader = InputStreamReader(
        input,
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT),
    )
    private val buffer = CharArray(BUFFER_CHARS)
    private var position = 0
    private var limit = 0
    private var read = 0

    /** Whether the file goes on past the bytes that were read. */
    val truncated: Boolean get() = input.truncated

    /** The next line, null at the end, or [NotText] thrown at a NUL character or bytes that are no UTF-8. */
    fun next(): Line? {
        val text = StringBuilder()
        var cut = false
        var any = false
        while (true) {
            if (position == limit) {
                position = 0
                limit = try {
                    reader.read(buffer).coerceAtLeast(0)
                } catch (e: CharacterCodingException) {
                    throw NotText()
                }
                if (limit == 0) return if (any) line(text, cut) else null
            }
            val char = buffer[position++]
            any = true
            when {
                char == '\n' -> return line(text, cut)
                char == '\u0000' -> throw NotText()
                text.length < maxChars -> text.append(char)
                else -> cut = true
            }
        }
    }

    private fun line(text: StringBuilder, cut: Boolean): Line {
        if (!cut && text.endsWith('\r')) text.setLength(text.length - 1)
        if (cut && text.last().isHighSurrogate()) text.setLength(text.length - 1)
        if (read++ == 0 && text.startsWith('﻿')) text.deleteCharAt(0)
        return Line(text.toString(), cut)
    }

    override fun close() {
        reader.close()
    }
}

/** [input] up to [remaining] bytes, and the end of a UTF-8 sequence that the limit splits. */
private class BoundedInput(input: InputStream, private var remaining: Long) : InputStream() {
    private val input = input.buffered()
    private var finishing = 0

    var truncated = false
        private set

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (remaining > 0) {
            val count = input.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (count > 0) remaining -= count
            return count
        }
        input.mark(1)
        val next = input.read()
        if (next == -1) return -1
        if (next and 0xC0 == 0x80 && finishing < 3) {
            finishing++
            b[off] = next.toByte()
            return 1
        }
        input.reset()
        truncated = true
        return -1
    }

    override fun close() {
        input.close()
    }
}

private const val BUFFER_CHARS = 8192
