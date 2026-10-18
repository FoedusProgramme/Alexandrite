package org.unitedsoftware.alexandrite.internal.text

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class Utf8TextTest {
    private val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    @Test
    fun `a byte order mark is kept apart from the text and put back when it is encoded`() {
        val text = Utf8Text.decode(bom + "Grüße\n".toByteArray())!!
        val plain = Utf8Text.decode("Grüße\n".toByteArray())!!

        assertEquals("Grüße\n", text.text)
        assertContentEquals(bom + "Tschüss\n".toByteArray(), text.encode("Tschüss\n"))
        assertContentEquals("Tschüss\n".toByteArray(), plain.encode("Tschüss\n"))
    }

    @Test
    fun `bytes that are no UTF-8 or hold a NUL character are no text`() {
        assertNull(Utf8Text.decode("café".toByteArray(Charsets.ISO_8859_1)))
        assertNull(Utf8Text.decode(byteArrayOf(0x41, 0, 0x42)))
    }

    @Test
    fun `new text takes the line break that most lines of the file end with`() {
        val crlf = Utf8Text.decode("a\r\nb\r\nc\n".toByteArray())!!
        val lf = Utf8Text.decode("a\nb\r\nc\n".toByteArray())!!
        val none = Utf8Text.decode("a".toByteArray())!!

        assertEquals(listOf("\r\n", "\n", "\n"), listOf(crlf, lf, none).map { it.lineBreak })
        assertEquals("x\r\ny\r\nz", crlf.withLineBreaks("x\ny\r\nz"))
        assertEquals("x\ny\nz", lf.withLineBreaks("x\ny\r\nz"))
    }
}
