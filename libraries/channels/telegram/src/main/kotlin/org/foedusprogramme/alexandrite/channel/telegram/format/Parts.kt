package org.foedusprogramme.alexandrite.channel.telegram.format

import java.util.BitSet

/** This text in parts of [limit] characters at most, cut at the last blank line, else line break, else space. */
internal fun FormattedText.parts(limit: Int = MAX_MESSAGE_LENGTH): List<FormattedText> {
    require(limit >= 2) { "A part holds at least 2 characters, was $limit." }
    if (text.length <= limit || text.isBlank()) return listOf(this)
    return Splitter(this, limit).parts()
}

private class Splitter(private val whole: FormattedText, private val limit: Int) {
    private val text = whole.text

    /** The cuts that would split a link's text or a custom emoji. */
    private val inside = BitSet(text.length).apply {
        for (span in whole.entities) if (span.type in KEPT_WHOLE) set(span.offset + 1, span.end)
    }

    fun parts(): List<FormattedText> {
        val parts = mutableListOf<FormattedText>()
        var start = 0
        while (true) {
            val visible = visibleFrom(start)
            if (visible == text.length) break
            if (text.length - start <= limit) {
                parts += whole.slice(start, text.length)
                break
            }
            val cut = cut(start, visible)
            val end = trimmedEnd(start, cut)
            if (end > start) parts += whole.slice(start, end)
            start = next(cut)
        }
        return parts
    }

    /** Where the part from [start], whose first visible character is at [visible], ends. */
    private fun cut(start: Int, visible: Int): Int {
        val candidates = start + limit downTo visible + 1
        return candidates.firstOrNull { isBlankLine(it) && !inside[it] }
            ?: candidates.firstOrNull { text[it] == '\n' && !inside[it] }
            ?: candidates.firstOrNull { text[it].isWhitespace() && !inside[it] }
            ?: hard(start + limit, visible)
    }

    /** The cut at [last], moved back out of a surrogate pair, a link's text or a custom emoji. */
    private fun hard(last: Int, visible: Int): Int {
        val cut = if (text[last - 1].isHighSurrogate() && text[last].isLowSurrogate()) last - 1 else last
        var before = cut
        while (inside[before]) before--
        return if (before > visible) before else cut
    }

    /** Whether [at] is a line break before a blank line. */
    private fun isBlankLine(at: Int): Boolean {
        if (text[at] != '\n') return false
        var end = at + 1
        while (end < text.length && text[end] != '\n' && text[end].isWhitespace()) end++
        return end < text.length && text[end] == '\n'
    }

    private fun trimmedEnd(start: Int, cut: Int): Int {
        var end = cut
        while (end > start && text[end - 1].isWhitespace()) end--
        return end
    }

    /** Where the part after [cut] starts, keeping the indentation of its first line. */
    private fun next(cut: Int): Int {
        val visible = visibleFrom(cut)
        val line = text.lastIndexOf('\n', visible) + 1
        return if (line > cut) line else visible
    }

    private fun visibleFrom(start: Int): Int {
        var at = start
        while (at < text.length && text[at].isWhitespace()) at++
        return at
    }
}

private val KEPT_WHOLE = setOf("text_link", "custom_emoji")
