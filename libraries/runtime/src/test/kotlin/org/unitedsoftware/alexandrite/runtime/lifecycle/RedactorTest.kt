package org.unitedsoftware.alexandrite.runtime.lifecycle

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RedactorTest {
    private val redactor = Redactor()

    private fun all(error: Throwable): List<Throwable> =
        listOf(error) + (listOfNotNull(error.cause) + error.suppressed).flatMap(::all)

    @Test
    fun `the token of a Telegram bot URL is masked`() {
        val text = "POST https://api.telegram.org/bot123456789:AAH-x_Y9z/sendMessage failed"

        assertEquals("POST https://api.telegram.org/bot***/sendMessage failed", redactor.text(text))
    }

    @Test
    fun `secrets shorter than four characters are left as they are`() {
        redactor.add(listOf("abc", "  ab  ", "abcd", ""))

        assertEquals("abc *** ab xy", redactor.text("abc abcd ab xy"))
    }

    @Test
    fun `a secret that holds another is masked whole`() {
        redactor.add(listOf("token"))
        redactor.add(listOf("token-long"))

        assertEquals("*** and ***", redactor.text("token-long and token"))
    }

    @Test
    fun `secrets are masked in suppressed exceptions and their causes`() {
        redactor.add(listOf("s3cr3t-value"))
        val clean = IllegalArgumentException("clean")
        val error = IllegalStateException("outer").apply {
            addSuppressed(RuntimeException("suppressed s3cr3t-value", IOException("deeper s3cr3t-value")))
            addSuppressed(clean)
        }

        val masked = redactor.error(error)

        assertTrue(all(masked).none { "s3cr3t-value" in it.toString() }, all(masked).toString())
        assertEquals("java.lang.IllegalStateException: outer", masked.toString())
        val (suppressed, kept) = masked.suppressed.toList()
        assertEquals("java.lang.RuntimeException: suppressed ***", suppressed.toString())
        assertEquals("java.io.IOException: deeper ***", suppressed.cause.toString())
        assertSame(clean, kept)
        assertSame(clean, redactor.error(clean))
    }
}
