package org.unitedsoftware.alexandrite.agent.control

import kotlinx.coroutines.delay
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.logged
import org.unitedsoftware.alexandrite.agent.worker.AgentTicket
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.Capacity
import org.unitedsoftware.alexandrite.sdk.turn.RefusalReason
import org.unitedsoftware.alexandrite.sdk.turn.Submission
import org.unitedsoftware.alexandrite.sdk.turn.TurnControl
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.sdk.turn.TurnPhase
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RecordingChannel
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class CommandLanesTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val commands = TestCommands()

    private fun harness(perChat: Int = 10, gateSeconds: Int = 30, operators: Set<String> = emptySet()): PluginHarness =
        agentHarness(
            """
        {
          "queue": {"commandsPerChat": $perChat, "commandGateSeconds": $gateSeconds},
          "shutdown": {"turnGraceSeconds": 0, "cancelJoinSeconds": 2},
          "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}
        }
            """.trimIndent(),
            store,
        ) { channel(operators = operators).model(model).commandHandler(commands) }

    private val Admission.accepted: Admission.Accepted get() = this as Admission.Accepted

    private val Admission.Accepted.ended: Boolean get() = (ticket as AgentTicket).ended

    private suspend fun awaitStarted(count: Int) {
        while (commands.started.size < count) delay(5)
    }

    @Test
    fun `a lane admits commandsPerChat counted invocations and runs them one at a time, in submit order`() {
        harness(perChat = 2).execute {
            commands.connect(get(), get(), get())
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
            val running = get<TurnControl>().turns(channel.chat()).single()
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
            commands.connect(get(), get(), get())
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
    fun `a message waits for each command sent before it, whose gate times out only once it runs`() {
        model.reply {
            text("Old")
            hang()
        }.reply { text("New.") }

        harness(gateSeconds = 1).execute {
            commands.connect(get(), get(), get())
            val channel = channel()
            val running = channel.receive("Hi").accepted
            model.awaitRequests(1)
            val news = List(2) { channel.receiveCommand("new").accepted }
            val held = channel.receive("Again").accepted
            delay(1_500)
            assertTrue(running.ticket.cancel())

            news.forEach { assertTrue(it.ticket.outcome() is TurnOutcome.Completed) }
            assertTrue(held.ticket.outcome() is TurnOutcome.Completed)
            val current = store.conversations.current(AgentChatKey(AgentId("coder"), channel.chat())).id
            assertEquals(current, channel.reply(held.ticket.turn).awaitEnd().turn.conversation)
        }
    }

    @Test
    fun `a message waits for the command sent before it, and goes on after commandGateSeconds`() {
        model.reply { text("One.") }.reply { text("Two.") }

        val lines = logged {
            harness(gateSeconds = 1).execute {
                commands.connect(get(), get(), get())
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
    fun `commands queued behind one that runs commandGateSeconds off a worker hold no messages until it ends`() {
        model.reply { text("One.") }.reply { text("Two.") }.reply { text("Three.") }

        val lines = logged {
            harness(gateSeconds = 1).execute {
                commands.connect(get(), get(), get())
                val channel = channel()
                val hung = channel.receiveCommand("wait", "a").accepted
                val queued = channel.receiveCommand("wait", "b").accepted
                awaitStarted(1)
                val held = channel.receive("Hi").accepted
                assertTrue(held.ticket.outcome() is TurnOutcome.Completed)
                val later = channel.receiveCommand("wait", "c").accepted
                val passing = channel.receive("Again").accepted
                assertTrue(passing.ticket.outcome() is TurnOutcome.Completed)

                assertEquals(listOf("wait a"), commands.started.toList())
                commands.gate("a").complete(Unit)
                awaitStarted(2)
                val waiting = channel.receive("Third").accepted
                delay(200)
                assertFalse(waiting.ended)
                commands.gate("b").complete(Unit)
                awaitStarted(3)
                delay(200)
                assertFalse(waiting.ended)
                commands.gate("c").complete(Unit)

                assertTrue(waiting.ticket.outcome() is TurnOutcome.Completed)
                listOf(hung, queued, later).forEach { assertTrue(it.ticket.outcome() is TurnOutcome.Completed) }
            }
        }

        assertEquals(1, lines.count { it.startsWith("WARN Command ") && "queued behind it" in it })
        assertEquals(1, lines.count { it.startsWith("WARN Command ") && "waits in" in it })
    }

    @Test
    fun `a handler that throws gets a failure notice and fails its invocation, and the chat goes on`() {
        model.reply { text("Still here.") }

        val lines = logged {
            harness().execute {
                commands.connect(get(), get(), get())
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

    @Test
    fun `a command's replies are vouched for when the agent trusts its issuer, else not`() {
        harness(operators = setOf("operator")).execute {
            commands.connect(get(), get(), get())
            val channel = channel()
            channel.receiveCommand("cancel", issuer = channel.user("operator")).accepted.ticket.outcome()
            channel.receiveCommand("cancel").accepted.ticket.outcome()

            assertEquals(
                listOf("Nothing runs." to true, "Nothing runs." to false),
                channel.sent.map { it.message.text to it.message.vouched },
            )
        }
    }
}
