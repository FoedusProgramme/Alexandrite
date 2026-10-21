package org.unitedsoftware.alexandrite.agent.worker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.agent.TestClock
import org.unitedsoftware.alexandrite.agent.cancel.TurnCancelled
import org.unitedsoftware.alexandrite.agent.logged
import org.unitedsoftware.alexandrite.agent.turn.TurnPlan
import org.unitedsoftware.alexandrite.agent.turn.TurnSource
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.ReplyTarget
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.model.Trust
import org.unitedsoftware.alexandrite.sdk.store.ConversationInfo
import org.unitedsoftware.alexandrite.sdk.store.ConversationStore
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.Capacity
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.sdk.turn.TurnPhase
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class TurnWorkersTest {
    private fun TestScope.bench(maxConcurrentTurns: Int = 2, perKey: Int = 2) =
        WorkerBench(this, maxConcurrentTurns, perKey)

    private suspend fun WorkerBench.phases(chat: String? = null): List<Pair<TurnId, TurnPhase>> =
        workers.statuses(chat?.let(ChatAddress::parse)).map { it.turn.id to it.phase }

    private fun WorkerBench.heartbeat(key: AgentChatKey): TurnId {
        val id = newTurnId(clock.instant())
        val input = TurnSource.FromInitiated("beat", "Beat.", Trust.UNTRUSTED)
        val plan =
            TurnPlan(id, key, key.chat, TurnKind.HEARTBEAT, input, null, ReplyTarget.CALLER, null, clock.instant())
        return workers.submit(key, WorkerJob.Turn(plan, AgentTicket(id) { false }, Capacity.COUNTED)).turn
    }

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
    fun `a control job runs in its place among the turns, also when the queue is full`() = runTest {
        val bench = bench(perKey = 1)
        val key = AgentChatKey.parse("coder@test:main:1")
        val first = bench.receive("test:main:1").turn
        runCurrent()
        val ran = mutableListOf<String>()
        val control = WorkerJob.Control { ran += "control after ${bench.runner.started.size} turn(s)" }
        val failing = WorkerJob.Control { throw NotImplementedError("Broken.") }
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
    fun `linked chats share one worker and a cancel stops only its agent's turns that came from its chat`() = runTest {
        val bench = bench()
        val anchor = bench.receive("test:main:7")
        val member = bench.receive("test:main:8")
        runCurrent()

        assertEquals(setOf(AgentChatKey.parse("coder@test:main:7")), bench.workers.keys)
        assertEquals(listOf(anchor.turn to TurnPhase.RUNNING), bench.phases("test:main:7"))
        assertEquals(listOf(member.turn to TurnPhase.QUEUED), bench.phases("test:main:8"))
        assertEquals(ChatAddress.parse("test:main:8"), bench.workers.statuses(null)[1].turn.chat)

        assertFalse(bench.workers.cancelRunning(ChatAddress.parse("test:main:8"), CODER, TurnCancelled(null)))
        val other = AgentId("other")
        assertFalse(bench.workers.cancelRunning(ChatAddress.parse("test:main:7"), other, TurnCancelled(null)))
        runCurrent()
        assertFalse(anchor.ticket.ended)

        assertTrue(bench.workers.cancelRunning(ChatAddress.parse("test:main:7"), CODER, TurnCancelled(null)))
        runCurrent()

        assertEquals(TurnOutcome.Cancelled, anchor.ticket.outcome())
        assertEquals(listOf(anchor.turn, member.turn), bench.runner.started)
        assertFalse(member.ticket.ended)
    }

    @Test
    fun `a running turn takes its principal's messages queued since it started, up to a change of state`() = runTest {
        val bench = bench(perKey = 10)
        val key = AgentChatKey.parse("coder@test:main:1")
        val guest = UserAddress.parse("test:main@guest")
        val running = bench.receive("test:main:1").turn
        runCurrent()
        val early = bench.receive("test:main:1")
        bench.clock.now = bench.clock.now.plusSeconds(1)
        val since = bench.clock.instant()
        val other = bench.receive("test:main:1", user = "other")
        val late = bench.receive("test:main:1")
        val later = bench.receive("test:main:1")
        bench.workers.enqueue(key, WorkerJob.Control {})
        val behind = bench.receive("test:main:1")
        val followUps = bench.runner.followUps.getValue(running)

        val chat = ChatAddress.parse("test:main:1")
        val taken = followUps.take(guest, chat, since)

        assertEquals(listOf(late.turn, later.turn), taken.map { it.plan.id })
        assertEquals(emptyList(), followUps.take(guest, chat, since))
        assertEquals(listOf(running, early.turn, other.turn, behind.turn), bench.phases().map { it.first })
        taken.first().complete(TurnOutcome.Absorbed(running))
        followUps.restore(taken.drop(1))
        bench.runner.finish(running)
        runCurrent()

        assertEquals(listOf(running, later.turn), bench.runner.started)
        assertEquals(TurnOutcome.Absorbed(running), late.ticket.outcome())
    }

    @Test
    fun `a running turn learns of a follow-up of its principal once one is queued`() = runTest {
        val bench = bench(perKey = 10)
        val guest = UserAddress.parse("test:main@guest")
        val chat = ChatAddress.parse("test:main:1")
        val running = bench.receive("test:main:1").turn
        runCurrent()
        val followUps = bench.runner.followUps.getValue(running)
        val since = bench.clock.instant()
        var arrived = false

        launch {
            followUps.await(guest, chat, since)
            arrived = true
        }
        bench.receive("test:main:1", user = "other")
        runCurrent()
        val before = arrived to followUps.queued(guest, chat, since)
        bench.receive("test:main:1")
        runCurrent()

        assertEquals(false to false, before)
        assertEquals(true to true, arrived to followUps.queued(guest, chat, since))
    }

    @Test
    fun `a turn that gives up its permit lets another key's turn run, and waits for it again before it goes on`() =
        runTest {
            val bench = bench(maxConcurrentTurns = 1, perKey = 5)
            val waiting = bench.receive("test:main:1").turn
            val other = bench.receive("test:main:2").turn
            runCurrent()
            val answer = CompletableDeferred<Unit>()
            var resumed = false

            launch {
                bench.runner.permits.getValue(waiting).released { answer.await() }
                resumed = true
            }
            runCurrent()
            val meanwhile = bench.runner.started.toList()
            answer.complete(Unit)
            runCurrent()
            val beforeOtherEnds = resumed
            bench.runner.finish(other)
            runCurrent()

            assertEquals(listOf(waiting, other), meanwhile)
            assertEquals(false to true, beforeOtherEnds to resumed)
            val third = bench.receive("test:main:3").turn
            runCurrent()
            assertEquals(listOf(waiting, other), bench.runner.started)
            bench.runner.finish(waiting)
            runCurrent()
            assertEquals(listOf(waiting, other, third), bench.runner.started)
        }

    @Test
    fun `a wait without the permit that is cancelled does not take the permit back`() = runTest {
        val bench = bench(maxConcurrentTurns = 1, perKey = 5)
        val waiting = bench.receive("test:main:1").turn
        runCurrent()
        val wait = launch { bench.runner.permits.getValue(waiting).released { awaitCancellation() } }
        val other = bench.receive("test:main:2").turn
        runCurrent()

        wait.cancel()
        bench.runner.finish(waiting)
        val third = bench.receive("test:main:3").turn
        runCurrent()

        assertEquals(listOf(waiting, other), bench.runner.started)
        bench.runner.finish(other)
        runCurrent()
        assertEquals(listOf(waiting, other, third), bench.runner.started)
    }

    @Test
    fun `follow-ups put back once the workers closed end as never started`() = runTest {
        val bench = bench(perKey = 10)
        val running = bench.receive("test:main:1").turn
        runCurrent()
        val followUp = bench.receive("test:main:1")
        val followUps = bench.runner.followUps.getValue(running)
        val taken =
            followUps.take(
                UserAddress.parse("test:main@guest"),
                ChatAddress.parse("test:main:1"),
                bench.clock.instant(),
            )

        bench.workers.close()
        followUps.restore(taken)

        assertEquals(TurnOutcome.ShutDown(replayable = true), followUp.ticket.outcome())
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
    fun `a turn whose outcome is decided is listed as finishing and no longer cancelled while it wraps up`() = runTest {
        val bench = bench()
        val decided = bench.receive("test:main:1")
        val running = bench.receive("test:main:2")
        runCurrent()
        assertEquals(listOf(decided.turn to TurnPhase.RUNNING), bench.phases("test:main:1"))
        bench.runner.decide(decided.turn)

        assertEquals(listOf(decided.turn to TurnPhase.FINISHING), bench.phases("test:main:1"))
        assertEquals(listOf(running.turn to TurnPhase.RUNNING), bench.phases("test:main:2"))
        assertFalse(bench.workers.cancelRunning(ChatAddress.parse("test:main:1"), CODER, TurnCancelled(null)))
        assertFalse(bench.workers.cancel(decided.turn, TurnCancelled(null)))
        assertFalse(decided.ticket.cancel())
        assertTrue(bench.workers.cancelRunning(ChatAddress.parse("test:main:2"), CODER, TurnCancelled(null)))
        bench.runner.finish(decided.turn)
        runCurrent()

        assertEquals(TurnOutcome.Completed(null), decided.ticket.outcome())
        assertEquals(TurnOutcome.Cancelled, running.ticket.outcome())
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
        assertEquals(1, lines.count { it.startsWith("ERROR") }, "$lines")
        assertEquals(listOf(failing.turn, next.turn), bench.runner.started)
    }

    @Test
    fun `a turn whose conversation cannot be read is left out of the statuses, and it runs`() = runTest {
        val store = MemoryStore(TestClock())
        val broken = object : ConversationStore by store.conversations {
            override suspend fun current(key: AgentChatKey): ConversationInfo = TODO("Not yet.")
        }
        val bench = WorkerBench(this, store = store, conversations = broken)

        val turn = bench.receive("test:main:1")
        runCurrent()

        assertEquals(emptyList(), bench.workers.statuses(null))
        assertEquals(listOf(turn.turn), bench.runner.started)
    }

    @Test
    fun `a worker whose loop dies is removed and ends what it held, and the key gets a new worker`() = runTest {
        val scope =
            CoroutineScope(backgroundScope.coroutineContext + SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
        val bench = WorkerBench(this, scope = scope)
        val key = AgentChatKey.parse("coder@test:main:1")
        val control = WorkerJob.Control {}
        bench.workers.enqueue(key, WorkerJob.Control { throw FatalError() })
        val queued = bench.receive("test:main:1")
        bench.workers.enqueue(key, control)

        logged { runCurrent() }

        assertTrue(queued.ticket.outcome() is TurnOutcome.Failed)
        assertTrue(control.done.getCompletionExceptionOrNull() is FatalError)
        assertEquals(emptySet(), bench.workers.keys)
        val next = bench.receive("test:main:1")
        runCurrent()
        assertEquals(listOf(next.turn), bench.runner.started)
    }

    @Test
    fun `a key that runs only heartbeats gets no conversation of its users`() = runTest {
        val bench = bench(perKey = 3)
        val key = AgentChatKey.parse("coder@test:main:1")
        val beats = List(2) { bench.heartbeat(key) }
        runCurrent()
        beats.forEach {
            bench.runner.finish(it)
            runCurrent()
        }

        assertEquals(beats, bench.runner.started)
        assertEquals(emptyList(), bench.store.conversations.chats(AgentId("coder")))
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
    fun `turns show the conversation their runner read, and before then the one their key holds`() = runTest {
        val bench = bench(perKey = 3)
        val key = AgentChatKey.parse("coder@test:main:1")
        val running = bench.receive("test:main:1").turn
        val queued = bench.receive("test:main:1").turn

        val before = bench.workers.statuses(null).map { it.turn.id to it.turn.conversation }
        runCurrent()
        val read = ConversationId("read-by-the-runner")
        bench.runner.resolve(running, read)

        val current = bench.store.conversations.current(key).id
        assertEquals(listOf(running to current, queued to current), before)
        val status = bench.workers.statuses(null).first()
        assertEquals(listOf("coder", "test:main:1"), listOf(status.turn.agent.value, status.turn.chat.toString()))
        assertEquals(
            listOf(running to read, queued to read),
            bench.workers.statuses(null).map { it.turn.id to it.turn.conversation },
        )
    }

    @Test
    fun `a message queued behind a running heartbeat is listed, as the heartbeats are`() = runTest {
        val bench = bench(perKey = 3)
        val key = AgentChatKey.parse("coder@test:main:1")
        val running = bench.heartbeat(key)
        runCurrent()
        val queued = bench.heartbeat(key)
        val message = bench.receive("test:main:1").turn
        runCurrent()

        val base = bench.store.conversations.heartbeatBase(key).id
        val current = bench.store.conversations.current(key).id
        assertEquals(
            listOf(running to base, queued to base, message to current),
            bench.workers.statuses(null).map { it.turn.id to it.turn.conversation },
        )
        assertTrue(base != current)
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
        assertTrue(lines.single().let { it.startsWith("WARN") && "${stubborn.turn}" in it }, "$lines")
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
    fun `once the workers closed, a turn that a running one leaves queued ends as never started`() = runTest {
        val bench = bench(perKey = 5)
        val key = AgentChatKey.parse("coder@test:main:1")
        val running = bench.receive("test:main:1")
        val queued = bench.receive("test:main:1")
        val control = WorkerJob.Control {}
        runCurrent()
        bench.workers.enqueue(key, control)

        bench.workers.close()
        bench.runner.finish(running.turn)
        runCurrent()

        assertEquals(TurnOutcome.ShutDown(replayable = true), queued.ticket.outcome())
        assertEquals(listOf(running.turn), bench.runner.started)
        assertTrue(control.done.isCompleted)
        assertEquals(emptySet(), bench.workers.keys)
    }
}

/** An error that the barriers let through. */
private class FatalError : VirtualMachineError("Fatal.")

private val CODER = AgentId("coder")
