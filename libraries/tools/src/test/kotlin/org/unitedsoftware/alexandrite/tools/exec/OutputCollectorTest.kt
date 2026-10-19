package org.unitedsoftware.alexandrite.tools.exec

import kotlin.test.Test
import kotlin.test.assertEquals

class OutputCollectorTest {
    private fun collected(limit: Int, vararg chunks: String): String {
        val collector = OutputCollector(limit)
        for (chunk in chunks) collector.append(chunk.toCharArray(), chunk.length)
        return collector.text()
    }

    @Test
    fun `an output within the limit is kept whole, in any chunks`() {
        assertEquals("abcdefghij", collected(10, "abc", "defg", "hij"))
        assertEquals("", collected(10))
    }

    @Test
    fun `a longer output keeps its head and its tail around a note of what was cut`() {
        assertEquals("abcde\n[… 16 characters cut …]\nvwxyz", collected(10, "abcdefghijklm", "nopqrstuvwxyz"))
        assertEquals("ab\n[… 9 characters cut …]\nl", collected(3, "abcdefghijkl"))
    }

    @Test
    fun `a cut never splits a surrogate pair`() {
        val smile = "😀"

        assertEquals("abc\n[… 5 characters cut …]\nghij", collected(8, "abc$smile", "defghij"))
        assertEquals("abcd\n[… 3 characters cut …]\nxyz", collected(8, "abcd", "e${smile}xyz"))
    }
}
