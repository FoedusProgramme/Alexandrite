package org.unitedsoftware.alexandrite.channel.telegram.format

/**
 * Renders Markdown as models write it into a text and the Telegram entities that format it.
 *
 * Emphasis follows CommonMark's delimiter rules, which CJK text relaxes: its punctuation counts as letters, and a CJK
 * character outside a delimiter lets punctuation stand inside it.
 */
internal object TelegramMarkdown {
    fun render(markdown: String): FormattedText {
        val out = Out()
        Blocks(out, quoted = false).render(markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n'))
        return out.build()
    }
}

/** The text rendered so far and its entities. */
private class Out {
    private val text = StringBuilder()
    private val spans = mutableListOf<Span>()
    private val open = ArrayDeque<String>()

    val length: Int get() = text.length

    fun append(value: CharSequence) {
        text.append(value)
    }

    fun append(value: Char) {
        text.append(value)
    }

    /** Appends what [body] appends as an entity of [type], or without one inside an entity of the same type. */
    fun span(type: String, url: String? = null, language: String? = null, body: () -> Unit) {
        if (type in open) {
            body()
            return
        }
        val start = text.length
        open.addLast(type)
        body()
        open.removeLast()
        if (text.length > start) spans += Span(type, start, text.length - start, url, language)
    }

    /** The text without its leading blank lines and trailing whitespace. */
    fun build(): FormattedText {
        val first = text.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return FormattedText("")
        val start = text.lastIndexOf("\n", first) + 1
        val end = text.indexOfLast { !it.isWhitespace() } + 1
        val kept = spans.mapNotNull { span ->
            val from = maxOf(span.offset, start)
            val to = minOf(span.end, end)
            if (to <= from) null else span.copy(offset = from - start, length = to - from)
        }
        return FormattedText(text.substring(start, end), kept.sortedWith(OUTER_FIRST))
    }
}

/** Renders lines of Markdown, the lines of a blockquote when [quoted]. */
private class Blocks(private val out: Out, private val quoted: Boolean) {
    fun render(lines: List<String>) {
        var at = 0
        while (at < lines.size) {
            if (at > 0) out.append('\n')
            at = block(lines, at)
        }
    }

    /** Renders the block that starts at line [at] and returns the line after it. */
    private fun block(lines: List<String>, at: Int): Int {
        val line = lines[at]
        fenceOpening(line)?.let { return fence(lines, at, it) }
        if (startsTable(lines, at)) return table(lines, at)
        if (RULE.matches(line)) {
            out.append(RULE_TEXT)
            return at + 1
        }
        HEADING.matchEntire(line)?.let { heading ->
            out.span(BOLD) { inline(heading.groupValues[1]) }
            return at + 1
        }
        if (QUOTE.matches(line)) return quote(lines, at)
        BULLET.matchEntire(line)?.let { item ->
            out.append(item.groupValues[1])
            out.append(BULLET_TEXT)
            inline(item.groupValues[2])
            return at + 1
        }
        if (line.isBlank() || ORDERED.matches(line)) {
            inline(line)
            return at + 1
        }
        var end = at + 1
        while (end < lines.size && continuesParagraph(lines, end)) end++
        inline(lines.subList(at, end).joinToString("\n") { it.trimEnd() })
        return end
    }

    private fun continuesParagraph(lines: List<String>, at: Int): Boolean {
        val line = lines[at]
        return line.isNotBlank() && fenceOpening(line) == null && !startsTable(lines, at) && !RULE.matches(line) &&
            !HEADING.matches(line) && !QUOTE.matches(line) && !BULLET.matches(line) && !ORDERED.matches(line)
    }

    private fun fence(lines: List<String>, at: Int, fence: Fence): Int {
        var end = at + 1
        while (end < lines.size && !fence.closedBy(lines[end])) end++
        val body = lines.subList(at + 1, end).joinToString("\n") { fence.unindented(it) }
        if (quoted) out.append(body) else out.span(PRE, language = fence.language) { out.append(body) }
        return if (end < lines.size) end + 1 else end
    }

    private fun table(lines: List<String>, at: Int): Int {
        var end = at + 2
        while (end < lines.size && lines[end].isNotBlank() && '|' in lines[end]) end++
        val raw = lines.subList(at, end).joinToString("\n") { it.trimEnd() }
        if (quoted) out.append(raw) else out.span(PRE) { out.append(raw) }
        return end
    }

