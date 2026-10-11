package org.foedusprogramme.alexandrite.agent.control

import kotlinx.coroutines.delay
import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.agent.logged
import org.foedusprogramme.alexandrite.agent.worker.AgentTicket
import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.Capacity
import org.foedusprogramme.alexandrite.sdk.turn.RefusalReason
import org.foedusprogramme.alexandrite.sdk.turn.Submission
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnPhase
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.RecordingChannel
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class CommandLanesTest {
    private val model = ScriptedModel()
    private val commands = TestCommands()

    private fun harness(perChat: Int = 10, running: Int = 4, gateSeconds: Int = 30): PluginHarness = agentHarness(
        """
        {
          "queue": {"commandsPerChat": $perChat, "commandsRunning": $running, "commandGateSeconds": $gateSeconds},
          "shutdown": {"turnGraceSeconds": 0, "cancelJoinSeconds": 2},
          "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}
        }
        """.trimIndent(),
        MemoryStore(),
    ) { channel().model(model).commandHandler(commands) }

    private val Admission.accepted: Admission.Accepted get() = this as Admission.Accepted

    private val Admission.Accepted.ended: Boolean get() = (ticket as AgentTicket).ended

    private suspend fun awaitStarted(count: Int) {
        while (commands.started.size < count) delay(5)
    }

    @Test
    fun `a lane admits commandsPerChat counted invocations and runs commandsRunning at once, in submit order`() {
        harness(perChat = 2, running = 1).execute {
            commands.control = get()
            val channel = channel()
            val first = channel.receiveCommand("wait", "a").accepted
            val second = channel.receiveCommand("wait", "b").accepted
            val full = channel.receiveCommand("wait", "c")
            val exempt = channel.submit(Submission.Command(channel.command("wait", "d"), capacity = Capacity.EXEMPT))
            val elsewhere = channel.receiveCommand("wait", "e", channel.chat("other")).accepted
            awaitStarted(2)
            delay(50)

            assertEquals(Admission.Refused(RefusalReason.QUEUE_FULL, 2), full)
            assertTrue(exempt is Admission.Accepted)
            assertEquals(setOf("wait a", "wait e"), commands.started.toSet())
            val running = commands.control.turns(channel.chat()).single()
            assertEquals(
                Triple(first.ticket.turn, TurnKind.COMMAND, TurnPhase.RUNNING),
                Triple(running.turn.id, running.turn.kind, running.phase),
            )
            assertEquals(channel.user(), running.turn.actor)
            assertTrue(second.ticket.cancel())
            commands.gate("a").complete(Unit)
            awaitStarted(3)

            assertEquals(TurnOutcome.Completed(null), first.ticket.outcome())
            assertEquals(TurnOutcome.Cancelled, second.ticket.outcome())
            assertEquals(setOf("wait a", "wait e", "wait d"), commands.started.toSet())
            listOf("d", "e").forEach { commands.gate(it).complete(Unit) }
            assertEquals(TurnOutcome.Completed(null), elsewhere.ticket.outcome())
        }
    }

    @Test
    fun `a chat's commands run one after another in the order they came, and one that runs alongside at once`() {
        harness().execute {
            commands.control = get()
            val channel = channel()
            val first = channel.receiveCommand("wait", "a").accepted
            val second = channel.receiveCommand("wait", "b").accepted
            val cancel = channel.receiveCommand("cancel").accepted
            val elsewhere = channel.receiveCommand("wait", "c", channel.chat("other")).accepted
            awaitStarted(3)
            delay(50)

            assertEquals(setOf("wait a", "cancel", "wait c"), commands.started.toSet())
            assertTrue(cancel.ticket.outcome() is TurnOutcome.Completed)
            commands.gate("a").complete(Unit)
            awaitStarted(4)

            assertEquals("wait b", commands.started.last())
            listOf("b", "c").forEach { commands.gate(it).complete(Unit) }
            assertEquals(
                List(3) { TurnOutcome.Completed(null) },
                listOf(first, second, elsewhere).map { it.ticket.outcome() },
            )
        }
    }

    @Test
    fun `a message waits for the command sent before it, and goes on after commandGateSeconds`() {
        model.reply { text("One.") }.reply { text("Two.") }

        val lines = logged {
            harness(gateSeconds = 1).execute {
                commands.control = get()
                val channel = channel()
                val first = channel.receiveCommand("wait", "a").accepted
                val held = channel.receive("Hi").accepted
                delay(200)

                assertFalse(held.ended)
                assertEquals(emptyList(), model.requests)
                commands.gate("a").complete(Unit)
                assertTrue(first.ticket.outcome() is TurnOutcome.Completed)
                assertTrue(held.ticket.outcome() is TurnOutcome.Completed)

                val stuck = channel.receiveCommand("wait", "b").accepted
                val start = TimeSource.Monotonic.markNow()
                val released = channel.receive("Again").accepted
                assertTrue(released.ticket.outcome() is TurnOutcome.Completed)

                assertTrue(start.elapsedNow() >= 900.milliseconds)
                assertFalse(stuck.ended)
                commands.gate("b").complete(Unit)
                stuck.ticket.outcome()
            }
        }

        assertEquals(1, lines.count { it.startsWith("WARN Command ") && "they go on" in it })
    }

    @Test
    fun `a handler that throws gets a failure notice and fails its invocation, and the chat goes on`() {
        model.reply { text("Still here.") }

        val lines = logged {
            harness().execute {
                commands.control = get()
                val channel = channel()
                val boom = channel.receiveCommand("boom").accepted
                val after = channel.receive("Hi").accepted

                assertEquals(TurnOutcome.Failed("java.lang.IllegalStateException: Bang."), boom.ticket.outcome())
                assertTrue(after.ticket.outcome() is TurnOutcome.Completed)
                val sent = channel.sent.single()
                val received = channel.events.filterIsInstance<RecordingChannel.Event.Received>().first()
                val trigger = (received.submission as Submission.Command).invocation.trigger
                assertEquals("The command /boom failed." to MessageKind.NOTICE, sent.message.text to sent.message.kind)
                assertEquals(channel.chat() to trigger, sent.chat to sent.message.replyTo)
            }
        }

        assertTrue(lines.any { it.startsWith("ERROR Command /boom") })
    }
}
