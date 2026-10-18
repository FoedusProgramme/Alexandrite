package org.unitedsoftware.alexandrite.internal.text

import kotlin.test.Test
import kotlin.test.assertEquals

class SnippetsTest {
    @Test
    fun `a short text comes back on one line`() {
        assertEquals("a b c", snippet(" a\n b\t\tc ", listOf("b"), 20))
    }

    @Test
    fun `a long text is cut around the first word found`() {
        val text = "x".repeat(50) + " Needle here " + "y".repeat(50)

        val cut = snippet(text, listOf("missing", "needle"), 20)

        assertEquals("…xxx Needle here yy…", cut)
        assertEquals(20, cut.length)
    }

    @Test
    fun `a text without the words is cut at its head and the ends keep surrogate pairs whole`() {
        assertEquals("abcdefgh…", snippet("abcdefghijkl", listOf("z"), 10))
        assertEquals("😀abcdef…", snippet("😀abcdefghij", listOf("z"), 10))
        assertEquals("…abcdefg…", snippet("x😀abcdefghij", listOf("b"), 10))
    }
}
