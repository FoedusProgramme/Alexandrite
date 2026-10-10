package org.foedusprogramme.alexandrite.agent.delivery

import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.agent.TestClock
import org.foedusprogramme.alexandrite.agent.blocking
import org.foedusprogramme.alexandrite.agent.logged
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
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
    fun `the hook replaces a preview, or holds it back while the throttle still counts it`() = runTest {
        val stream = stream { _, text -> if (text.endsWith("!")) null else text.uppercase() }

        stream.text("Hi!")
        stream.text(" Yo", afterMillis = 100)
        stream.text(" there", afterMillis = 700)

        assertEquals(listOf(0 to "Hi!", 0 to "Hi! Yo there"), offered)
        assertEquals(listOf(0 to "HI! YO THERE"), sink.previews)
    }

    @Test
    fun `a stream whose hook holds every preview back shows nothing`() = runTest {
        val stream = stream(interval = 0) { _, _ -> null }

        stream.text("Hel")
        stream.text("lo")

        assertEquals(emptyList(), sink.previews)
        assertFalse(stream.shown)
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
}

private class Sink : ReplySink {
    val previews = mutableListOf<Pair<Int, String>>()
    var failing = false

    override suspend fun preview(segment: Int, text: String) {
        check(!failing) { "Gone." }
        previews += segment to text
    }

    override suspend fun complete(message: OutboundMessage): Delivery = error("A stream never completes its reply.")

    override suspend fun abandon(end: ReplyEnd) = error("A stream never abandons its reply.")
}
