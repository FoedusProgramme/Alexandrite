package org.unitedsoftware.alexandrite.app.tui

import org.jline.utils.WCWidth

private val ESCAPE = Regex("\u001B\\[[0-9;?]*[A-Za-z]")

private const val ELLIPSIS = "…"

/** [text] without its escape sequences. */
internal fun plain(text: String): String = ESCAPE.replace(text, "")

private fun columns(codePoint: Int): Int = WCWidth.wcwidth(codePoint).coerceAtLeast(0)

/** The columns [text] takes on a terminal, escape sequences aside. */
internal fun columns(text: String): Int {
    val shown = plain(text)
    var width = 0
    var index = 0
    while (index < shown.length) {
        val codePoint = shown.codePointAt(index)
        width += columns(codePoint)
        index += Character.charCount(codePoint)
    }
    return width
}

/** [text] cut to at most [width] columns, ending in … where it is cut. */
internal fun fit(text: String, width: Int): String {
    if (columns(text) <= width) return text
    val kept = StringBuilder()
    var used = 0
    var index = 0
    var styled = false
    while (index < text.length) {
        val escape = ESCAPE.matchAt(text, index)
        if (escape != null) {
            kept.append(escape.value)
            styled = true
            index += escape.value.length
            continue
        }
        val codePoint = text.codePointAt(index)
        val wide = columns(codePoint)
        if (used + wide > width - 1) break
        kept.appendCodePoint(codePoint)
        used += wide
        index += Character.charCount(codePoint)
    }
    kept.append(ELLIPSIS)
    if (styled) kept.append("\u001B[0m")
    return "$kept"
}

/** [text] followed by spaces up to [width] columns. */
internal fun pad(text: String, width: Int): String = text + " ".repeat((width - columns(text)).coerceAtLeast(0))

/** A word of [wrap], and whether a space stood before it. */
private class Word(val text: String, val spaced: Boolean)

/** The plain [text] broken into lines of at most [width] columns: at spaces, and between wide characters. */
internal fun wrap(text: String, width: Int): List<String> {
    val limit = width.coerceAtLeast(1)
    return text.split('\n').flatMap { paragraph ->
        val lines = mutableListOf<String>()
        var line = StringBuilder()
        for (word in joined(words(paragraph))) {
            val joined = if (line.isEmpty()) word.text else (if (word.spaced) " " else "") + word.text
            if (columns("$line$joined") <= limit) {
                line.append(joined)
                continue
            }
            if (line.isNotEmpty()) lines += "$line"
            line = StringBuilder()
            for (piece in pieces(word.text, limit)) {
                if (line.isNotEmpty()) lines += "$line"
                line = StringBuilder(piece)
            }
        }
        lines += "$line"
        lines
    }
}

private fun words(paragraph: String): List<Word> {
    val words = mutableListOf<Word>()
    val word = StringBuilder()
    var spaced = false
    var wordSpaced = false
    var index = 0
    fun flush() {
        if (word.isNotEmpty()) words += Word("$word", wordSpaced)
        word.clear()
    }
    while (index < paragraph.length) {
        val codePoint = paragraph.codePointAt(index)
        index += Character.charCount(codePoint)
        when {
            codePoint == ' '.code -> {
                flush()
                spaced = true
            }

            columns(codePoint) > 1 -> {
                flush()
                words += Word(String(Character.toChars(codePoint)), spaced)
                spaced = false
            }

            else -> {
                if (word.isEmpty()) wordSpaced = spaced
                word.appendCodePoint(codePoint)
                spaced = false
            }
        }
    }
    flush()
    return words
}

/** Punctuation that a line neither starts with nor ends with. */
private const val CLOSING = "。，、；：！？）」』】》〉”’…"
private const val OPENING = "（「『【《〈“‘"

/** [words] with closing punctuation joined to the word before it, and opening punctuation to the word after it. */
private fun joined(words: List<Word>): List<Word> {
    val kept = mutableListOf<Word>()
    for (word in words) {
        val last = kept.lastOrNull()
        if (last != null && !word.spaced && (word.text.first() in CLOSING || last.text.last() in OPENING)) {
            kept[kept.size - 1] = Word(last.text + word.text, last.spaced)
        } else {
            kept += word
        }
    }
    return kept
}

/** [word] cut into pieces of at most [width] columns. */
private fun pieces(word: String, width: Int): List<String> {
    val pieces = mutableListOf<String>()
    val piece = StringBuilder()
    var index = 0
    while (index < word.length) {
        val codePoint = word.codePointAt(index)
        index += Character.charCount(codePoint)
        if (piece.isNotEmpty() && columns("$piece") + columns(codePoint) > width) {
            pieces += "$piece"
            piece.clear()
        }
        piece.appendCodePoint(codePoint)
    }
    if (piece.isNotEmpty()) pieces += "$piece"
    return pieces
}
