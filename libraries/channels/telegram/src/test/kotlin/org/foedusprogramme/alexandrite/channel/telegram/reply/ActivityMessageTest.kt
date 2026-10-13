package org.foedusprogramme.alexandrite.channel.telegram.reply

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.sdk.channel.ReplyEnd
import org.foedusprogramme.alexandrite.sdk.channel.ToolCallStatus
import org.foedusprogramme.alexandrite.sdk.channel.TurnEvent
import org.foedusprogramme.alexandrite.sdk.channel.TurnEventKind
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.tool.ToolActivity
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityMessageTest {
    private fun TestScope.output(streaming: Boolean = true) = Output(this, streaming)

    @Test
    fun `the channel asks only for tool call events, not thoughts or usage`() = runTest {
        val output = output()

        val events = output.channel.capabilities(output.address()).events

        assertEquals(setOf(TurnEventKind.TOOL_CALL_STARTED, TurnEventKind.TOOL_CALL_UPDATED), events)
    }

    @Test
    fun `a tool call opens an activity message below the body text, edited past the summary's tense`() = runTest {
        val output = output()
        val reply = output.open()
        val id = ToolCallId("c1")

        reply.preview(0, "Let me check.")
        advanceTimeBy(1_100)
        reply.event(TurnEvent.ToolCallStarted(id, "fs.read", ToolActivity.READ, "notes.md"))
        advanceTimeBy(1_100)
        reply.event(TurnEvent.ToolCallUpdated(id, ToolCallStatus.COMPLETED))
        advanceTimeBy(1_100)

        assertEquals(
            listOf(
                "sendMessage Let me check.",
                "sendMessage Reading 1 file\nnotes.md",
                "editMessageText Read 1 file\nnotes.md",
            ),
            output.shown(),
        )
    }

    @Test
    fun `new body text opens a new message, and its tool calls a new activity message below it`() = runTest {
        val output = output()
        val reply = output.open()
        val first = ToolCallId("c1")
        val second = ToolCallId("c2")

        reply.preview(0, "Checking.")
        advanceTimeBy(1_100)
        reply.event(TurnEvent.ToolCallStarted(first, "fs.read", ToolActivity.READ, "notes.md"))
        advanceTimeBy(1_100)
        reply.event(TurnEvent.ToolCallUpdated(first, ToolCallStatus.COMPLETED))
        advanceTimeBy(1_100)

        reply.startSegment(1)
        reply.preview(1, "Found it.")
        advanceTimeBy(1_100)
        reply.event(TurnEvent.ToolCallStarted(second, "command.exec", ToolActivity.EXECUTE, "ls"))
        advanceTimeBy(1_100)
        val output2 = TurnEvent.Content.Output("Exit code: 0\na.txt")
        reply.event(TurnEvent.ToolCallUpdated(second, ToolCallStatus.COMPLETED, output2))
        advanceTimeBy(1_100)
        reply.complete(markdown("Found it. Done."))

        assertEquals(
            listOf(
                "sendMessage Checking.",
                "sendMessage Reading 1 file\nnotes.md",
                "editMessageText Read 1 file\nnotes.md",
                "sendMessage Found it.",
                "sendMessage Running 1 shell command\nls",
                "editMessageText Ran 1 shell command\nls — exit 0",
                "editMessageText Found it. Done.",
            ),
            output.shown(),
        )
    }

    @Test
    fun `the activity message keeps its folded details below the summary`() = runTest {
        val output = output()
        val reply = output.open()
        val (first, second) = ToolCallId("c1") to ToolCallId("c2")

        reply.event(TurnEvent.ToolCallStarted(first, "fs.read", ToolActivity.READ, "a.kt"))
        reply.event(TurnEvent.ToolCallStarted(second, "fs.read", ToolActivity.READ, "b.kt"))
        advanceTimeBy(1_100)
        reply.event(TurnEvent.ToolCallUpdated(first, ToolCallStatus.COMPLETED))
        reply.event(TurnEvent.ToolCallUpdated(second, ToolCallStatus.COMPLETED))
        advanceTimeBy(1_100)

        val edit = output.api.calls("editMessageText").last()
        assertEquals("Read 2 files\na.kt\nb.kt", edit.text)
        assertEquals(listOf("expandable_blockquote 13+9"), edit.entities)
    }

    @Test
    fun `rapid updates before the chat's pace allows a send are coalesced into the latest state`() = runTest {
        val output = output()
        val reply = output.open()
        val (first, second) = ToolCallId("c1") to ToolCallId("c2")

        reply.event(TurnEvent.ToolCallStarted(first, "fs.read", ToolActivity.READ, "a.kt"))
        reply.event(TurnEvent.ToolCallStarted(second, "fs.read", ToolActivity.READ, "b.kt"))
        reply.event(TurnEvent.ToolCallUpdated(first, ToolCallStatus.COMPLETED))
        reply.event(TurnEvent.ToolCallUpdated(second, ToolCallStatus.COMPLETED))
        advanceTimeBy(1_100)

        assertEquals(listOf("sendMessage Read 2 files\na.kt\nb.kt"), output.shown())
    }

    @Test
    fun `an activity message stays after the turn ends, abandoned or completed`() = runTest {
        val output = output()
        val reply = output.open()
        val id = ToolCallId("c1")

        reply.event(TurnEvent.ToolCallStarted(id, "fs.read", ToolActivity.READ, "notes.md"))
        reply.abandon(ReplyEnd.CANCELLED)
        advanceTimeBy(1_100)

        assertEquals(listOf("sendMessage Reading 1 file\nnotes.md"), output.shown())
    }

    @Test
    fun `a sub-agent's calls keep their own activity message apart from the delegation`() = runTest {
        val output = output()
        val reply = output.open()
        val run = RunId("r-1")
        val delegate = ToolCallId("c1")

        reply.event(TurnEvent.ToolCallStarted(delegate, "agent.delegate", ToolActivity.OTHER, "reviewer"))
        reply.event(
            TurnEvent.ToolCallStarted(delegate, "fs.read", ToolActivity.READ, "a.kt", run, AgentId("reviewer")),
        )
        reply.event(TurnEvent.ToolCallUpdated(delegate, ToolCallStatus.COMPLETED, null, run))
        advanceTimeBy(2_200)
        reply.event(TurnEvent.ToolCallUpdated(delegate, ToolCallStatus.COMPLETED))
        advanceTimeBy(1_100)

        assertEquals(
            listOf(
                "sendMessage Delegating to reviewer",
                "sendMessage Read 1 file\nreviewer · a.kt",
                "editMessageText Delegated to reviewer",
            ),
            output.shown(),
        )
    }

    @Test
    fun `four delegation calls to the same profile have four independently updated records`() = runTest {
        val output = output()
        val reply = output.open()
        for (number in 1..4) {
            reply.event(
                TurnEvent.ToolCallStarted(
                    ToolCallId("delegate-$number"),
                    "agent.delegate",
                    ToolActivity.OTHER,
                    "reviewer",
                ),
            )
        }
        advanceTimeBy(5_000)
        for (number in 1..4) {
            reply.event(TurnEvent.ToolCallUpdated(ToolCallId("delegate-$number"), ToolCallStatus.BACKGROUNDED))
        }
        advanceTimeBy(5_000)
        assertEquals(List(4) { "Delegating to reviewer" }, output.api.calls("sendMessage").map { it.text })
        assertEquals(List(4) { "Delegated to reviewer" }, output.api.calls("editMessageText").map { it.text })
    }

    @Test
    fun `four concurrent runs of the same profile keep separate activity messages`() = runTest {
        val output = output()
        val reply = output.open()
        for (number in 1..4) {
            reply.event(
                TurnEvent.ToolCallStarted(
                    ToolCallId("read"),
                    "fs.read",
                    ToolActivity.READ,
                    "file-$number.kt",
                    RunId("r-$number"),
                    AgentId("reviewer"),
                ),
            )
        }
        advanceTimeBy(5_000)
        for (number in 1..4) {
            reply.event(
                TurnEvent.ToolCallUpdated(
                    ToolCallId("read"),
                    ToolCallStatus.COMPLETED,
                    run = RunId("r-$number"),
                ),
            )
        }
        advanceTimeBy(5_000)
        val sent = output.api.calls("sendMessage")
        assertEquals(4, sent.size)
        for (number in 1..4) {
            assertEquals("Reading 1 file\nreviewer · file-$number.kt", sent[number - 1].text)
        }
        assertEquals(4, output.api.calls("editMessageText").size)
    }

    @Test
    fun `a text-less round edits the same activity message instead of sending a new one`() = runTest {
        val output = output()
        val reply = output.open()
        val (first, second) = ToolCallId("c1") to ToolCallId("c2")

        reply.event(TurnEvent.ToolCallStarted(first, "fs.glob", ToolActivity.LIST, "a/**"))
        reply.event(TurnEvent.ToolCallUpdated(first, ToolCallStatus.COMPLETED))
        advanceTimeBy(1_100)
        reply.event(TurnEvent.ToolCallStarted(second, "fs.read", ToolActivity.READ, "a.kt"))
        reply.event(TurnEvent.ToolCallUpdated(second, ToolCallStatus.COMPLETED))
        advanceTimeBy(1_100)

        assertEquals(
            listOf(
                "sendMessage Listed 1 directory\na/**",
                "editMessageText Listed 1 directory · Read 1 file\na/**\na.kt",
            ),
            output.shown(),
        )
    }

    @Test
    fun `an activity message waits for its own segment's text to be sent first`() = runTest {
        val output = output()
        val reply = output.open()
        val id = ToolCallId("c1")

        reply.preview(0, "Checking.")
        reply.event(TurnEvent.ToolCallStarted(id, "fs.read", ToolActivity.READ, "notes.md"))
        advanceTimeBy(2_200)

        assertEquals(
            listOf("sendMessage Checking.", "sendMessage Reading 1 file\nnotes.md"),
            output.shown(),
        )
    }

    @Test
    fun `an update of a call the channel never saw started is ignored`() = runTest {
        val output = output()
        val reply = output.open()

        reply.event(TurnEvent.ToolCallUpdated(ToolCallId("unknown"), ToolCallStatus.COMPLETED))
        advanceTimeBy(1_100)

        assertEquals(emptyList(), output.shown())
    }
}
