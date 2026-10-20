package org.unitedsoftware.alexandrite.agent.worker

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.Capacity
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ChatIntakesTest {
    private val gated = ChatAddress.parse("test:main:1")
    private val command = TurnId("t-command")

    @Test
    fun `a chat of an unknown instance or without an agent is refused at once`() = runTest {
        val bench = WorkerBench(this)

        assertEquals(Admission.Refused(RefusalReason.UNKNOWN_CHAT), bench.receive("test:other:1"))
        assertEquals(Admission.Refused(RefusalReason.NO_AGENT), bench.receive("test:quiet:1"))
        assertEquals(emptySet(), bench.intakes.chats)
    }

    @Test
    fun `a gate holds the chat's messages until it is released, then hands them on in order`() = runTest {
        val bench = WorkerBench(this, perKey = 5)
        bench.intakes.gate(gated, command)
        val held = List(3) { bench.receive("test:main:1").turn }
        val other = bench.receive("test:main:2").turn
        runCurrent()

        assertEquals(listOf(other), bench.runner.started)
        assertEquals(setOf(gated), bench.intakes.chats)
        bench.intakes.release(gated, command)
        runCurrent()

        assertEquals(listOf(other, held[0]), bench.runner.started)
        held.forEach { bench.runner.finish(it) }
        runCurrent()
        assertEquals(listOf(other) + held, bench.runner.started)
        assertEquals(emptySet(), bench.intakes.chats)
    }

    @Test
    fun `held messages count against the queue of the key their chat goes to`() = runTest {
        val bench = WorkerBench(this, perKey = 2)
        bench.receive("test:main:1")
        runCurrent()
        bench.receive("test:main:1")
        bench.intakes.gate(gated, command)

        assertTrue(bench.receive("test:main:1") is Admission.Accepted)
        assertEquals(Admission.Refused(RefusalReason.QUEUE_FULL, 2), bench.receive("test:main:1"))
        assertTrue(bench.receive("test:main:1", Capacity.EXEMPT) is Admission.Accepted)
        bench.intakes.release(gated, command)
        assertEquals(Admission.Refused(RefusalReason.QUEUE_FULL, 2), bench.receive("test:main:1"))
    }

    @Test
    fun `a chat's messages go at once to the agent it switched to`() = runTest {
        val bench = WorkerBench(this)
        bench.select("test:solo:3", "coder")

        val turn = bench.receive("test:solo:3").turn
        runCurrent()

        assertEquals(listOf(turn), bench.runner.started)
        assertEquals(setOf(AgentChatKey.parse("coder@test:solo:3")), bench.workers.keys)
        assertEquals(emptySet(), bench.intakes.chats)
    }

    @Test
    fun `a held message is dropped by its ticket`() = runTest {
        val bench = WorkerBench(this)
        bench.intakes.gate(gated, command)
        val dropped = bench.receive("test:main:1")
        val kept = bench.receive("test:main:1")

        assertTrue(dropped.ticket.cancel())
        assertFalse(dropped.ticket.cancel())
        bench.intakes.release(gated, command)
        runCurrent()

        assertEquals(TurnOutcome.Cancelled, dropped.ticket.outcome())
        assertEquals(listOf(kept.turn), bench.runner.started)
    }
}
