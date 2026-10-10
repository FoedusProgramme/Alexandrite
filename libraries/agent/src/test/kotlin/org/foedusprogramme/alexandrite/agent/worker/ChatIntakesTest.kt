package org.foedusprogramme.alexandrite.agent.worker

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.agent.logged
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.Capacity
import org.foedusprogramme.alexandrite.sdk.turn.RefusalReason
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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
    fun `a gate nobody releases lets the messages go on after commandGateSeconds`() = runTest {
        val bench = WorkerBench(this)
        bench.intakes.gate(gated, command)
        val held = bench.receive("test:main:1").turn

        val lines = logged {
            advanceTimeBy(29.seconds)
            runCurrent()
            assertEquals(emptyList(), bench.runner.started)
            advanceTimeBy(2.seconds)
            runCurrent()
        }

        assertEquals(listOf(held), bench.runner.started)
        assertEquals(listOf("WARN Command t-command held the messages of test:main:1 for 30s: they go on"), lines)
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
    fun `a chat whose switch is not read yet holds its messages and hands them on in order once it is`() = runTest {
        val bench = WorkerBench(this, perKey = 2)
        bench.select("test:solo:3", "coder")
        bench.reads.hold()
        val first = bench.receive("test:solo:3").turn
        val second = bench.receive("test:solo:3").turn
        runCurrent()

        assertEquals(Admission.Refused(RefusalReason.QUEUE_FULL, 2), bench.receive("test:solo:3"))
        assertEquals(emptyList(), bench.runner.started)
        assertEquals(setOf(ChatAddress.parse("test:solo:3")), bench.intakes.chats)
        bench.reads.release()
        runCurrent()
        val third = bench.receive("test:solo:3").turn

        assertEquals(emptySet(), bench.intakes.chats)
        assertEquals(listOf(first), bench.runner.started)
        assertEquals(setOf(AgentChatKey.parse("coder@test:solo:3")), bench.workers.keys)
        bench.runner.finish(first)
        bench.runner.finish(second)
        runCurrent()
        assertEquals(listOf(first, second, third), bench.runner.started)
    }

    @Test
    fun `the messages held for a switch that cannot be read fail`() = runTest {
        val bench = WorkerBench(this)
        bench.reads.hold()
        val held = bench.receive("test:solo:3")
        bench.reads.failure = IllegalStateException("Disk gone.")

        val lines = logged {
            bench.reads.release()
            runCurrent()
        }

        assertEquals(
            TurnOutcome.Failed(
                "Cannot read which agent serves test:solo:3: java.lang.IllegalStateException: Disk gone.",
            ),
            held.ticket.outcome(),
        )
        assertEquals(
            listOf(
                "WARN Cannot read which agent chat test:solo:3 switched to: " +
                    "java.lang.IllegalStateException: Disk gone.",
            ),
            lines,
        )
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