    private fun quote(lines: List<String>, at: Int): Int {
        var end = at
        while (end < lines.size && QUOTE.matches(lines[end])) end++
        val inner = lines.subList(at, end).map { QUOTE_MARKERS.replaceFirst(it, "") }
        out.span(BLOCKQUOTE) { Blocks(out, quoted = true).render(inner) }
        return end
    }

    private fun inline(text: String) {
        Inline(text.trimEnd(), out).render()
    }
}

/** A code fence opened by a run of [length] [marker]s. */
internal class Fence(
    private val indent: Int,
    private val marker: Char,
    private val length: Int,
    val language: String?,
) {
    fun closedBy(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.length >= length && trimmed.all { it == marker }
    }

    /** [line] without the indentation of the opening fence. */
    fun unindented(line: String): String {
        var start = 0
        while (start < indent && start < line.length && (line[start] == ' ' || line[start] == '\t')) start++
        return line.substring(start)
    }
}

/** The fence [line] opens, null when it opens none. */
internal fun fenceOpening(line: String): Fence? {
    val match = FENCE.matchEntire(line) ?: return null
    val (indent, marker, info) = match.destructured
    if (marker[0] == '`' && '`' in info) return null
    val language = info.trim().split(' ', '\t').first().takeIf { LANGUAGE.matches(it) }
    return Fence(indent.length, marker[0], marker.length, language)
}

private fun startsTable(lines: List<String>, at: Int): Boolean =
    at + 1 < lines.size && '|' in lines[at] && '|' in lines[at + 1] && DELIMITER_ROW.matches(lines[at + 1])

/** Renders the inline Markdown of one block. */
private class Inline(private val source: String, private val out: Out) {
    private val nodes = mutableListOf<Node>()

    fun render() {
        var at = 0
        while (at < source.length) at = token(at)
        emphasis(bottom = -1)
        write(nodes, inLink = false)
    }

    /** Reads the token at [at] and returns the index after it. */
    private fun token(at: Int): Int = when (val char = source[at]) {
        '\\' -> escape(at)

        '`' -> code(at)

        '*', '_', '~' -> delimiter(at, char)

        '[' -> {
            nodes += Bracket()
            at + 1
        }

        ']' -> closeBracket(at)

        else -> {
            var end = at + 1
            while (end < source.length && source[end] !in SPECIAL) end++
            text(source.subSequence(at, end))
            end
        }
    }

    private fun escape(at: Int): Int {
        val next = source.getOrNull(at + 1)
        return when {
            next == '\n' || (next != null && next.isAsciiPunctuation()) -> {
                text(next.toString())
                at + 2
            }

            else -> {
                text("\\")
                at + 1
            }
        }
    }

    private fun code(at: Int): Int {
        val length = run(at, '`')
        var search = at + length
        while (true) {
            val closing = source.indexOf('`', search)
            if (closing < 0) break
            val closingLength = run(closing, '`')
            if (closingLength == length) {
                nodes += Code(codeText(source.substring(at + length, closing)))
                return closing + length
            }
            search = closing + closingLength
        }
        text("`".repeat(length))
        return at + length
    }

    private fun delimiter(at: Int, char: Char): Int {
        val length = run(at, char)
        if (char == '~' && length != 2) {
            text("~".repeat(length))
            return at + length
        }
        val before = if (at == 0) SPACE else Character.codePointBefore(source, at)
        val after = if (at + length >= source.length) SPACE else Character.codePointAt(source, at + length)
        val left = !isSpace(after) &&
            (!isPunctuation(after) || isSpace(before) || isPunctuation(before) || isCjk(before))
        val right = !isSpace(before) &&
            (!isPunctuation(before) || isSpace(after) || isPunctuation(after) || isCjk(after))
        nodes += if (char == '_') {
            Delimiter(char, length, left && (!right || isPunctuation(before)), right && (!left || isPunctuation(after)))
        } else {
            Delimiter(char, length, left, right)
        }
        return at + length
    }

    private fun closeBracket(at: Int): Int {
        val index = nodes.indexOfLast { it is Bracket && it.open }
        if (index < 0) {
            text("]")
            return at + 1
        }
        val bracket = nodes[index] as Bracket
        val link = if (bracket.active) destination(at + 1) else null
        if (link == null) {
            bracket.open = false
            text("]")
            return at + 1
        }
        emphasis(bottom = index)
        val tail = nodes.subList(index + 1, nodes.size)
        val children = tail.toList()
        tail.clear()
        nodes.removeAt(index)
        nodes.forEach { if (it is Bracket) it.active = false }
        nodes += Element(TEXT_LINK, link.url, children)
        return link.end
    }

