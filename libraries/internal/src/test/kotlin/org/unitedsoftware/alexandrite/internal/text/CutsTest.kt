package org.unitedsoftware.alexandrite.internal.text

import kotlin.test.Test
import kotlin.test.assertEquals

class CutsTest {
    private val face = "😀"

    @Test
    fun `a head keeps a surrogate pair whole and a short text as it is`() {
        assertEquals("abc", cut("abc", 5))
        assertEquals("ab", cut("abcd", 2))
        assertEquals("a", cut("a$face", 2))
        assertEquals(1, fittingLength("a$face", 2))
        assertEquals(0, fittingLength("abc", -1))
    }

    @Test
    fun `an ellipsis counts toward the limit at either end`() {
        assertEquals("abcd", cutWithEllipsis("abcd", 4))
        assertEquals("abc…", cutWithEllipsis("abcde", 4))
        assertEquals("ab…", cutWithEllipsis("ab${face}c", 4))
        assertEquals("…cde", cutStartWithEllipsis("abcde", 4))
        assertEquals("…c", cutStartWithEllipsis("ab${face}c", 3))
        assertEquals(4, tailStart("ab${face}c", 2))
    }
}
