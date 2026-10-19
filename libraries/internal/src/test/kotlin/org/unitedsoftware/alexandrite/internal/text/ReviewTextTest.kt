package org.unitedsoftware.alexandrite.internal.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ReviewTextTest {
    @Test
    fun `a typed escape sequence cannot pass for an escaped hidden character`() {
        val typed = ReviewText.escape("notes\\u202Egpj.md")
        val hidden = ReviewText.escape("notes\u202Egpj.md")

        assertEquals("notes\\u202Egpj.md", hidden)
        assertEquals("notes\\\\u202Egpj.md", typed)
        assertNotEquals(typed, hidden)
    }

    @Test
    fun `invisible letter-class fillers are escaped`() {
        assertEquals(
            "a\\u3164b\\u115Fc\\u1160d\\u2800e\\uFFA0f",
            ReviewText.escape("a\u3164b\u115Fc\u1160d\u2800e\uFFA0f"),
        )
    }

    @Test
    fun `control, format and separator code points are escaped, a supplementary one as both of its units`() {
        val text = "a\u0000b\u001Bc\u200Bd\u2066e\u2028f\u2029g\u00ADh\uDB40\uDC41i"

        assertEquals(
            "a\\u0000b\\u001Bc\\u200Bd\\u2066e\\u2028f\\u2029g\\u00ADh\\uDB40\\uDC41i",
            ReviewText.escape(text),
        )
    }

    @Test
    fun `a lone surrogate is escaped, while a pair that renders stays`() {
        assertEquals("x\\uD83Dy \uD83D\uDE00", ReviewText.escape("x\uD83Dy \uD83D\uDE00"))
    }

    @Test
    fun `line breaks are escaped unless kept, and only the line feed is kept`() {
        val text = "one\ntwo\r\nthree\tfour"

        assertEquals("one\\u000Atwo\\u000D\\u000Athree\\u0009four", ReviewText.escape(text))
        assertEquals("one\ntwo\\u000D\nthree\\u0009four", ReviewText.escape(text, keepNewlines = true))
    }

    @Test
    fun `text that holds nothing hidden is returned as it is`() {
        val text = "Plain text, Ünïcödé, 漢字 and \uD83D\uDE00."

        assertEquals(text, ReviewText.escape(text))
    }
}
