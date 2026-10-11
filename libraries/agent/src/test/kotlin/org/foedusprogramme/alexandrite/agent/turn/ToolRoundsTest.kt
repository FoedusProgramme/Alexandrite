package org.foedusprogramme.alexandrite.agent.turn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.serialization.json.jsonPrimitive
import org.foedusprogramme.alexandrite.agent.StoreIndex
import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.agent.logged
import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.hook.HookDecision
import org.foedusprogramme.alexandrite.sdk.hook.ObserverHook
import org.foedusprogramme.alexandrite.sdk.model.FinishKind
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.model.ToolChoice
import org.foedusprogramme.alexandrite.sdk.store.MediaStore
import org.foedusprogramme.alexandrite.sdk.store.TurnEndKind
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.InlineMedia
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.sdk.transcript.MediaPart
import org.foedusprogramme.alexandrite.sdk.transcript.NotRunReason
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeEntry
import org.foedusprogramme.alexandrite.sdk.transcript.NoticeKind
import org.foedusprogramme.alexandrite.sdk.transcript.StoredMedia
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolCallPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome.NotRun
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TranscriptEntry
import org.foedusprogramme.alexandrite.sdk.transcript.UserEntry
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.ToolCallDone
import org.foedusprogramme.alexandrite.sdk.turn.TurnOutcome
import org.foedusprogramme.alexandrite.sdk.turn.TurnPoints
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.RecordedReply.Preview
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import org.foedusprogramme.alexandrite.testkit.recordingTool
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolRoundsTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val read = recordingTool("fs.read") { arguments, _ ->
        ToolResult("Contents of ${arguments["path"]?.jsonPrimitive?.content}.")
    }

    private fun harness(agent: String = "", configure: PluginHarness.Builder.() -> Unit = {}): PluginHarness =
        agentHarness(
            """
            {
              "previewIntervalMillis": 0,
              "shutdown": {"turnGraceSeconds": 0, "cancelJoinSeconds": 2},
              "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}$agent}}
            }
            """.trimIndent(),
            store,
        ) { channel(admins = setOf("admin")).model(model).tool(read).apply(configure) }

    private fun PluginHarness.Running.ask(text: String = "Hi", user: String = "admin"): Admission.Accepted =
        channel().receive(text, sender = channel().user(user)) as Admission.Accepted

    private suspend fun stored(): List<TranscriptEntry> = store.transcripts.entries(store.conversations.current(key).id)

    private fun results(entries: List<TranscriptEntry>): Map<String, ToolResultEntry> =
        entries.filterIsInstance<ToolResultEntry>().associateBy { it.toolName }

    private fun text(result: ToolResultEntry?): String? = (result?.content?.single() as? TextPart)?.text

    // Rounds.

    @Test
    fun `a turn runs the tools of its rounds until the model answers, and stores every round`() {
        model.reply {
            text("Reading.")
            toolCall("fs.read", """{"path": "a"}""")
        }.reply { toolCall("fs.read", """{"path": "b"}""") }.reply { text("Both read.") }
        val committed = Observer(TurnPoints.TURN_COMMITTED)

        harness { hook(committed) }.execute {
            val admission = ask()
            val outcome = admission.ticket.outcome()

            val entries = stored()
            assertEquals(
                listOf(
                    UserEntry::class,
                    AssistantEntry::class,
                    ToolResultEntry::class,
                    AssistantEntry::class,
                    ToolResultEntry::class,
                    AssistantEntry::class,
                ),
                entries.map { it::class },
            )
            assertEquals(
                listOf("Contents of a.", "Contents of b."),
                entries.filterIsInstance<ToolResultEntry>().map(::text),
            )
            val reply = channel().reply(admission.ticket.turn)
            val answer = entries.last() as AssistantEntry
            assertEquals(TurnOutcome.Completed(answer, mapOf(channel().chat() to reply.delivery!!)), outcome)
            assertEquals("Both read.", reply.completed?.text)
            val requests = model.requests
            assertEquals(listOf(0, 1, 2), requests.map { it.ids.round })
            assertEquals(entries.take(3), requests[1].history)
            assertEquals(entries.take(5), requests[2].history)
            assertEquals(listOf(0, 0, 0), requests.map { it.turnStart })
            val shared = requests.map { listOf(it.instructions, it.turnContext, it.tools, it.options, it.cacheKey) }
            assertEquals(1, shared.distinct().size)
            assertEquals(listOf("fs.read"), requests[0].tools.map { it.name })
            assertEquals(List(3) { ToolChoice.Auto }, requests.map { it.toolChoice })
            val calls = entries.filterIsInstance<AssistantEntry>().flatMap { it.parts.filterIsInstance<ToolCallPart>() }
            assertEquals(calls.map { it.id }, read.calls.map { it.call })
            assertEquals(listOf(admission.ticket.turn), read.calls.map { it.turn.id }.distinct())
            assertEquals(3, committed.seen.single().usage?.requests)
            assertEquals(TurnEndKind.COMPLETED, store.conversations.turn(admission.ticket.turn)?.end)
        }
    }

    @Test
    fun `calls of tools not offered, with unreadable arguments or of failing tools get results that say so`() {
        val failing = recordingTool("fs.fail") { _, _ -> error("Disk on fire.\nAt line 2.") }
        val erring = recordingTool("fs.err") { _, _ -> ToolResult("No such file.", isError = true) }
        model.reply {
            toolCall("fs.missing")
            toolCall("fs.read", "[1]")
            toolCall("fs.fail")
            toolCall("fs.err")
        }.reply { text("Done.") }

        harness { tool(failing).tool(erring) }.execute {
            ask().ticket.outcome()

            val results = results(stored())
            assertEquals(
                mapOf(
                    "fs.missing" to ("No tool named 'fs.missing' is offered." to NotRun(NotRunReason.UNKNOWN_TOOL)),
                    "fs.read" to ("The arguments are not a JSON object." to NotRun(NotRunReason.INVALID_ARGUMENTS)),
                    "fs.fail" to ("The tool failed: Disk on fire." to ToolOutcome.Failed),
                    "fs.err" to ("No such file." to ToolOutcome.Failed),
                ),
                results.mapValues { (_, result) -> text(result) to result.outcome },
            )
            assertEquals(emptyList(), read.calls)
            assertEquals(1, failing.calls.size)
        }
    }

    @Test
    fun `a call that a tool before hook denies does not run, and the hook's reply reaches the chat as a notice`() {
        val glob = recordingTool("fs.glob")
        val hook = Interceptor(TurnPoints.TOOL_BEFORE) { check ->
            if (check.call.name == "fs.read") HookDecision.Abort("Not that file.") else error("Broken.")
        }
        model.reply {
            toolCall("fs.read", """{"path": "secret"}""")
            toolCall("fs.glob")
        }.reply { text("Denied.") }

        harness { tool(glob).hook(hook) }.execute {
            val admission = ask()
            admission.ticket.outcome()

            val entries = stored()
            val denied = NotRun(NotRunReason.HOOK_DENIED)
            val outcomes = results(entries).mapValues { it.value.outcome }
            assertEquals(mapOf("fs.glob" to denied, "fs.read" to denied), outcomes)
            assertEquals(emptyList(), read.calls + glob.calls)
            assertEquals(
                listOf("fs.read" to ToolRisk.READ_ONLY, "fs.glob" to ToolRisk.READ_ONLY).toSet(),
                hook.seen.map { it.definition.name to it.risk }.toSet(),
            )
            val notice = channel().sent.single().message
            val trigger = channel().reply(admission.ticket.turn).request.trigger
            assertEquals(
                listOf("Not that file.", MessageKind.NOTICE, trigger),
                listOf(notice.text, notice.kind, notice.replyTo),
            )
            assertEquals(
                listOf(NoticeEntry(null, "Not that file.", NoticeKind.HOOK_ABORTED)),
                entries.filterIsInstance<NoticeEntry>().map { it.withoutRecord() },
            )
            assertEquals("Denied.", channel().reply(admission.ticket.turn).completed?.text)
        }
    }

    @Test
    fun `media in a result is stored before the result, and the next request carries it inline`() {
        val bytes = byteArrayOf(1, 2, 3)
        val shot = recordingTool("screen.shot") { _, _ ->
            ToolResult(listOf(TextPart("A screenshot."), MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(bytes))))
        }
        val after = Observer(TurnPoints.TOOL_AFTER)
        model.reply { toolCall("screen.shot") }.reply { text("Seen.") }

        harness { tool(shot).hook(after) }.execute {
            ask().ticket.outcome()

            val result = stored().filterIsInstance<ToolResultEntry>().single()
            val media = result.content[1] as MediaPart
            val id = (media.source as StoredMedia).id
            assertEquals(bytes.toList(), store.media.read(id)?.toList())
            assertEquals(listOf(result), after.seen.map { it.result })
            val sent = model.requests[1].history.filterIsInstance<ToolResultEntry>().single().content[1] as MediaPart
            assertEquals(InlineMedia(bytes), sent.source)
        }
    }

    // Parallel calls.

    @Test
    fun `the media of results that parallel calls return are stored one at a time`() {
        val media = OneAtATime(store.media)
        val returned = CompletableDeferred<Unit>()
        val started = AtomicInteger()
        val shots = listOf("screen.one", "screen.two").map { name ->
            recordingTool(name) { _, _ ->
                if (started.incrementAndGet() == 2) returned.complete(Unit)
                returned.await()
                ToolResult(listOf(MediaPart(MediaKind.IMAGE, "image/png", InlineMedia(name.encodeToByteArray()))))
            }
        }
        model.reply {
            toolCall("screen.one")
            toolCall("screen.two")
        }.reply { text("Seen.") }

        agentHarness(
            """{"agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}}}}""",
            store = null,
        ) {
            channel(admins = setOf("admin")).model(model).plugin(StoreIndex(store, media = media))
            shots.forEach(::tool)
        }.execute { ask().ticket.outcome() }

        assertEquals(2, media.puts)
        assertEquals(1, media.mostAtOnce)
    }

    @Test
    fun `read-only calls of one response run at most four at once`() {
        read.hold()
        model.reply { repeat(6) { toolCall("fs.read", """{"path": "$it"}""") } }.reply { text("Read.") }

        harness().execute {
            val admission = ask()
            read.awaitCalls(4)
            delay(200)

            assertEquals(4, read.calls.size)
            read.release()
            admission.ticket.outcome()
            assertEquals(6, read.calls.size)
            assertEquals(6, stored().filterIsInstance<ToolResultEntry>().size)
        }
    }

    @Test
    fun `each result is stored as its call ends, before the results of calls that ended later`() {
        val fastStored = CompletableDeferred<Unit>()
        val slow = recordingTool("fs.slow") { _, _ ->
            fastStored.await()
            ToolResult("Slow.")
        }
        val fast = recordingTool("fs.fast") { _, _ -> ToolResult("Fast.") }
        val after = object : ObserverHook<ToolCallDone> {
            override val point = TurnPoints.TOOL_AFTER

            override suspend fun observe(payload: ToolCallDone) {
                if (payload.call.name == "fs.fast") fastStored.complete(Unit)
            }
        }
        model.reply {
            toolCall("fs.slow")
            toolCall("fs.fast")
        }.reply { text("Done.") }

        harness { tool(slow).tool(fast).hook(after) }.execute {
            ask().ticket.outcome()

            val entries = stored()
            assertEquals(listOf("fs.fast", "fs.slow"), entries.filterIsInstance<ToolResultEntry>().map { it.toolName })
            assertEquals(entries.take(4), model.requests[1].history)
        }
    }

    // Ends.

    @Test
    fun `the round after the last tool round lets the model call no tool, and calls made anyway do not run`() {
        model.reply { toolCall("fs.read", """{"path": "a"}""") }.reply {
            text("Partial.")
            toolCall("fs.read", """{"path": "b"}""")
        }

        harness(""", "maxRounds": 1""").execute {
            val admission = ask()
            val outcome = admission.ticket.outcome()

            val entries = stored()
            val requests = model.requests
            assertEquals(listOf(ToolChoice.Auto, ToolChoice.None), requests.map { it.toolChoice })
            assertEquals(listOf("fs.read"), requests[1].tools.map { it.name })
            assertEquals(1, read.calls.size)
            val last = entries.filterIsInstance<ToolResultEntry>().last()
            assertEquals(
                NotRun(NotRunReason.ROUND_LIMIT) to "Not run: the turn used up its tool rounds.",
                last.outcome to text(last),
            )
            val notice = "The model used up the tool rounds of this message, so the answer may be incomplete."
            assertEquals(NoticeEntry(null, notice, NoticeKind.ROUND_LIMIT), entries.last().withoutRecord())
            assertEquals("Partial.", channel().reply(admission.ticket.turn).completed?.text)
            val sent = channel().sent.map { it.message.text to it.message.kind }
            assertEquals(listOf(notice to MessageKind.NOTICE), sent)
            assertEquals<TranscriptEntry?>(entries[3], (outcome as TurnOutcome.Completed).reply)
        }
    }

    @Test
    fun `a paused response is stored and continued without a user entry, three times at most`() {
        model.reply {
            text("Part one.")
            finish(FinishKind.PAUSED)
        }.reply { text("Part two.") }
        repeat(4) { part ->
            model.reply {
                text("Piece $part.")
                finish(FinishKind.PAUSED)
            }
        }

        harness().execute {
            val first = ask()
            first.ticket.outcome()

            val entries = stored()
            assertEquals(
                listOf(UserEntry::class, AssistantEntry::class, AssistantEntry::class),
                entries.map { it::class },
            )
            assertEquals(entries.take(2), model.requests[1].history)
            assertEquals(0, model.requests[1].turnStart)
            val reply = channel().reply(first.ticket.turn)
            assertEquals(Preview(0, "Part one."), reply.previews.first())
            assertEquals("Part two.", reply.completed?.text)

            val second = ask()
            val outcome = second.ticket.outcome() as TurnOutcome.Completed

            assertEquals(listOf(0, 1, 2, 3), model.requests.drop(2).map { it.ids.round })
            assertEquals(4, stored().drop(3).filterIsInstance<AssistantEntry>().size)
            assertEquals("Piece 3.", channel().reply(second.ticket.turn).completed?.text)
            assertEquals<TranscriptEntry?>(stored().last(), outcome.reply)
        }
    }

    @Test
    fun `a blank or refused response after a tool round ends the turn on a notice and keeps the rounds before`() {
        model.reply { toolCall("fs.read") }.reply { reasoning("Nothing.") }
        model.reply { toolCall("fs.read") }.reply {
            text("No.")
            finish(FinishKind.REFUSAL)
        }

        harness().execute {
            for ((kind, text) in listOf(
                NoticeKind.BLANK_REPLY to "The model gave no answer. Try again, or put the message another way.",
                NoticeKind.REFUSED to "The model declined to answer this message.",
            )) {
                val admission = ask()
                assertEquals(TurnOutcome.Completed(null), admission.ticket.outcome(), "$kind")

                val entries = store.transcripts.turnEntries(admission.ticket.turn)
                assertEquals(
                    listOf(UserEntry::class, AssistantEntry::class, ToolResultEntry::class, NoticeEntry::class),
                    entries.map { it::class },
                    "$kind",
                )
                assertEquals(NoticeEntry(null, text, kind), entries.last().withoutRecord())
                assertEquals(text, channel().reply(admission.ticket.turn).completed?.text)
                assertEquals(TurnEndKind.COMPLETED, store.conversations.turn(admission.ticket.turn)?.end)
            }
        }
    }

    @Test
    fun `a response cut off at the output limit in the middle of a call goes on with that call not run`() {
        model.reply {
            text("Reading.")
            toolCall("fs.read", """{"path": "a""")
            finish(FinishKind.MAX_OUTPUT_TOKENS)
        }.reply { text("Done.") }

        harness().execute {
            val admission = ask()
            admission.ticket.outcome()

            val result = stored().filterIsInstance<ToolResultEntry>().single()
            assertEquals(ToolOutcome.NotRun(NotRunReason.INVALID_ARGUMENTS), result.outcome)
            assertEquals("Done.", channel().reply(admission.ticket.turn).completed?.text)
            assertEquals(emptyList(), channel().sent)
        }
    }

    // Offers.

    @Test
    fun `every turn is offered the agent's tools, and a member may call only those it lists for members`() {
        val glob = recordingTool("fs.glob")
        val tools = listOf(glob, recordingTool("fs.write", ToolRisk.WORKSPACE_WRITE))
        model.reply { text("To the admin.") }.reply {
            toolCall("fs.glob")
            toolCall("fs.read", """{"path": "a"}""")
        }.reply { text("To the member.") }

        harness(""", "tools": {"forMembers": ["fs.read"]}""") { tools.forEach(::tool) }.execute {
            ask().ticket.outcome()
            val member = ask(user = "member")
            member.ticket.outcome()

            assertEquals(
                List(3) { listOf("fs.glob", "fs.read", "fs.write") to ToolChoice.Auto },
                model.requests.map { request -> request.tools.map { it.name } to request.toolChoice },
            )
            val results = results(store.transcripts.turnEntries(member.ticket.turn))
            assertEquals(
                NotRun(NotRunReason.NOT_PERMITTED) to "This turn may not call 'fs.glob'.",
                results.getValue("fs.glob").let { it.outcome to text(it) },
            )
            assertEquals(ToolOutcome.Succeeded, results.getValue("fs.read").outcome)
            assertEquals(emptyList(), glob.calls)
        }
    }

    @Test
    fun `a turn that may call none of the tools it offers leaves the choice to the model and refuses its calls`() {
        model.reply { toolCall("fs.read", """{"path": "a"}""") }.reply { text("Read.") }
            .reply { toolCall("fs.read", """{"path": "b"}""") }.reply { text("Hello.") }

        harness().execute {
            ask().ticket.outcome()
            val asked = ask(user = "member")
            asked.ticket.outcome()

            val member = model.requests.drop(2)
            assertEquals(
                List(2) { listOf("fs.read") to ToolChoice.Auto },
                member.map { request -> request.tools.map { it.name } to request.toolChoice },
            )
            assertEquals(model.requests[0].tools, member[0].tools)
            assertTrue(member[0].history.any { it is ToolResultEntry })
            val refused = results(store.transcripts.turnEntries(asked.ticket.turn)).getValue("fs.read")
            assertEquals(NotRun(NotRunReason.NOT_PERMITTED), refused.outcome)
            assertEquals(1, read.calls.size)
        }
    }

    @Test
    fun `a call to a tool that turn start leaves out does not run, while the request still offers it`() {
        val hook = Interceptor(TurnPoints.TURN_START) { HookDecision.Replace(it.withTools(emptyList())) }
        model.reply { toolCall("fs.read", """{"path": "a"}""") }.reply { text("Done.") }

        harness { hook(hook) }.execute {
            ask().ticket.outcome()

            assertEquals(
                List(2) { listOf("fs.read") to ToolChoice.Auto },
                model.requests.map { request -> request.tools.map { it.name } to request.toolChoice },
            )
            val result = stored().filterIsInstance<ToolResultEntry>().single()
            assertEquals(NotRun(NotRunReason.NOT_PERMITTED), result.outcome)
            assertEquals(emptyList(), read.calls)
        }
    }

    @Test
    fun `a model that takes no tools is offered none, with one warning`() {
        val plain = ModelInfo.builder("plain", ScriptedModel.DIALECT).build()
        val model = ScriptedModel(models = listOf(plain)).reply { text("One.") }.reply { text("Two.") }

        val lines = logged {
            agentHarness(
                """{"agents": {"coder": {"model": "scripted/plain", "channels": {"test:main": {}}}}}""",
                store,
            ) { channel(admins = setOf("admin")).model(model).tool(read) }.execute {
                ask().ticket.outcome()
                ask().ticket.outcome()
            }
        }

        assertEquals(listOf(emptyList(), emptyList()), model.requests.map { it.tools })
        assertEquals(1, lines.count { it.startsWith("WARN Model scripted/plain takes no tools") })
    }
}

/** The media of [store], which counts how many puts run at once. */
private class OneAtATime(private val store: MediaStore) : MediaStore by store {
    private val running = AtomicInteger()

    @Volatile
    var mostAtOnce: Int = 0
        private set

    @Volatile
    var puts: Int = 0
        private set

    override suspend fun put(bytes: ByteArray, kind: MediaKind, mediaType: String): StoredMedia {
        val now = running.incrementAndGet()
        synchronized(this) {
            mostAtOnce = maxOf(mostAtOnce, now)
            puts++
        }
        try {
            delay(200)
            return store.put(bytes, kind, mediaType)
        } finally {
            running.decrementAndGet()
        }
    }
}
