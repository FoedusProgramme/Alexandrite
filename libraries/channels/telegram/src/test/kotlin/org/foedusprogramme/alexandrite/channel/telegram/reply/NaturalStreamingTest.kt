package org.foedusprogramme.alexandrite.channel.telegram.reply

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class NaturalStreamingTest {
    private fun TestScope.output(quote: Boolean = true) = Output(this, mode = StreamingMode.NATURAL, quote = quote)

    @Test
    fun `each paragraph goes out as a message of its own once written, and nothing is edited`() = runTest {
        val output = output()
        val reply = output.open()

        reply.preview(0, "One")
        advanceTimeBy(2_000)
        assertEquals(emptyList(), output.shown())

        reply.preview(0, "One\n\nTwo")
        advanceTimeBy(2_000)
        val delivery = reply.complete(markdown("One\n\nTwo\n\nThree"))

        assertEquals(listOf("sendMessage One", "sendMessage Two", "sendMessage Three"), output.shown())
        assertEquals(3, assertIs<Delivery.Delivered>(delivery).messages.size)
    }

    @Test
    fun `the text a segment ends with goes out when the next segment starts`() = runTest {
        val output = output()
        val reply = output.open()

        reply.preview(0, "Let me look.")
        advanceTimeBy(2_000)
        reply.startSegment(1)
        advanceTimeBy(2_000)

        assertEquals(listOf("sendMessage Let me look."), output.shown())
        reply.complete(markdown("Found it."))
        assertEquals(listOf("sendMessage Let me look.", "sendMessage Found it."), output.shown())
    }

    @Test
    fun `a final that does not go on from what went out goes out whole`() = runTest {
        val output = output()
        val reply = output.open()

        reply.preview(0, "One\n\nTwo")
        advanceTimeBy(2_000)
        reply.complete(markdown("Something else"))

        assertEquals(listOf("sendMessage One", "sendMessage Something else"), output.shown())
    }

    @Test
    fun `in a group only the reply's first message quotes the message it answers, and none when quoting is off`() =
        runTest {
            for (quote in listOf(true, false)) {
                val output = output(quote)
                val reply = output.open(GROUP, trigger = "41")

                reply.complete(markdown("One\n\nTwo"))

                val quoted = output.api.calls("sendMessage").map { it.replyTo }
                assertEquals(if (quote) listOf(41L, null) else listOf(null, null), quoted, "quote $quote")
            }
        }

    @Test
    fun `quoting off keeps an edited reply in a group from quoting too`() = runTest {
        val output = Output(this, quote = false)
        val reply = output.open(GROUP, trigger = "41")

        reply.complete(markdown("Hi."))

        assertEquals(listOf(null), output.api.calls("sendMessage").map { it.replyTo })
    }
}
