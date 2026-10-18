package org.foedusprogramme.alexandrite.channel.telegram.reply

import org.foedusprogramme.alexandrite.sdk.channel.DelegatedRun
import org.foedusprogramme.alexandrite.sdk.channel.ToolCallStatus
import org.foedusprogramme.alexandrite.sdk.channel.TurnEvent
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.RunId
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.tool.ToolActivity
import org.foedusprogramme.alexandrite.testkit.testTexts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActivityBoardTest {
    private val board = ActivityBoard(testTexts("alexandrite-channel-telegram"), null)
    private val run = RunId("r-1")
    private val delegate = ToolCallId("delegate")

    private fun started(id: String, run: RunId? = null) = TurnEvent.ToolCallStarted(
        ToolCallId(id),
        "fs.read",
        ToolActivity.READ,
        "$id.kt",
        run,
        run?.let {
            AgentId("general")
        },
    )

    private fun delegation() = TurnEvent.ToolCallStarted(delegate, "agent.delegate", ToolActivity.DELEGATE, "general")

    /** The calls of each message, by their ids, in message order. */
    private fun layout(): List<List<String>> = board.takeAll().map { (index, _) ->
        board[index].calls.keys.map { it.second.value }
    }

    @Test
    fun `the calls of a segment share a message, and a delegation and its run's calls get their own`() {
        board.record(started("a"), 0)
        board.record(delegation(), 0)
        board.record(started("b"), 0)
        board.record(started("c", run), 0)

        assertEquals(listOf(listOf("a", "b"), listOf("delegate"), listOf("c")), layout())
    }

    @Test
    fun `a detached run's calls join its delegation's message, whose own updates are then ignored`() {
        board.record(delegation(), 0)
        board.takeAll()

        val call = assertNotNull(board.detach(DelegatedRun(delegate, run, AgentId("general"))))
        assertEquals(ToolCallStatus.BACKGROUNDED to run, call.status to call.run)
        assertTrue(board.isDetached(run))
        assertNull(board.detach(DelegatedRun(delegate, run, AgentId("general"))))
        assertFalse(board.record(TurnEvent.ToolCallUpdated(delegate, ToolCallStatus.COMPLETED), 0))
        board.record(started("c", run), 3)

        assertEquals(listOf(listOf("delegate", "c")), layout())
        assertTrue(board.close(run, call, ToolCallStatus.COMPLETED))
        assertEquals(ToolCallStatus.COMPLETED, call.status)
        assertFalse(board.hasDetached)
        assertFalse(board.close(run, call, ToolCallStatus.FAILED))
    }

    @Test
    fun `a run whose delegation no message shows cannot be detached`() {
        assertNull(board.detach(DelegatedRun(delegate, run, AgentId("general"))))
        assertFalse(board.hasDetached)
    }

    @Test
    fun `only a ready message is taken next, and a taken one waits for its next change`() {
        board.record(started("a"), 0)
        board.record(delegation(), 1)

        val next = assertNotNull(board.takeNext { it.segment == 1 })
        assertEquals(listOf("delegate"), board[next.first].calls.keys.map { it.second.value })
        assertNull(board.takeNext { it.segment == 1 })
        assertTrue(board.hasPending { true })
    }
}