    /** The link destination of `[text](destination "title")` that starts at [start], null when there is none. */
    private fun destination(start: Int): Link? {
        if (source.getOrNull(start) != '(') return null
        var at = spaces(start + 1)
        val url: String
        if (source.getOrNull(at) == '<') {
            val close = source.indexOf('>', at + 1)
            if (close < 0) return null
            val raw = source.substring(at + 1, close)
            if ('\n' in raw || '<' in raw) return null
            url = unescaped(raw)
            at = close + 1
        } else {
            val begin = at
            var depth = 0
            while (at < source.length) {
                val char = source[at]
                if (char == '\\' && source.getOrNull(at + 1)?.isAsciiPunctuation() == true) {
                    at += 2
                    continue
                }
                if (char.isWhitespace() || char.isISOControl() || (char == ')' && depth == 0)) break
                if (char == '(') depth++
                if (char == ')') depth--
                at++
            }
            url = unescaped(source.substring(begin, at))
        }
        at = spaces(at)
        val quote = source.getOrNull(at)
        if (quote == '"' || quote == '\'' || quote == '(') {
            val closing = if (quote == '(') ')' else quote
            at++
            while (at < source.length && source[at] != closing) at += if (source[at] == '\\') 2 else 1
            if (at >= source.length) return null
            at = spaces(at + 1)
        }
        if (source.getOrNull(at) != ')' || !LINKABLE.matches(url)) return null
        return Link(url, at + 1)
    }

    /** CommonMark's "process emphasis" over the delimiters after node [bottom]. */
    private fun emphasis(bottom: Int) {
        var closerAt = bottom + 1
        while (closerAt < nodes.size) {
            val closer = nodes[closerAt] as? Delimiter
            if (closer == null || !closer.active || !closer.closes) {
                closerAt++
                continue
            }
            val openerAt = (closerAt - 1 downTo bottom + 1).firstOrNull { opens(nodes[it], closer) }
            if (openerAt == null) {
                if (!closer.opens) closer.active = false
                closerAt++
                continue
            }
            val opener = nodes[openerAt] as Delimiter
            val used = if (opener.count >= 2 && closer.count >= 2) 2 else 1
            val type = when {
                closer.char == '~' -> STRIKETHROUGH
                used == 2 -> BOLD
                else -> ITALIC
            }
            opener.count -= used
            closer.count -= used
            val between = nodes.subList(openerAt + 1, closerAt)
            val children = between.toList()
            between.clear()
            children.forEach { if (it is Delimiter) it.active = false }
            nodes.add(openerAt + 1, Element(type, null, children))
            closerAt = openerAt + 2
            if (opener.count == 0) {
                nodes.removeAt(openerAt)
                closerAt--
            }
            if (closer.count == 0) nodes.removeAt(closerAt)
        }
        for (index in bottom + 1 until nodes.size) (nodes[index] as? Delimiter)?.active = false
    }

    /** Whether [node] opens what [closer] closes, by CommonMark's rule of three. */
    private fun opens(node: Node, closer: Delimiter): Boolean {
        if (node !is Delimiter || !node.active || !node.opens || node.char != closer.char) return false
        if (closer.char == '~' || !(node.closes || closer.opens)) return true
        return (node.original + closer.original) % 3 != 0 || (node.original % 3 == 0 && closer.original % 3 == 0)
    }

    private fun write(nodes: List<Node>, inLink: Boolean) {
        for (node in nodes) {
            when (node) {
                is Text -> out.append(node.text)

                is Delimiter -> repeat(node.count) { out.append(node.char) }

                is Bracket -> out.append('[')

                is Code -> if (inLink) out.append(node.text) else out.span(CODE) { out.append(node.text) }

                is Element -> out.span(node.type, node.url) {
                    val start = out.length
                    write(node.children, inLink || node.type == TEXT_LINK)
                    if (node.url != null && out.length == start) out.append(node.url)
                }
            }
        }
    }

    private fun text(value: CharSequence) {
        val last = nodes.lastOrNull()
        if (last is Text) last.text.append(value) else nodes += Text(StringBuilder(value))
    }

    private fun run(at: Int, char: Char): Int {
        var end = at
        while (end < source.length && source[end] == char) end++
        return end - at
    }

