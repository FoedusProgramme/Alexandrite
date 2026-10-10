package org.foedusprogramme.alexandrite.agent.worker

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.agent.GatedConversations
import org.foedusprogramme.alexandrite.agent.TestClock
import org.foedusprogramme.alexandrite.agent.logged
import org.foedusprogramme.alexandrite.agent.turn.TurnCancelled
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.TurnId
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.Capacity
import org.foedusprogramme.alexandrite.sdk.turn.RefusalReason
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnPhase
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TurnWorkersTest {
    private fun TestScope.bench(maxConcurrentTurns: Int = 2, perKey: Int = 2) =
        WorkerBench(this, maxConcurrentTurns, perKey)

    private fun WorkerBench.phases(chat: String? = null): List<Pair<TurnId, TurnPhase>> =
        workers.statuses(chat?.let(ChatAddress::parse)).map { it.turn.id to it.phase }

    @Test
    fun `a key runs its turns one at a time in submit order`() = runTest {
        val bench = bench(perKey = 5)
        val turns = List(3) { bench.receive("test:main:1").turn }
        runCurrent()

        assertEquals(turns.take(1), bench.runner.started)
        assertEquals(
            listOf(turns[0] to TurnPhase.RUNNING, turns[1] to TurnPhase.QUEUED, turns[2] to TurnPhase.QUEUED),
            bench.phases(),
        )

        bench.runner.finish(turns[0])
        runCurrent()
        assertEquals(turns.take(2), bench.runner.started)

        bench.runner.finish(turns[1])
        runCurrent()
        assertEquals(turns, bench.runner.started)
    }

    @Test
    fun `keys run in parallel as far as the turn permits go`() = runTest {
        val bench = bench(maxConcurrentTurns = 2)
        val turns = List(3) { bench.receive("test:main:${it + 1}").turn }
        runCurrent()

        assertEquals(turns.take(2).toSet(), bench.runner.started.toSet())
        assertEquals(TurnPhase.QUEUED, bench.phases("test:main:3").single().second)

        bench.runner.finish(turns[1])
        runCurrent()

        assertEquals(turns.toSet(), bench.runner.started.toSet())
        assertEquals(TurnPhase.RUNNING, bench.phases("test:main:3").single().second)
    }

    @Test
    fun `a key's queue takes perKey counted turns while exempt turns always get in`() = runTest {
        val bench = bench(perKey = 2)
        bench.receive("test:main:1")
        runCurrent()
        bench.receive("test:main:1")
        bench.receive("test:main:1")

        assertEquals(Admission.Refused(RefusalReason.QUEUE_FULL, 2), bench.receive("test:main:1"))
        assertTrue(bench.receive("test:main:1", Capacity.EXEMPT) is Admission.Accepted)
        assertEquals(Admission.Refused(RefusalReason.QUEUE_FULL, 2), bench.receive("test:main:1"))
        assertTrue(bench.receive("test:main:2") is Admission.Accepted)
    }

    @Test
    fun `a control job runs in its place among the turns, also when the queue is full`() = runTest {
        val bench = bench(perKey = 1)
        val key = AgentChatKey.parse("coder@test:main:1")
        val first = bench.receive("test:main:1").turn
        runCurrent()
        val ran = mutableListOf<String>()
        val control = WorkerJob.Control { ran += "control after ${bench.runner.started.size} turn(s)" }
        val failing = WorkerJob.Control { error("Broken.") }
        bench.workers.enqueue(key, control)
        bench.workers.enqueue(key, failing)
        val second = bench.receive("test:main:1").turn
        runCurrent()

        assertEquals(emptyList(), ran)
        assertEquals(Admission.Refused(RefusalReason.QUEUE_FULL, 1), bench.receive("test:main:1"))
        bench.runner.finish(first)
        runCurrent()

        assertEquals(listOf("control after 1 turn(s)"), ran)
        assertTrue(control.done.isCompleted)
        assertEquals("Broken.", failing.done.getCompletionExceptionOrNull()?.message)
        assertEquals(listOf(first, second), bench.runner.started)
    }

    @Test
    fun `linked chats share one worker and a cancel stops only the turns that came from its chat`() = runTest {
        val bench = bench()
        val anchor = bench.receive("test:main:7")
        val member = bench.receive("test:main:8")
        runCurrent()

        assertEquals(setOf(AgentChatKey.parse("coder@test:main:7")), bench.workers.keys)
        assertEquals(listOf(anchor.turn to TurnPhase.RUNNING), bench.phases("test:main:7"))
        assertEquals(listOf(member.turn to TurnPhase.QUEUED), bench.phases("test:main:8"))
        assertEquals(ChatAddress.parse("test:main:8"), bench.workers.statuses(null)[1].turn.chat)

        assertFalse(bench.workers.cancelRunning(ChatAddress.parse("test:main:8"), TurnCancelled(null)))
        runCurrent()
        assertFalse(anchor.ticket.ended)

        assertTrue(bench.workers.cancelRunning(ChatAddress.parse("test:main:7"), TurnCancelled(null)))
        runCurrent()

        assertEquals(TurnOutcome.Cancelled, anchor.ticket.outcome())
        assertEquals(listOf(anchor.turn, member.turn), bench.runner.started)
        assertFalse(member.ticket.ended)
    }

    @Test
    fun `a ticket drops its turn while queued and cancels it while it runs`() = runTest {
        val bench = bench()
        val running = bench.receive("test:main:1")
        val handled = bench.receive("test:main:2")
        val queued = bench.receive("test:main:1")
        bench.runner.onCancel[handled.turn] = TurnOutcome.Completed(null)
        runCurrent()

        assertTrue(queued.ticket.cancel())
        assertEquals(TurnOutcome.Cancelled, queued.ticket.outcome())
        assertTrue(running.ticket.cancel())
        assertTrue(handled.ticket.cancel())
        runCurrent()

        assertEquals(TurnOutcome.Cancelled, running.ticket.outcome())
        assertEquals(TurnOutcome.Completed(null), handled.ticket.outcome())
        assertFalse(running.ticket.cancel())
        assertEquals(setOf(running.turn, handled.turn), bench.runner.started.toSet())
    }

    @Test
    fun `a turn waiting for a permit is dropped by its ticket without starting`() = runTest {
        val bench = bench(maxConcurrentTurns = 1)
        val first = bench.receive("test:main:1")
        val waiting = bench.receive("test:main:2")
        runCurrent()

        assertTrue(waiting.ticket.cancel())
        runCurrent()
        bench.runner.finish(first.turn)
        runCurrent()

        assertEquals(TurnOutcome.Cancelled, waiting.ticket.outcome())
        assertEquals(listOf(first.turn), bench.runner.started)
    }

    @Test
    fun `a runner that throws fails its turn and the worker goes on`() = runTest {
        val bench = bench()
        val failing = bench.receive("test:main:1")
        val next = bench.receive("test:main:1")
        runCurrent()

        val lines = logged {
            bench.runner.fail(failing.turn, IllegalStateException("Broken."))
            runCurrent()
        }

        assertEquals(TurnOutcome.Failed("java.lang.IllegalStateException: Broken."), failing.ticket.outcome())
        assertEquals(listOf("ERROR Turn ${failing.turn} of coder@test:main:1 failed"), lines)
        assertEquals(listOf(failing.turn, next.turn), bench.runner.started)
    }

    @Test
    fun `idle workers and intakes are removed`() = runTest {
        val bench = bench()
        val turns = listOf(bench.receive("test:main:1").turn, bench.receive("test:main:2").turn)
        runCurrent()

        assertEquals(2, bench.workers.keys.size)
        turns.forEach { bench.runner.finish(it) }
        runCurrent()

        assertEquals(emptySet(), bench.workers.keys)
        assertEquals(emptySet(), bench.intakes.chats)
    }

    @Test
    fun `turns report the conversation their worker read, and not before it has read one`() = runTest {
        val store = MemoryStore(TestClock())
        val conversations = GatedConversations(store)
        val bench = WorkerBench(this, store = store, conversations = conversations)
        val key = AgentChatKey.parse("coder@test:main:1")
        bench.receive("test:main:1")
        runCurrent()

        assertEquals(emptyList(), bench.workers.statuses(null))
        conversations.open(key)
        runCurrent()

        val status = bench.workers.statuses(null).single()
        assertEquals(store.conversations.current(key).id, status.turn.conversation)
        assertEquals(listOf("coder", "test:main:1"), listOf(status.turn.agent.value, status.turn.chat.toString()))
    }

    @Test
    fun `a drain ends the turns that never started and lets the running ones finish within their grace`() = runTest {
        val bench = bench(maxConcurrentTurns = 1)
        val running = bench.receive("test:main:1")
        val queued = bench.receive("test:main:1")
        val waiting = bench.receive("test:main:2")
        runCurrent()
        bench.intakes.gate(ChatAddress.parse("test:main:3"), TurnId("command"))
        val held = bench.receive("test:main:3")

        val drain = launch {
            bench.intakes.shutDown()
            bench.workers.drain(10.seconds, 3.seconds)
        }
        runCurrent()

        val never = TurnOutcome.ShutDown(replayable = true)
        assertEquals(listOf(never, never, never), listOf(queued, waiting, held).map { it.ticket.outcome() })
        assertEquals(Admission.Refused(RefusalReason.SHUTTING_DOWN), bench.receive("test:main:1"))
        advanceTimeBy(5.seconds)
        bench.runner.finish(running.turn)
        runCurrent()

        assertTrue(drain.isCompleted)
        assertEquals(TurnOutcome.Completed(null), running.ticket.outcome())
        assertEquals(listOf(running.turn), bench.runner.started)
    }

    @Test
    fun `a drain cancels the turns still running after the grace and abandons those that keep running`() = runTest {
        val bench = bench(maxConcurrentTurns = 3)
        val thrown = bench.receive("test:main:1")
        val answered = bench.receive("test:main:2")
        val stubborn = bench.receive("test:main:3")
        bench.runner.onCancel[answered.turn] = TurnOutcome.ShutDown(replayable = true)
        bench.runner.stubborn += stubborn.turn
        runCurrent()

        lateinit var afterGrace: List<Boolean>
        val lines = logged {
            launch { bench.workers.drain(10.seconds, 3.seconds) }
            advanceTimeBy(9.seconds)
            assertEquals(listOf(false, false, false), listOf(thrown, answered, stubborn).map { it.ticket.ended })
            advanceTimeBy(1.seconds + 1.seconds / 2)
            afterGrace = listOf(thrown, answered, stubborn).map { it.ticket.ended }
            advanceTimeBy(3.seconds)
        }
        bench.runner.finish(stubborn.turn)

        assertEquals(listOf(true, true, false), afterGrace)
        assertEquals(TurnOutcome.ShutDown(replayable = false), thrown.ticket.outcome())
        assertEquals(TurnOutcome.ShutDown(replayable = true), answered.ticket.outcome())
        assertEquals(TurnOutcome.ShutDown(replayable = false), stubborn.ticket.outcome())
        assertEquals(
            listOf(
                "WARN Turn ${stubborn.turn} of coder@test:main:3 did not end within 3s of its cancellation: it is " +
                    "abandoned",
            ),
            lines,
        )
    }

    @Test
    fun `control jobs still queued when the drain ends fail`() = runTest {
        val bench = bench()
        val key = AgentChatKey.parse("coder@test:main:1")
        val stubborn = bench.receive("test:main:1")
        bench.runner.stubborn += stubborn.turn
        val control = WorkerJob.Control {}
        bench.workers.enqueue(key, control)
        runCurrent()

        logged {
            launch { bench.workers.drain(1.seconds, 1.seconds) }
            advanceTimeBy(3.seconds)
        }
        bench.runner.finish(stubborn.turn)

        assertTrue(control.done.isCompleted)
        assertEquals("The agent is shutting down.", control.done.getCompletionExceptionOrNull()?.message)
    }

    @Test
    fun `submissions are refused while the workers are closed`() = runTest {
        val bench = bench()
        bench.workers.close()

        assertEquals(Admission.Refused(RefusalReason.SHUTTING_DOWN), bench.receive("test:main:1"))
        bench.workers.open()
        assertTrue(bench.receive("test:main:1") is Admission.Accepted)
    }
}
