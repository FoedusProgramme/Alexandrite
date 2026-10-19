package org.unitedsoftware.alexandrite.channel.telegram.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PartsTest {
    @Test
    fun `a text of one message is one part as it is, a character more is two`() {
        val full = FormattedText("x".repeat(4_096), listOf(Span("bold", 0, 4_096)))
        val over = FormattedText("x".repeat(4_097), listOf(Span("bold", 0, 4_097)))

        assertSame(full, full.parts().single())
        assertEquals(
            listOf(full, FormattedText("x", listOf(Span("bold", 0, 1)))),
            over.parts(),
        )
    }

    // Where a cut falls.

    @Test
    fun `a cut falls at the last blank line that fits, else at the last line break`() {
        val text = FormattedText("Alpha beta.\n\nGamma.\nDelta epsilon zeta.")

        assertEquals(texts("Alpha beta.", "Gamma.", "Delta epsilon zeta."), text.parts(25))
    }

    @Test
    fun `without a line break a cut falls at the last space`() {
        assertEquals(texts("Alpha beta", "gamma delta"), FormattedText("Alpha beta gamma delta").parts(12))
    }

    @Test
    fun `without whitespace a cut falls at the limit, never inside a surrogate pair`() {
        assertEquals(texts("abcd", "efgh", "ij"), FormattedText("abcdefghij").parts(4))
        assertEquals(texts("abc", "\uD83D\uDE00de", "f"), FormattedText("abc\uD83D\uDE00def").parts(4))
    }

    @Test
    fun `a cut drops the whitespace around it and keeps the indentation of the next line`() {
        val text = FormattedText("Items:   \n\n  • one  \n  • two")

        assertEquals(texts("Items:", "  • one", "  • two"), text.parts(12))
    }

    @Test
    fun `a cut keeps out of a link's text, unless the link is longer than a part`() {
        val link = Span("text_link", 5, 14, url = "https://example.org")
        val sentence = FormattedText("Read the long guide now", listOf(link))
        val word = FormattedText("abcdefghij", listOf(Span("text_link", 2, 6, url = "https://example.org")))

        assertEquals(
            listOf(
                FormattedText("Read"),
                FormattedText("the long guide", listOf(link.copy(offset = 0))),
                FormattedText("now"),
            ),
            sentence.parts(16),
        )
        assertEquals(
            listOf(
                FormattedText("ab"),
                FormattedText("cdefg", listOf(Span("text_link", 0, 5, url = "https://example.org"))),
                FormattedText("hij", listOf(Span("text_link", 0, 1, url = "https://example.org"))),
            ),
            word.parts(5),
        )
    }

    // Entities.

    @Test
    fun `entities are clipped to their parts and an entity cut in two goes on in the next`() {
        val text = FormattedText("one two three four", listOf(Span("italic", 0, 18), Span("bold", 4, 9)))

        assertEquals(
            listOf(
                FormattedText("one two", listOf(Span("italic", 0, 7), Span("bold", 4, 3))),
                FormattedText("three", listOf(Span("italic", 0, 5), Span("bold", 0, 5))),
                FormattedText("four", listOf(Span("italic", 0, 4))),
            ),
            text.parts(9),
        )
    }

    @Test
    fun `a code block cut in two is cut at one of its line breaks and reopened with its language`() {
        val rendered = TelegramMarkdown.render("Look:\n```kotlin\nval a = 1\nval b = 2\nval c = 3\n```")
        assertEquals(
            FormattedText("Look:\nval a = 1\nval b = 2\nval c = 3", listOf(Span("pre", 6, 29, language = "kotlin"))),
            rendered,
        )

        assertEquals(
            listOf(
                FormattedText("Look:\nval a = 1\nval b = 2", listOf(Span("pre", 6, 19, language = "kotlin"))),
                FormattedText("val c = 3", listOf(Span("pre", 0, 9, language = "kotlin"))),
            ),
            rendered.parts(30),
        )
    }

    @Test
    fun `every part of a long Markdown text fits and its entities lie inside it`() {
        val paragraph = "Some **bold** words, a `code` span and [a link](https://example.org) in a line.\n"
        val code = "```\n" + (1..80).joinToString("\n") { "    call($it)" } + "\n```\n"
        val rendered = TelegramMarkdown.render((paragraph.repeat(60) + code + "\n").repeat(3))

        val parts = rendered.parts()

        assertEquals(3, parts.size)
        for (part in parts) {
            assertTrue(part.text.length <= 4_096 && part.text.isNotBlank(), "${part.text.length}")
            assertTrue(part.entities.all { it.offset >= 0 && it.length > 0 && it.end <= part.text.length }, "$part")
        }
        val kept = rendered.text.filterNot { it.isWhitespace() }
        assertEquals(kept, parts.joinToString("") { part -> part.text.filterNot { it.isWhitespace() } })
    }

    private fun texts(vararg texts: String): List<FormattedText> = texts.map { FormattedText(it) }
}
