package org.foedusprogramme.alexandrite.channel.telegram.reply

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParagraphsTest {
    @Test
    fun `a paragraph is complete once a blank line follows it, and the last only when the text is whole`() {
        val text = "One\nstill one\n\nTwo"

        val first = nextParagraph(text, 0, whole = false)!!
        assertEquals("One\nstill one", first.text)
        assertNull(nextParagraph(text, first.end, whole = false))
        assertEquals("Two", nextParagraph(text, first.end, whole = true)?.text)
    }

    @Test
    fun `a blank line inside a code fence ends no paragraph`() {
        val text = "```\na\n\nb\n```\n\nAfter"

        assertEquals(listOf("```\na\n\nb\n```", "After"), paragraphs(text))
    }

    @Test
    fun `runs of blank lines and surrounding space make no empty paragraphs`() {
        assertEquals(listOf("A", "B"), paragraphs("\n\nA\n\n\n\n  B  \n\n"))
        assertEquals(emptyList(), paragraphs("  \n\n "))
    }
}
