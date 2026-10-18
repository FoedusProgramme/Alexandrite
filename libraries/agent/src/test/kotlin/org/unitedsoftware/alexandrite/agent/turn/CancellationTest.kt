package org.unitedsoftware.alexandrite.agent.turn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.logged
import org.unitedsoftware.alexandrite.agent.worker.TurnWorkers
import org.unitedsoftware.alexandrite.sdk.channel.ReplyEnd
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.hook.HookDecision
import org.unitedsoftware.alexandrite.sdk.hook.InterceptorHook
import org.unitedsoftware.alexandrite.sdk.store.TurnEndKind
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.ReplyDraft
import org.unitedsoftware.alexandrite.sdk.turn.TurnInput
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.sdk.turn.TurnPoints
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RecordedReply.Preview
import org.unitedsoftware.alexandrite.testkit.RecordingChannel
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class CancellationTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val read = recordingTool("fs.read")
    private val release = CompletableDeferred<Unit>()

    /** A tool that runs on through its cancellation until [release]. */
    private val stuck = recordingTool("fs.stuck") { _, _ ->
        withContext(NonCancellable) { release.await() }
        ToolResult("Late.")
    }

    private fun harness(
        grace: Int = 0,
        join: Int = 2,
        configure: PluginHarness.Builder.() -> Unit = {},
    ): PluginHarness = agentHarness(
        """
        {
          "previewIntervalMillis": 0,
          "shutdown": {"turnGraceSeconds": $grace, "cancelJoinSeconds": $join},
          "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}
        }
        """.trimIndent(),
        store,
    ) { channel(operators = setOf("operator")).model(model).tool(read).tool(stuck).apply(configure) }

    private fun PluginHarness.Running.ask(text: String = "Hi"): Admission.Accepted =
        channel().receive(text, sender = channel().user("operator")) as Admission.Accepted

    private suspend fun entries(turn: TurnId): List<TranscriptEntry> = store.transcripts.turnEntries(turn)

    private suspend fun end(turn: TurnId): TurnEndKind? = store.conversations.turn(turn)?.end

    private fun words(entry: TranscriptEntry): String? = ((entry as? UserEntry)?.parts?.last() as? TextPart)?.text

    private fun abandoned(lines: List<String>): List<String> = lines.filter { it.contains("it is abandoned") }

    // Cancel.

    @Test
    fun `a cancel while the answer streams abandons the shown reply without a notice and stores nothing`() {
        model.reply {
            text("Hel")
            hang()
        }
        val committed = Observer(TurnPoints.TURN_COMMITTED)

        harness { hook(committed) }.execute {
            val admission = ask()
            channel().awaitEvent { it is RecordingChannel.Event.Previewed }

            assertTrue(admission.ticket.cancel())
            assertEquals(TurnOutcome.Cancelled, admission.ticket.outcome())
            val turn = admission.ticket.turn
            val reply = channel().reply(turn)
            assertEquals(ReplyEnd.CANCELLED to listOf(Preview(0, "Hel")), reply.abandoned to reply.previews)
            assertEquals(emptyList(), channel().sent)
            assertEquals(emptyList(), entries(turn))
            assertEquals(TurnEndKind.CANCELLED, end(turn))
            assertEquals(listOf(TurnOutcome.Cancelled to emptyList()), committed.seen.map { it.outcome to it.entries })
        }
    }

    @Test
    fun `a cancel while tools run answers the stored calls as cancelled and abandons a stuck tool after 5 s`() {
        read.hold()
        model.reply {
            toolCall("fs.read")
            toolCall("fs.stuck")
        }
        lateinit var admission: Admission.Accepted

        val lines = logged {
            harness().execute {
                admission = ask()
                val turn = admission.ticket.turn
                read.awaitCalls(1)
                stuck.awaitCalls(1)
                val cancelled = TimeSource.Monotonic.markNow()

                assertTrue(admission.ticket.cancel())
                assertEquals(TurnOutcome.Cancelled, admission.ticket.outcome())
                assertTrue(cancelled.elapsedNow() >= 5.seconds)
                assertEquals(ReplyEnd.CANCELLED, channel().reply(turn).abandoned)
                assertEquals(TurnEndKind.CANCELLED, end(turn))
                release.complete(Unit)
                delay(100)
            }
        }

        val turn = admission.ticket.turn
        val results = blocking { entries(turn) }.filterIsInstance<ToolResultEntry>()
        assertEquals(
            listOf("fs.read", "fs.stuck").map { it to (ToolOutcome.Cancelled to "Cancelled (outcome unknown)") },
            results.map { it.toolName to (it.outcome to (it.content.single() as TextPart).text) },
        )
        assertEquals(1, read.cancelled.size)
        val warning = abandoned(lines).single()
        assertTrue(warning.startsWith("WARN Tool fs.stuck still runs 5s after its call "), warning)
        assertTrue(warning.endsWith(" in turn $turn was cancelled: it is abandoned and its result discarded"), warning)
    }

    @Test
    fun `a cancel that lands while the final answer is delivered lets it reach the chat`() {
        val delivering = CompletableDeferred<Unit>()
        val go = CompletableDeferred<Unit>()
        val before = object : InterceptorHook<ReplyDraft> {
            override val point = TurnPoints.RESPONSE_BEFORE

            override suspend fun intercept(payload: ReplyDraft): HookDecision<ReplyDraft> {
                delivering.complete(Unit)
                go.await()
                return HookDecision.Continue
            }
        }
        model.reply { text("The answer.") }

        harness { hook(before) }.execute {
            val admission = ask()
            delivering.await()

            assertTrue(admission.ticket.cancel())
            go.complete(Unit)
            val outcome = admission.ticket.outcome()

            val turn = admission.ticket.turn
            assertIs<TurnOutcome.Completed>(outcome)
            assertEquals<TranscriptEntry?>(entries(turn).last(), outcome.reply)
            assertEquals("The answer.", channel().reply(turn).completed?.text)
            assertEquals(TurnEndKind.COMPLETED, end(turn))
        }
    }

    @Test
    fun `a message that a cancelled turn took and did not absorb runs as a turn of its own`() {
        read.hold()
        val absorbing = CompletableDeferred<Unit>()
        val input = object : InterceptorHook<TurnInput> {
            override val point = TurnPoints.TURN_INPUT

            override suspend fun intercept(payload: TurnInput): HookDecision<TurnInput> {
                if (payload.followUp) {
                    absorbing.complete(Unit)
                    awaitCancellation()
                }
                return HookDecision.Continue
            }
        }
        model.reply { toolCall("fs.read") }.reply { text("Second answer.") }

        harness { hook(input) }.execute {
            val first = ask("First.")
            read.awaitCalls(1)
            val second = ask("Second.")
            read.release()
            absorbing.await()

            assertTrue(first.ticket.cancel())
            assertEquals(TurnOutcome.Cancelled, first.ticket.outcome())
            assertIs<TurnOutcome.Completed>(second.ticket.outcome())
            assertEquals("Second answer.", channel().reply(second.ticket.turn).completed?.text)
            assertEquals(listOf("First.", null, null), entries(first.ticket.turn).map(::words))
            assertEquals(listOf("Second.", null), entries(second.ticket.turn).map(::words))
        }
    }

    // Shutdown.

    @Test
    fun `a turn that ends within the grace still delivers its answer through the closed channel`() {
        lateinit var workers: TurnWorkers
        model.reply {
            text("Started before the stop.")
            pause { while (workers.accepting) delay(10) }
            text("Finished after it.")
        }
        lateinit var admission: Admission.Accepted
        lateinit var channel: RecordingChannel

        harness(grace = 5).execute {
            workers = get()
            channel = channel()
            admission = ask()
            model.awaitRequests(1)
            stop()
        }

        assertIs<TurnOutcome.Completed>(blocking { admission.ticket.outcome() })
        val text = channel.reply(admission.ticket.turn).completed?.text
        assertEquals("Started before the stop.\n\nFinished after it.", text)
        assertEquals(TurnEndKind.COMPLETED, blocking { end(admission.ticket.turn) })
    }

    @Test
    fun `a turn still running after the grace is cut off and ends within the cancel join, also with a stuck tool`() {
        model.reply {
            text("Reading.")
            toolCall("fs.stuck")
        }
        lateinit var admission: Admission.Accepted
        lateinit var channel: RecordingChannel
        var stopped: TimeSource.Monotonic.ValueTimeMark? = null

        val lines = logged {
            harness(grace = 1, join = 2).execute {
                channel = channel()
                admission = ask()
                stuck.awaitCalls(1)
                stopped = TimeSource.Monotonic.markNow()
                stop()
            }
        }
        release.complete(Unit)

        val turn = admission.ticket.turn
        assertEquals(TurnOutcome.ShutDown(replayable = false), blocking { admission.ticket.outcome() })
        assertTrue(checkNotNull(stopped).elapsedNow() >= 2.seconds)
        assertEquals(ReplyEnd.SHUTDOWN, channel.reply(turn).abandoned)
        assertEquals(TurnEndKind.SHUT_DOWN, blocking { end(turn) })
        val result = blocking { entries(turn) }.last() as ToolResultEntry
        assertEquals(ToolOutcome.Cancelled, result.outcome)
        val warning = abandoned(lines).single()
        assertTrue(warning.startsWith("WARN Tool fs.stuck still runs 1s after its call "), warning)
    }

    @Test
    fun `a turn cut off at a shutdown after a stored round answers its open calls and may not be replayed`() {
        read.hold()
        model.reply {
            text("Reading.")
            toolCall("fs.read")
        }
        lateinit var admission: Admission.Accepted
        lateinit var channel: RecordingChannel

        harness().execute {
            channel = channel()
            admission = ask()
            read.awaitCalls(1)
            stop()
        }

        val turn = admission.ticket.turn
        assertEquals(TurnOutcome.ShutDown(replayable = false), blocking { admission.ticket.outcome() })
        assertEquals(ReplyEnd.SHUTDOWN, channel.reply(turn).abandoned)
        val stored = blocking { entries(turn) }
        assertEquals(listOf(UserEntry::class, AssistantEntry::class, ToolResultEntry::class), stored.map { it::class })
        assertEquals(ToolOutcome.Cancelled, (stored.last() as ToolResultEntry).outcome)
        assertEquals(TurnEndKind.SHUT_DOWN, blocking { end(turn) })
    }
}
