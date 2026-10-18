package org.unitedsoftware.alexandrite.channel.telegram.reply

/** The paragraph [text] of a reply that ends at [end], where the next one starts. */
internal class Paragraph(val text: String, val end: Int)

/**
 * The next paragraph of [text] from [from] on, ended by a blank line outside a code fence, null when none is complete
 * yet; the text after the last blank line counts as complete when [whole].
 */
internal fun nextParagraph(text: String, from: Int, whole: Boolean): Paragraph? {
    var start = from
    while (start < text.length && text[start].isWhitespace()) start++
    if (start >= text.length) return null
    var fenced = false
    var lineStart = start
    while (lineStart < text.length) {
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        if (line.trimStart().startsWith(FENCE)) fenced = !fenced
        if (!fenced && line.isBlank() && lineStart > start && lineEnd < text.length) {
            return Paragraph(text.substring(start, lineStart).trimEnd(), lineEnd + 1)
        }
        lineStart = lineEnd + 1
    }
    return if (whole) Paragraph(text.substring(start).trimEnd(), text.length) else null
}

/** Every paragraph of [text] from [from] on, the last one included. */
internal fun paragraphs(text: String, from: Int = 0): List<String> =
    generateSequence(nextParagraph(text, from, whole = true)) { nextParagraph(text, it.end, whole = true) }
        .map { it.text }
        .toList()

private const val FENCE = "```"
