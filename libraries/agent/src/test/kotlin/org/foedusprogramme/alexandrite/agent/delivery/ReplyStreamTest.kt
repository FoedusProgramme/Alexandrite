package org.foedusprogramme.alexandrite.agent.delivery

import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.agent.TestClock
import org.foedusprogramme.alexandrite.agent.blocking
import org.foedusprogramme.alexandrite.agent.logged
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.channel.ReplyEnd
import org.foedusprogramme.alexandrite.sdk.channel.ReplySink
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.model.ModelEvent
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReplyStreamTest {
    private val clock = TestClock()
    private val sink = Sink()
    private val offered = mutableListOf<Pair<Int, String>>()

    private fun stream(interval: Long = 750, preview: (Int, String) -> String? = { _, text -> text }): ReplyStream =
        ReplyStream(TurnId("t-1"), sink, Duration.ofMillis(interval), clock) { segment, text ->
            offered += segment to text
            preview(segment, text)
        }

    private suspend fun ReplyStream.text(text: String, afterMillis: Long = 0, index: Int = 0) {
        clock.now = clock.now.plusMillis(afterMillis)
        event(ModelEvent.TextDelta(index, text))
    }

    @Test
    fun `a preview goes out at most once per interval, with all the text so far`() = runTest {
        val stream = stream()

        stream.text("Hel")
        stream.text("lo", afterMillis = 100)
        stream.text(" there", afterMillis = 600)
        stream.text("!", afterMillis = 50)
        stream.text(" Bye.", afterMillis = 100)

        assertEquals(listOf(0 to "Hel", 0 to "Hello there!"), sink.previews)
        assertTrue(stream.shown)
    }

    @Test
    fun `a preview goes out only when the text changed`() = runTest {
        val stream = stream(interval = 0)

        stream.text("One")
        stream.text(" ", index = 1)
        stream.text("Two", index = 2)
        stream.nextSegment()

        assertEquals(listOf(0 to "One", 0 to "One\n\nTwo"), sink.previews)
    }

    @Test
    fun `reasoning is never previewed, nor the text after the round's first tool call`() = runTest {
        val stream = stream(interval = 0)

        stream.event(ModelEvent.ReasoningDelta(0, "Let me think.", null))
        stream.text("Looking.", index = 1)
        stream.event(ModelEvent.ToolCallStarted(2, ToolCallId("c1"), "fs.read"))
        stream.text("Done.", index = 3)
        stream.nextSegment()
        stream.text("Found it.")

        assertEquals(listOf(0 to "Looking.", 1 to "Found it."), sink.previews)
    }

    @Test
    fun `the last text of a segment is shown before the next segment starts`() = runTest {
        val stream = stream()

        stream.text("Hel")
        stream.text("lo", afterMillis = 100)
        stream.nextSegment()
        stream.text("Next", afterMillis = 100)
        stream.text("!", afterMillis = 700)
        stream.nextSegment()

        assertEquals(listOf(0 to "Hel", 0 to "Hello", 1 to "Next!"), sink.previews)
    }

    @Test
    fun `a round with no text keeps the segment, so a later round's calls join the same one`() = runTest {
        val stream = stream()

        stream.text("Hel")
        stream.text("lo", afterMillis = 100)
        stream.nextSegment()
        stream.nextSegment()
        stream.text("Done", afterMillis = 800)

        assertEquals(listOf("preview 0 Hel", "preview 0 Hello", "start 1", "preview 1 Done"), sink.calls)
    }

    @Test
    fun `a segment starts on the sink also when no preview is shown`() = runTest {
        val stream = stream(interval = 0) { _, _ -> null }

        stream.text("Hel")
        stream.nextSegment()
        stream.text("World")

        assertEquals(listOf("start 1"), sink.calls)
        assertFalse(stream.shown)
    }

    @Test
    fun `text after tool calls starts a new segment`() = runTest {
        val stream = stream(interval = 0)

        stream.text("Looking.")
        stream.event(ModelEvent.ToolCallStarted(1, ToolCallId("c1"), "fs.read"))
        stream.nextSegment()
        stream.text("Found it.")

        assertEquals(listOf("preview 0 Looking.", "start 1", "preview 1 Found it."), sink.calls)
    }

    @Test
    fun `a paused round's text is followed by a new segment too`() = runTest {
        val stream = stream(interval = 0)

        stream.text("Here goes.")
        stream.nextSegment()
        stream.text("The rest.")

        assertEquals(listOf("preview 0 Here goes.", "start 1", "preview 1 The rest."), sink.calls)
    }

    @Test
    fun `a tool call flushes the round's held-back text at once`() = runTest {
        val stream = stream()

        stream.text("Looking")
        stream.text(" closely.", afterMillis = 100)
        stream.event(ModelEvent.ToolCallStarted(1, ToolCallId("c1"), "fs.read"))

        assertEquals(listOf(0 to "Looking", 0 to "Looking closely."), sink.previews)
    }

    @Test
    fun `a discarded response's preview is replaced by the next response's, even where it reads the same`() = runTest {
        val stream = stream()

        stream.text("Saving it.")
        stream.text(" <", afterMillis = 100)
        stream.discard()
        stream.text("Saving it.", afterMillis = 100)
        stream.event(ModelEvent.ToolCallStarted(1, ToolCallId("c1"), "fs.write"))
        stream.nextSegment()
        stream.text("Saved.", afterMillis = 800)

        assertEquals(listOf(0 to "Saving it.", 0 to "Saving it.", 1 to "Saved."), sink.previews)
    }

    @Test
    fun `a segment that still shows a discarded response's text takes the next round's text`() = runTest {
        val stream = stream(interval = 0)

        stream.text("Saving it.")
        stream.discard()
        stream.event(ModelEvent.ToolCallStarted(0, ToolCallId("c1"), "fs.write"))
        stream.nextSegment()
        stream.text("Saved.")
        stream.nextSegment()
        stream.text("Found it.")

        assertEquals(
            listOf("preview 0 Saving it.", "preview 0 Saved.", "start 1", "preview 1 Found it."),
            sink.calls,
        )
    }

    @Test
    fun `a discarded response that showed nothing leaves the segment as it is`() = runTest {
        val stream = stream()

        stream.text("Done.")
        stream.nextSegment()
        stream.discard()
        stream.nextSegment()
        stream.text("Next.", afterMillis = 800)

        assertEquals(listOf("preview 0 Done.", "start 1", "preview 1 Next."), sink.calls)
    }

    @Test
    fun `the hook replaces a preview, or holds it back while the throttle still counts it`() = runTest {
        val stream = stream { _, text -> if (text.endsWith("!")) null else text.uppercase() }

        stream.text("Hi!")
        stream.text(" Yo", afterMillis = 100)
        stream.text(" there", afterMillis = 700)

        assertEquals(listOf(0 to "Hi!", 0 to "Hi! Yo there"), offered)
        assertEquals(listOf(0 to "HI! YO THERE"), sink.previews)
    }

    @Test
    fun `a preview the sink fails is logged and the stream goes on`() {
        sink.failing = true
        val stream = stream(interval = 0)

        val lines = logged {
            blocking {
                stream.text("Hel")
                stream.text("lo")
            }
        }

        assertTrue(stream.shown)
        assertEquals(
            List(2) { "WARN A preview of turn t-1 did not reach its chat: java.lang.IllegalStateException: Gone." },
            lines,
        )
    }

    @Test
    fun `a preview the sink fails with an error is logged and the stream goes on`() {
        sink.error = NotImplementedError("Not yet.")
        val stream = stream(interval = 0)

        val lines = logged { blocking { stream.text("Hel") } }

        assertEquals(
            listOf("WARN A preview of turn t-1 did not reach its chat: kotlin.NotImplementedError: Not yet."),
            lines,
        )
    }

    @Test
    fun `a delivery that throws an error is not delivered`() {
        val delivery = blocking { delivered { throw NotImplementedError("Not yet.") } }

        assertEquals(Delivery.NotDelivered(DeliveryFailure.UNKNOWN, "kotlin.NotImplementedError: Not yet."), delivery)
    }

    @Test
    fun `a segment start the sink fails is logged and the stream goes on`() {
        sink.failing = true
        val stream = stream(interval = 0) { _, _ -> null }

        val lines = logged {
            blocking {
                stream.text("One")
                stream.nextSegment()
                stream.text("Two")
                stream.nextSegment()
                stream.text("Three")
            }
        }

        assertEquals(
            (1..2).map {
                "WARN The start of segment $it of turn t-1 did not reach its chat: " +
                    "java.lang.IllegalStateException: Gone."
            },
            lines,
        )
    }
}

private class Sink : ReplySink {
    val previews = mutableListOf<Pair<Int, String>>()
    val calls = mutableListOf<String>()
    var failing = false
    var error: Error? = null

    override suspend fun preview(segment: Int, text: String) {
        check(!failing) { "Gone." }
        error?.let { throw it }
        previews += segment to text
        calls += "preview $segment $text"
    }

    override suspend fun startSegment(segment: Int) {
        check(!failing) { "Gone." }
        calls += "start $segment"
    }

    override suspend fun complete(message: OutboundMessage): Delivery = error("A stream never completes its reply.")

    override suspend fun abandon(end: ReplyEnd) = error("A stream never abandons its reply.")
}
