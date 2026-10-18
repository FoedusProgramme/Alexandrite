package org.unitedsoftware.alexandrite.agent.job

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.logged
import org.unitedsoftware.alexandrite.agent.startingInBackground
import org.unitedsoftware.alexandrite.agent.turn.withoutRecord
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.RunId
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.hook.ObserverHook
import org.unitedsoftware.alexandrite.sdk.hook.ObserverPoint
import org.unitedsoftware.alexandrite.sdk.store.TurnEndKind
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeKind
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.AgentControl
import org.unitedsoftware.alexandrite.sdk.turn.JobLifecycle
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.sdk.turn.TurnPoints
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RecordedReply
import org.unitedsoftware.alexandrite.testkit.RecordingChannel.Event
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.TestRecords
import org.unitedsoftware.alexandrite.testkit.recordingTool
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class JobTurnsTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val finish = CompletableDeferred<Unit>()
    private val slow = recordingTool("fs.slow") { _, _ ->
        finish.await()
        ToolResult("Slow done.")
    }
    private val read = recordingTool("fs.read")

    private fun harness(agent: String = "", settings: String = "", configure: PluginHarness.Builder.() -> Unit = {}) =
        agentHarness(
            """
            {
              "previewIntervalMillis": 0$settings,
              "shutdown": {"turnGraceSeconds": 0, "cancelJoinSeconds": 2},
              "agents": {
                "coder": {"model": "scripted/test-model", "channels": {"test:main": {}}, "foregroundSeconds": 1$agent}
              }
            }
            """.trimIndent(),
            store,
        ) { channel(admins = setOf("admin")).model(model).tool(slow).tool(read).apply(configure) }

    private fun PluginHarness.Running.ask(text: String = "Read the big file."): Admission.Accepted =
        channel().receive(text, sender = channel().user("admin")) as Admission.Accepted

    private suspend fun stored(): List<TranscriptEntry> = store.transcripts.entries(store.conversations.current(key).id)

    private suspend fun recorded(): Set<RunId> = JobRecords(TestRecords(store).of("alexandrite-agent")).all().keys

    /** The job that the stored result of the first call of `fs.slow` names. */
    private suspend fun job(): RunId = (
        stored().filterIsInstance<ToolResultEntry>().first {
            it.toolName == "fs.slow"
        }.outcome as ToolOutcome.Detached
        )
        .job

    /** The reply of the job turn that answers the chat, once it ended. */
    private suspend fun PluginHarness.Running.jobReply(): RecordedReply {
        val opened = channel().awaitEvent { (it as? Event.Opened)?.request?.turn?.kind == TurnKind.JOB } as Event.Opened
        val reply = channel().awaitReply(opened.request.turn.id).awaitEnd()
        until { store.conversations.turn(reply.turn.id)?.end != null }
        return reply
    }

    private suspend fun until(condition: suspend () -> Boolean) {
        withTimeout(5.seconds) { while (!condition()) delay(10.milliseconds) }
    }

    private fun framing(entry: TranscriptEntry): String? =
        ((entry as? UserEntry)?.parts?.firstOrNull() as? ContextPart)?.takeIf { it.source == "alexandrite.job" }?.text

    private fun text(entry: TranscriptEntry): String? = when (entry) {
        is ToolResultEntry -> (entry.content.single() as TextPart).text
        is AssistantEntry -> entry.parts.filterIsInstance<TextPart>().joinToString("") { it.text }
        else -> null
    }

    // Moving to the background.

    @Test
    fun `only a call that moved to the background after its foreground time fires tool backgrounded`() {
        val points = CopyOnWriteArrayList<String>()
        val started = recordingTool("test.watch") { _, _ -> awaitCancellation() }
        model.reply { toolCall("fs.slow") }.reply { toolCall("test.watch") }.reply { text("Both run.") }

        harness {
            tool(startingInBackground(started))
            for (point in listOf(TurnPoints.JOB_STARTED, TurnPoints.TOOL_BACKGROUNDED)) {
                hook(object : ObserverHook<JobLifecycle> {
                    override val point: ObserverPoint<JobLifecycle> = point
                    override suspend fun observe(payload: JobLifecycle) {
                        points += "${point.id}:${payload.call.name}"
                    }
                })
            }
        }.execute {
            assertIs<TurnOutcome.Completed>(ask().ticket.outcome())
            assertEquals(
                listOf("job.started:fs.slow", "tool.backgrounded:fs.slow", "job.started:test.watch"),
                points.toList(),
            )
            get<AgentControl>().jobs(channel().chat()).forEach {
                get<AgentControl>().stopJob(channel().chat(), it.id, null)
            }
        }
    }

    @Test
    fun `a call that runs past the foreground time goes on as a job, whose result a job turn answers the chat with`() {
        model.reply { toolCall("fs.slow", """{"path": "/srv/big"}""") }
            .reply { text("It runs in the background.") }
            .reply { text("The big file is read.") }

        harness().execute {
            val admission = ask()
            assertIs<TurnOutcome.Completed>(admission.ticket.outcome())
            val id = job()
            val control = get<AgentControl>()
            val status = control.jobs(channel().chat()).single()
            assertEquals(
                listOf(id, "fs.slow", "/srv/big", channel().user("admin")),
                listOf(status.id, status.tool, status.title, status.by),
            )
            assertEquals(setOf(id), recorded())
            finish.complete(Unit)

            val reply = jobReply()

            assertEquals("The big file is read.", reply.completed?.text)
            assertEquals(null, reply.request.trigger)
            val turn = checkNotNull(store.conversations.turn(reply.turn.id))
            assertEquals(
                listOf(TurnKind.JOB, channel().user("admin").address, TurnEndKind.COMPLETED),
                listOf(turn.kind, turn.actor, turn.end),
            )
            val entries = stored()
            assertEquals(
                "[alexandrite:background] fs.slow is still running as job $id; its result will come in a later turn " +
                    "— go on, or end your turn.",
                text(entries[2]),
            )
            assertEquals("It runs in the background.", text(entries[3]))
            val framing = checkNotNull(framing(entries[4]))
            assertTrue(framing.startsWith("[alexandrite:job]\nA background job ended: job $id, fs.slow /srv/big, "))
            assertTrue(framing.endsWith(". It succeeded.\nSlow done.\n[/alexandrite:job]"), framing)
            assertEquals(UserOrigin.SystemWritten("alexandrite.job"), (entries[4] as UserEntry).origin)
            assertEquals(listOf(reply.turn.id, reply.turn.id), entries.drop(4).map { it.record?.turn })
            assertEquals(entries.take(5), model.requests[2].history)
            assertEquals(emptyList(), control.jobs(channel().chat()))
            assertEquals(emptySet(), recorded())
            assertEquals(1, slow.calls.size)
        }
    }

    @Test
    fun `a job that ends while a turn of its conversation runs joins that turn at its next tool boundary`() {
        read.hold()
        model.reply { toolCall("fs.slow") }.reply { toolCall("fs.read", """{"path": "a"}""") }.reply { text("Both.") }

        harness().execute {
            val admission = ask()
            read.awaitCalls(1)
            finish.complete(Unit)
            until { get<AgentControl>().jobs(channel().chat()).isEmpty() }
            read.release()

            assertIs<TurnOutcome.Completed>(admission.ticket.outcome())
            val entries = stored()
            assertEquals(listOf(null, null, null, null, null, true, null), entries.map { framing(it)?.isNotEmpty() })
            assertEquals(entries, store.transcripts.turnEntries(admission.ticket.turn))
            assertEquals(entries.take(6), model.requests[2].history)
            assertEquals("Both.", channel().reply(admission.ticket.turn).completed?.text)
            assertEquals(listOf(admission.ticket.turn), channel().replies.map { it.turn.id })
            assertEquals(emptySet(), recorded())
        }
    }

    @Test
    fun `the time a call waits for its approval does not count toward its foreground time`() {
        val write = recordingTool("fs.write", ToolRisk.WORKSPACE_WRITE) { _, _ ->
            delay(500.milliseconds)
            ToolResult("Written.")
        }
        model.reply { toolCall("fs.write") }.reply { text("Done.") }

        harness { tool(write) }.execute {
            channel().holdApprovals()
            channel().scriptApprovals(ApprovalAnswer.Allowed(channel().user("admin")))
            val admission = ask("Write it.")
            channel().awaitApprovals(1)
            delay(1500.milliseconds)
            channel().releaseApprovals()
            admission.ticket.outcome()

            val result = stored().filterIsInstance<ToolResultEntry>().single()
            assertEquals(ToolOutcome.Succeeded to "Written.", result.outcome to text(result))
        }
    }

    @Test
    fun `a chat runs at most maxJobsPerChat jobs, and past that a call stays in the foreground`() {
        val quick = recordingTool("fs.quick") { _, _ ->
            delay(1500.milliseconds)
            ToolResult("Quick done.")
        }
        model.reply { toolCall("fs.slow") }.reply { toolCall("fs.quick") }.reply { text("One left.") }

        val lines = logged {
            harness(settings = """, "maxJobsPerChat": 1""") { tool(quick) }.execute {
                ask().ticket.outcome()

                val results = stored().filterIsInstance<ToolResultEntry>()
                assertIs<ToolOutcome.Detached>(results[0].outcome)
                assertEquals(ToolOutcome.Succeeded to "Quick done.", results[1].outcome to text(results[1]))
                assertEquals(1, get<AgentControl>().jobs(channel().chat()).size)
            }
        }

        assertEquals(
            1,
            lines.count {
                it.startsWith("INFO Call ") &&
                    it.endsWith("stays in the foreground: the chat already runs 1 background jobs")
            },
        )
    }

    // Stopping.

    @Test
    fun `cancel stops the chat's jobs, whose tools are cancelled and whose results never come`() {
        model.reply { toolCall("fs.slow") }.reply { text("Running.") }

        harness().execute {
            ask().ticket.outcome()
            val control = get<AgentControl>()
            val admin = channel().user("admin")

            assertTrue(control.cancel(channel().chat(), admin))
            until { slow.cancelled.size == 1 && recorded().isEmpty() }
            delay(200.milliseconds)

            assertEquals(emptyList(), control.jobs(channel().chat()))
            assertFalse(control.cancel(channel().chat(), admin))
            assertEquals(2, model.requests.size)
            assertEquals(emptyList(), channel().sent)
        }
    }

    @Test
    fun `a job is stopped by its id from its own chat only, through the control port or the agent's own tool`() {
        model.reply { toolCall("fs.slow", """{"path": "a"}""") }.reply { text("First.") }
            .reply { toolCall("fs.slow", """{"path": "b"}""") }.reply { text("Second.") }

        harness().execute {
            ask().ticket.outcome()
            ask().ticket.outcome()
            val control = get<AgentControl>()
            val (first, second) = control.jobs(channel().chat()).map { it.id }
            model.reply { toolCall("agent.jobs") }.reply { toolCall("agent.stop", """{"job": "$second"}""") }
                .reply { text("Stopped it.") }

            assertFalse(control.stopJob(channel().chat("other"), first, null))
            assertTrue(control.stopJob(channel().chat(), first, null))
            until { slow.cancelled.size == 1 }
            ask("Stop the other one.").ticket.outcome()

            until { slow.cancelled.size == 2 }
            val results = stored().filterIsInstance<ToolResultEntry>().drop(2).map(::text)
            assertTrue(results[0]!!.startsWith("Jobs that run in this chat:\n- $second: fs.slow b (running since "))
            assertEquals("Job $second is stopped; its result will not come.", results[1])
            assertEquals(emptyList(), control.jobs(channel().chat()))
            assertFalse(control.cancelRun(second, tree = false, by = null))
            assertEquals(emptySet(), recorded())
        }
    }

    // Restarts and seals.

    @Test
    fun `a restart reports a job that was running as interrupted in its conversation and does not run it again`() {
        model.reply { toolCall("fs.slow", """{"path": "/srv/big"}""") }.reply { text("Running.") }
        var started: Pair<TurnId, RunId>? = null

        harness().execute {
            val turn = ask().ticket.turn
            channel().awaitReply(turn).awaitEnd()
            started = turn to job()
        }

        val (turn, id) = checkNotNull(started)

        assertEquals(1, slow.cancelled.size)
        assertEquals(setOf(id), blocking { recorded() })

        val lines = logged { harness().execute {} }

        val notice = "Job $id (fs.slow /srv/big) was interrupted by a restart; it was not run again."
        assertEquals(
            NoticeEntry(null, notice, NoticeKind.INTERRUPTED),
            blocking { store.transcripts.turnEntries(turn) }.last().withoutRecord(),
        )
        assertEquals(emptySet(), blocking { recorded() })
        assertEquals(1, slow.calls.size)
        assertEquals(
            listOf(
                "INFO Job $id (fs.slow /srv/big) of test:main:chat was interrupted by a restart: it is not run again",
            ),
            lines.filter { it.contains("interrupted by a restart") },
        )
    }

    @Test
    fun `the next turn after a restart tells the model once which of its jobs the restart interrupted`() {
        model.reply { toolCall("fs.slow", """{"path": "/srv/big"}""") }.reply { text("Running.") }
            .reply { text("The read was cut off; I can start it again.") }.reply { text("Yes.") }

        harness().execute { ask().ticket.outcome() }
        harness().execute {
            val id = job()
            ask("Is the big file read?").ticket.outcome()
            ask("Should I ask again?").ticket.outcome()

            val interrupted = UserEntry(
                null,
                listOf(
                    ContextPart(
                        "alexandrite.job",
                        "[alexandrite:job]\nA background job ended: job $id, fs.slow /srv/big. It was interrupted by " +
                            "a restart and was not run again, so no result will come.\n[/alexandrite:job]",
                    ),
                ),
                UserOrigin.SystemWritten("alexandrite.job"),
            )
            val (_, _, next, later) = model.requests
            assertEquals(interrupted, next.history[next.history.size - 2])
            assertIs<UserOrigin.FromChat>((next.history.last() as UserEntry).origin)
            assertEquals(1, later.history.count { it.withoutRecord() == interrupted })
            assertEquals(1, stored().count { it.withoutRecord() == interrupted })
            val pending = InterruptedJobs(TestRecords(store).of("alexandrite-agent"), store.conversations)
            assertEquals(null, pending.pending(store.conversations.current(key).id))
        }
    }

    @Test
    fun `the result of a job whose conversation was sealed meanwhile is dropped, telling the chat`() {
        model.reply { toolCall("fs.slow", """{"path": "/srv/big"}""") }.reply { text("Running.") }

        val lines = logged {
            harness().execute {
                ask().ticket.outcome()
                val id = job()
                get<AgentControl>().newConversation(channel().chat(), channel().user("admin"))
                finish.complete(Unit)

                val sent = channel().awaitEvent { it is Event.Sent } as Event.Sent
                assertEquals(
                    "Job $id (fs.slow /srv/big) ended after its conversation was closed, so its result was dropped.",
                    sent.message.text,
                )
                assertEquals(channel().chat() to MessageKind.NOTICE, sent.chat to sent.message.kind)
                until { recorded().isEmpty() }
                assertEquals(emptyList(), stored())
                assertEquals(2, model.requests.size)
            }
        }

        assertEquals(1, lines.count { it.startsWith("INFO The result of job ") && it.endsWith("was sealed") })
    }

    @Test
    fun `stopping a monitor discards its queued wake without starting a model turn`() {
        val blocked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val emit = CompletableDeferred<Unit>()
        val emitted = CompletableDeferred<Unit>()
        val monitor = recordingTool("test.monitor") { _, context ->
            val job = checkNotNull(context.background())
            emit.await()
            repeat(100) { job.update(ToolResult("MATCH $it")) }
            emitted.complete(Unit)
            awaitCancellation()
        }
        model.reply { toolCall("test.monitor") }.reply {
            pause {
                blocked.complete(Unit)
                release.await()
            }
            text("Parent done.")
        }
        harness { tool(startingInBackground(monitor)) }.execute {
            val ticket = ask().ticket
            withTimeout(5.seconds) { blocked.await() }
            val control = get<AgentControl>()
            val id = control.jobs(channel().chat()).single().id
            emit.complete(Unit)
            withTimeout(5.seconds) { emitted.await() }
            assertTrue(control.stopJob(channel().chat(), id, channel().user("admin")))
            release.complete(Unit)
            assertIs<TurnOutcome.Completed>(ticket.outcome())
            until { control.turns(channel().chat()).isEmpty() && recorded().isEmpty() }
            assertEquals(2, model.requests.size)
            assertEquals(1, channel().replies.size)
            assertFalse(
                stored().filterIsInstance<UserEntry>().any {
                    it.origin == UserOrigin.SystemWritten("alexandrite.job")
                },
            )
        }
    }

    // Taint.

    @Test
    fun `what the tool reports untrusted after its call moved on marks the conversation`() {
        val fetch = recordingTool("web.slow") { _, context ->
            finish.await()
            context.untrusted("a web page")
            ToolResult("Page.")
        }
        model.reply { toolCall("web.slow") }.reply { text("Fetching.") }.reply { text("Fetched.") }

        harness { tool(fetch) }.execute {
            ask().ticket.outcome()
            val conversation = store.conversations.current(key).id
            assertEquals(null, store.conversations.taint(conversation))
            finish.complete(Unit)

            jobReply()

            assertEquals(
                "call ${store.transcripts.entries(conversation)[1].toolCall()} reported untrusted content: a web page",
                store.conversations.taint(conversation)?.reason,
            )
        }
    }

    private fun TranscriptEntry.toolCall(): String =
        (this as AssistantEntry).parts.filterIsInstance<ToolCallPart>().single().id.value
}