    /** The index after the spaces and the one line break at most from [at] on. */
    private fun spaces(at: Int): Int {
        var end = at
        var breaks = 0
        while (end < source.length && (source[end] == ' ' || source[end] == '\t' || source[end] == '\n')) {
            if (source[end] == '\n' && ++breaks > 1) break
            end++
        }
        return end
    }
}

private sealed interface Node

private class Text(val text: StringBuilder) : Node

private class Code(val text: String) : Node

/** A `[` that may open a link. */
private class Bracket : Node {
    /** Whether a `]` may still close it. */
    var open = true

    /** Whether it may still become a link. */
    var active = true
}

/** A run of `*`, `_` or `~~`, of which [count] characters are left. */
private class Delimiter(val char: Char, var count: Int, val opens: Boolean, val closes: Boolean) : Node {
    val original = count
    var active = true
}

private class Element(val type: String, val url: String?, val children: List<Node>) : Node

private class Link(val url: String, val end: Int)

/** The text of a code span: line breaks as spaces, without one space on each side. */
private fun codeText(raw: String): String {
    val text = raw.replace('\n', ' ')
    val padded = text.length >= 2 && text.first() == ' ' && text.last() == ' ' && text.isNotBlank()
    return if (padded) text.substring(1, text.length - 1) else text
}

private fun unescaped(text: String): String = ESCAPED.replace(text) { it.value.substring(1) }

private fun Char.isAsciiPunctuation(): Boolean = this in ASCII_PUNCTUATION

private fun isSpace(codePoint: Int): Boolean = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)

private fun isPunctuation(codePoint: Int): Boolean = when {
    codePoint < ASCII_END -> codePoint.toChar() in ASCII_PUNCTUATION
    isCjkPunctuation(codePoint) -> false
    else -> Character.getType(codePoint) in PUNCTUATION_TYPES
}

private fun isCjk(codePoint: Int): Boolean =
    isCjkPunctuation(codePoint) || Character.UnicodeScript.of(codePoint) in CJK_SCRIPTS

private fun isCjkPunctuation(codePoint: Int): Boolean = codePoint in 0x3000..0x303F || codePoint in 0xFF00..0xFFEF

private const val BOLD = "bold"
private const val ITALIC = "italic"
private const val STRIKETHROUGH = "strikethrough"
private const val CODE = "code"
private const val PRE = "pre"
private const val TEXT_LINK = "text_link"
private const val BLOCKQUOTE = "blockquote"

private const val RULE_TEXT = "———"
private const val BULLET_TEXT = "• "
private const val SPECIAL = "\\`*_~[]"
private const val ASCII_PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"
private const val ASCII_END = 0x80
private const val SPACE = ' '.code

private val OUTER_FIRST = compareBy<Span> { it.offset }.thenByDescending { it.length }

private val PUNCTUATION_TYPES = setOf(
    Character.CONNECTOR_PUNCTUATION,
    Character.DASH_PUNCTUATION,
    Character.START_PUNCTUATION,
    Character.END_PUNCTUATION,
    Character.INITIAL_QUOTE_PUNCTUATION,
    Character.FINAL_QUOTE_PUNCTUATION,
    Character.OTHER_PUNCTUATION,
    Character.MATH_SYMBOL,
    Character.CURRENCY_SYMBOL,
    Character.MODIFIER_SYMBOL,
    Character.OTHER_SYMBOL,
).map { it.toInt() }.toSet()

private val CJK_SCRIPTS = setOf(
    Character.UnicodeScript.HAN,
    Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA,
    Character.UnicodeScript.HANGUL,
    Character.UnicodeScript.BOPOMOFO,
)

private val FENCE = Regex("""^([ \t]*)(`{3,}|~{3,})(.*)$""")
private val LANGUAGE = Regex("""[A-Za-z0-9_+#.-]{1,32}""")
private val DELIMITER_ROW = Regex("""^[ \t]*\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$""")
private val RULE = Regex("""^ {0,3}(?:(?:\*[ \t]*){3,}|(?:-[ \t]*){3,}|(?:_[ \t]*){3,})$""")
private val HEADING = Regex("""^ {0,3}#{1,6}(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$""")
private val QUOTE = Regex("""^ {0,3}>.*$""")
private val QUOTE_MARKERS = Regex("""^ {0,3}(?:>[ \t]?)+""")
private val BULLET = Regex("""^([ \t]*)[-*+][ \t]+(.*)$""")
private val ORDERED = Regex("""^[ \t]*\d{1,9}[.)](?:[ \t]+.*)?$""")
private val LINKABLE = Regex("""(?i)https?://[^\s/?#]+\S*|tg:\S+""")
private val ESCAPED = Regex("""\\[!-/:-@\[-`{-~]""")
