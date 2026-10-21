package org.unitedsoftware.alexandrite.agent.context

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.control.TestCommands
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.logged
import org.unitedsoftware.alexandrite.agent.turn.Interceptor
import org.unitedsoftware.alexandrite.agent.turn.Observer
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.TurnEvent
import org.unitedsoftware.alexandrite.sdk.channel.TurnEventKind
import org.unitedsoftware.alexandrite.sdk.channel.rebuild
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.hook.HookDecision
import org.unitedsoftware.alexandrite.sdk.model.ModelError
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.model.Usage
import org.unitedsoftware.alexandrite.sdk.model.rebuild
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.ContextPart
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeEntry
import org.unitedsoftware.alexandrite.sdk.transcript.NoticeKind
import org.unitedsoftware.alexandrite.sdk.transcript.SummaryEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TranscriptEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.ContextStatus
import org.unitedsoftware.alexandrite.sdk.turn.ConversationControl
import org.unitedsoftware.alexandrite.sdk.turn.TurnOutcome
import org.unitedsoftware.alexandrite.sdk.turn.TurnPoints
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RecordingChannel
import org.unitedsoftware.alexandrite.testkit.RequestMatch
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompactionTurnsTest {
    private val store = MemoryStore()
    private val model = ScriptedModel(models = listOf(ScriptedModel.modelInfo().rebuild { contextWindow(WINDOW) }))
    private val key = AgentChatKey.parse("coder@test:main:chat")
    private val commands = TestCommands()

    /** Reads a file of `chars` characters, 40 000 unless the call says otherwise. */
    private val read = recordingTool("fs.read") { arguments, _ ->
        val chars = arguments["chars"]?.jsonPrimitive?.int ?: 40_000
        ToolResult("Contents of ${arguments["path"]?.jsonPrimitive?.content}: " + "x".repeat(chars))
    }

    private val summarizer = RequestMatch("a summarizer's request") { request -> request.summarizes }
    private val turns = RequestMatch("a turn's request") { request -> !request.summarizes }

    private val ModelRequest.summarizes: Boolean get() = instructions.any { it.id == SUMMARIZER_SECTION }

    /** The coder compacting by [compaction], offered only fs.read when [readOnly], so the prompt keeps its size. */
    private fun harness(
        compaction: String,
        readOnly: Boolean = false,
        configure: PluginHarness.Builder.() -> Unit = {},
    ): PluginHarness = agentHarness(
        """
            {
              "previewIntervalMillis": 0,
              "agents": {
                "coder": {"model": "scripted/test-model", "channels": {"test:main": {}}, "compaction": $compaction
                  ${if (readOnly) ", \"tools\": {\"allow\": [\"fs.read\"]}" else ""}}
              }
            }
        """.trimIndent(),
        store,
    ) { channel(operators = setOf("operator")).model(model).tool(read).commandHandler(commands).apply(configure) }

    private fun PluginHarness.Running.ask(text: String): Admission.Accepted =
        channel().receive(text, sender = channel().user("operator")) as Admission.Accepted

    /** Has the model read each of [paths], then answer with [answer]. */
    private fun reads(vararg paths: String, chars: Int = 40_000, answer: String) {
        for (path in paths) model.reply(turns) { toolCall("fs.read", """{"path": "$path", "chars": $chars}""") }
        model.reply(turns) { text(answer) }
    }

    private suspend fun stored(): List<TranscriptEntry> = store.transcripts.entries(store.conversations.current(key).id)

    private fun summaryText(entry: TranscriptEntry): String? = (
        (entry as? UserEntry)?.takeIf { it.origin == UserOrigin.SystemWritten(SUMMARY_SOURCE) }?.parts?.single()
            as? ContextPart
        )?.text

    private fun turnsOf(request: ModelRequest): List<TurnId> = request.history.mapNotNull { it.record?.turn }.distinct()

    private fun texts(request: ModelRequest): List<String> = request.history.filterIsInstance<ToolResultEntry>()
        .map { (it.content.single() as TextPart).text.take(40) }

    // Layer 2.

    @Test
    fun `a long tool loop compacts mid-turn, goes on, and its next request starts with the summary`() {
        reads("a", answer = "Read a.")
        reads("b", answer = "Read b.")
        reads("c", "d", "e", answer = "Read them all.")
        model.reply(summarizer) { text("Ada asked to read a; it was read.") }
        val compacted = Observer(TurnPoints.CONTEXT_COMPACTED)

        harness("""{"trigger": 0.6, "target": 0.3, "clearAt": "off", "keepTurns": 1}""") { hook(compacted) }.execute {
            val first = ask("First.").also { it.ticket.outcome() }
            val second = ask("Second.").also { it.ticket.outcome() }
            val third = ask("Third.")
            val outcome = third.ticket.outcome()

            val requests = model.requests.filterNot { it.summarizes }
            val (beforeIt, after) = requests.takeLast(2)
            assertEquals(listOf(first.ticket.turn, second.ticket.turn, third.ticket.turn), turnsOf(beforeIt))
            assertEquals(3, after.ids.round)
            assertEquals(
                listOf(second.ticket.turn, third.ticket.turn),
                after.history.drop(1).mapNotNull { it.record?.turn }.distinct(),
            )
            assertEquals(
                summaryFraming("Ada asked to read a; it was read."),
                summaryText(after.history.first()),
            )
            assertEquals(1 + 4, after.turnStart)
            val summary = stored().filterIsInstance<SummaryEntry>().single()
            val firstEntries = stored().filter { it.record?.turn == first.ticket.turn }
            assertEquals(firstEntries.last().record!!.id, summary.through)
            assertEquals(third.ticket.turn, summary.record!!.turn)
            val reply = assertIs<TurnOutcome.Completed>(outcome).reply
            assertEquals(listOf(TextPart("Read them all.")), reply?.parts)
            val input = model.requests.single { it.summarizes }.history.single() as UserEntry
            val text = (input.parts.single() as TextPart).text
            assertTrue("| First." in text && "| Read a." in text && "Second." !in text, text)
            val seen = compacted.seen.single()
            assertEquals(summary, seen.summary)
            val tokens = seen.tokensBefore to seen.tokensAfter
            assertTrue(tokens.first >= 60_000 && tokens.second < 60_000, "$tokens")
        }
        model.assertFinished()
    }

    @Test
    fun `a second compaction folds the first summary with the turns it covers next`() {
        reads("a", answer = "Read a.")
        reads("b", answer = "Read b.")
        reads("c", "d", "e", answer = "Read them all.")
        model.reply(summarizer) { text("First summary.") }
        reads("f", answer = "Read f.")
        model.reply(summarizer) { text("Second summary.") }

        harness("""{"trigger": 0.6, "target": 0.3, "clearAt": "off", "keepTurns": 1}""").execute {
            for (text in listOf("First.", "Second.", "Third.")) ask(text).ticket.outcome()
            val fourth = ask("Fourth.").ticket.outcome()

            assertIs<TurnOutcome.Completed>(fourth)
            val (_, second) = model.requests.filter { it.summarizes }
            val input = ((second.history.single() as UserEntry).parts.single() as TextPart).text
            assertTrue(input.startsWith("[previous summary]\n| First summary.\n"), input.take(80))
            assertTrue("| Second." in input && "Third." !in input, input)
            val last = model.requests.last()
            assertEquals(summaryFraming("Second summary."), summaryText(last.history.first()))
            assertEquals(1, last.history.count { summaryText(it) != null })
            val summaries = stored().filterIsInstance<SummaryEntry>()
            assertEquals(listOf("First summary.", "Second summary."), summaries.map { it.text })
        }
    }

    @Test
    fun `a fold that reaches its target inside a turn stops at the end of a round`() {
        reads("a", "b", "c", answer = "Read three.")
        model.reply(turns) { text("Hi.") }
        reads("d", "e", answer = "Done.")
        model.reply(summarizer) { text("Two files read.") }

        val config = """{"trigger": 0.6, "target": 0.5, "clearAt": "off", "keepTurns": 1, "maxSummaryTokens": 500}"""
        harness(config).execute {
            for (text in listOf("First.", "Second.", "Third.")) ask(text).ticket.outcome()

            val summary = stored().filterIsInstance<SummaryEntry>().single()
            val firstTurn = stored().filterIsInstance<ToolResultEntry>().take(3)
            assertEquals(firstTurn[1].record!!.id, summary.through)
            val after = model.requests.last()
            val kept = after.history[1] as AssistantEntry
            assertEquals(listOf("fs.read"), kept.parts.filterIsInstance<ToolCallPart>().map { it.name })
            assertEquals(firstTurn[2].callId, (after.history[2] as ToolResultEntry).callId)
        }
        model.assertFinished()
    }

    @Test
    fun `a compaction keeps the newest turns verbatim, and with nothing older to fold the turn goes on`() {
        reads("a", answer = "Read a.")
        reads("b", answer = "Read b.")
        reads("c", "d", "e", answer = "Read them all.")

        val lines = logged {
            harness("""{"trigger": 0.6, "target": 0.3, "clearAt": "off", "keepTurns": 2}""").execute {
                for (text in listOf("First.", "Second.")) ask(text).ticket.outcome()
                assertIs<TurnOutcome.Completed>(ask("Third.").ticket.outcome())
                assertEquals(emptyList(), stored().filterIsInstance<SummaryEntry>())
            }
        }

        assertEquals(emptyList(), model.requests.filter { it.summarizes })
        val nothing = "nothing outside its newest 2 turns and its own newest 5 rounds is left to fold"
        assertTrue(lines.any { it.startsWith("INFO") && nothing in it }, "$lines")
        assertEquals(1, lines.count { "is left to fold" in it })
    }

    @Test
    fun `a single turn of forty rounds compacts its own older rounds again and again and completes`() {
        val paths = (1..40).map { "f$it" }
        reads(*paths.toTypedArray(), chars = 8_000, answer = "Read all forty.")
        for (number in 1..6) model.reply(summarizer) { text("Summary $number.") }
        val config = """{"trigger": 0.4, "target": 0.2, "clearAt": "off", "keepToolRounds": 3, """ +
            """"maxSummaryTokens": 500}"""

        harness(config).execute {
            val ask = ask("Read every file.")
            val outcome = ask.ticket.outcome()

            val reply = assertIs<TurnOutcome.Completed>(outcome).reply
            assertEquals(listOf(TextPart("Read all forty.")), reply?.parts)
            val stored = stored()
            val opening = stored.first() as UserEntry
            val summaries = stored.filterIsInstance<SummaryEntry>()
            assertTrue(summaries.size >= 2, "${summaries.size} compactions")
            assertEquals(6 - summaries.size, model.remaining)
            assertEquals(40, stored.count { it is ToolResultEntry })
            val requests = model.requests.filterNot { it.summarizes }
            val compacted = requests.filter { summaryText(it.history.first()) != null }
            assertTrue(compacted.size >= 20, "${compacted.size} requests after a compaction")
            for (request in compacted) {
                assertEquals(opening, request.history[1])
                assertEquals(1, request.turnStart)
                val read = request.history.filterIsInstance<ToolResultEntry>().map { it.path() }
                val newest = paths.take(request.ids.round)
                assertTrue(read.size >= minOf(3, newest.size) && read == newest.takeLast(read.size), "$read")
            }
            for (summary in summaries) {
                assertEquals(listOf(opening.record!!.id), summary.kept)
                assertTrue(summary.covers(stored[1].record!!.id))
            }
            val last = summaries.last()
            val newest = stored.filter { it.record?.turn == ask.ticket.turn && it !is SummaryEntry }.takeLast(7)
            assertTrue(newest.none { last.covers(it.record!!.id) })
            val inputs = model.requests.filter { it.summarizes }
                .map { ((it.history.single() as UserEntry).parts.single() as TextPart).text }
            assertTrue(inputs.drop(1).all { it.startsWith("[previous summary]") })
            assertTrue(inputs.none { "Read every file." in it })
            assertTrue("Contents of f1:" in inputs.first())
        }
    }

    private fun ToolResultEntry.path(): String = (content.single() as TextPart).text.substringAfter("Contents of ")
        .substringBefore(':')

    // Failures.

    @Test
    fun `a summary that fails leaves the turn to old tool results cleared, when that makes it fit`() {
        reads("a", answer = "Read a.")
        reads("b", answer = "Read b.")
        reads("c", "d", "e", answer = "Read them all.")
        model.fail(ModelError.builder(ModelErrorKind.INVALID_REQUEST, "No.").retryable(false).build(), summarizer)
        val cleared = Observer(TurnPoints.CONTEXT_CLEARED)

        val config = """{"trigger": 0.6, "target": 0.3, "clearAtLeast": 0.9, "keepToolRounds": 2, "keepTurns": 1}"""
        val lines = logged {
            harness(config) { hook(cleared) }.execute {
                for (text in listOf("First.", "Second.")) ask(text).ticket.outcome()
                assertIs<TurnOutcome.Completed>(ask("Third.").ticket.outcome())
            }
        }

        val last = model.requests.last()
        assertEquals(
            listOf(
                "[alexandrite:cleared] fs.read: 40015 cha",
                "[alexandrite:cleared] fs.read: 40015 cha",
                "[alexandrite:cleared] fs.read: 40015 cha",
                "Contents of d: xxxxxxxxxxxxxxxxxxxxxxxxx",
                "Contents of e: xxxxxxxxxxxxxxxxxxxxxxxxx",
            ),
            texts(last),
        )
        assertNull(summaryText(last.history.first()))
        assertEquals(3, cleared.seen.single().calls.size)
        assertTrue(
            lines.any {
                it.startsWith("WARN The summary of conversation") && "(invalid_request)" in it
            },
            "$lines",
        )
        model.assertFinished()
    }

    @Test
    fun `a blank summary ends a turn that still fills the window with the full-window notice`() {
        model.reply(turns) { text("Hi.") }.reply(turns) { text("Hi.") }
        reads("a", "b", "c", chars = 120_000, answer = "Never.")
        model.reply(summarizer) { text(" ") }

        val lines = logged {
            harness("""{"clearAt": "off", "keepTurns": 1}""").execute {
                for (text in listOf("First.", "Second.")) ask(text).ticket.outcome()
                val third = ask("Third.")
                val outcome = third.ticket.outcome()

                assertIs<TurnOutcome.Failed>(outcome)
                val notice = channel().reply(third.ticket.turn).completed!!
                assertEquals(MessageKind.NOTICE, notice.kind)
                assertEquals(
                    "This conversation no longer fits into the model's context window. Start a new conversation " +
                        "with /new.",
                    notice.text,
                )
                assertEquals(NoticeKind.FAILED, (stored().last() as NoticeEntry).kind)
                assertEquals(emptyList(), stored().filterIsInstance<SummaryEntry>())
            }
        }
        assertTrue(lines.any { it.startsWith("WARN The summary of conversation") && "wrote none" in it }, "$lines")
        assertTrue(lines.any { it.startsWith("WARN") && "fills the context window" in it }, "$lines")
        assertEquals(1, model.remaining)
    }

    // Layer 1.

    @Test
    fun `old tool results are cleared in batches, so requests in between keep the same prefix`() {
        reads("a", "b", "c", "d", "e", answer = "Read five.")
        model.reply(turns) { text("Again.") }
        val cleared = Observer(TurnPoints.CONTEXT_CLEARED)

        harness("""{"clearAt": 0.3, "clearAtLeast": 0.05, "keepToolRounds": 1}""", readOnly = true) {
            hook(cleared)
        }.execute {
            ask("Read them.").ticket.outcome()
            ask("Again.").ticket.outcome()

            val requests = model.requests
            val full = "Contents of %s: xxxxxxxxxxxxxxxxxxxxxxxxx"
            val gone = "[alexandrite:cleared] fs.read: 40015 cha"
            assertEquals(listOf(full.format("a"), full.format("b")), texts(requests[2]))
            assertEquals(listOf(gone, gone, full.format("c")), texts(requests[3]))
            assertEquals(requests[3].history, requests[4].history.take(requests[3].history.size))
            assertEquals(listOf(gone, gone, full.format("c"), full.format("d")), texts(requests[4]))
            assertEquals(listOf(gone, gone, gone, gone, full.format("e")), texts(requests[5]))
            assertEquals(listOf(gone, gone, gone, gone, full.format("e")), texts(requests[6]))
            val batches = cleared.seen.map { batch -> batch.calls.map { it.path } }
            assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), batches)
            assertTrue(cleared.seen.all { it.tokensFreed > 20_000 }, "${cleared.seen.map { it.tokensFreed }}")
            val kept = stored().filterIsInstance<ToolResultEntry>().map {
                (it.content.single() as TextPart).text.length
            }
            assertEquals(List(5) { 40_015 }, kept)
        }
    }

    private val ToolCallPart.path: String get() = arguments.substringAfter("\"path\": \"").substringBefore('"')

    // Hooks and events.

    @Test
    fun `a hook may write the summary itself, and the channel is told of the compaction when it asks`() {
        reads("a", answer = "Read a.")
        reads("b", answer = "Read b.")
        reads("c", "d", "e", answer = "Read them all.")
        val compact = Interceptor(TurnPoints.CONTEXT_COMPACT) { HookDecision.Replace(it.withSummary("Hook summary.")) }

        harness("""{"trigger": 0.6, "target": 0.3, "clearAt": "off", "keepTurns": 1}""") { hook(compact) }.execute {
            val capabilities = RecordingChannel.DEFAULT_CAPABILITIES.rebuild { events(setOf(TurnEventKind.COMPACTED)) }
            channel().scriptCapabilities(capabilities)
            val first = ask("First.").also { it.ticket.outcome() }
            ask("Second.").ticket.outcome()
            val third = ask("Third.").also { it.ticket.outcome() }

            assertEquals(emptyList(), model.requests.filter { it.summarizes })
            assertEquals("Hook summary.", stored().filterIsInstance<SummaryEntry>().single().text)
            val payload = compact.seen.single()
            assertEquals(stored().filter { it.record?.turn == first.ticket.turn }, payload.entries)
            assertEquals(listOf(null, null), listOf(payload.previous, payload.focus))
            val event = channel().reply(third.ticket.turn).activity.filterIsInstance<TurnEvent.Compacted>().single()
            assertTrue(event.tokensBefore > event.tokensAfter)
            assertEquals(emptyList(), channel().reply(first.ticket.turn).activity)
        }
        model.assertFinished()
    }

    @Test
    fun `a hook that aborts a compaction leaves the turn going on as it is`() {
        reads("a", answer = "Read a.")
        reads("b", answer = "Read b.")
        reads("c", "d", "e", answer = "Read them all.")
        val compact = Interceptor(TurnPoints.CONTEXT_COMPACT) { HookDecision.Abort("Not now.") }
        val compacted = Observer(TurnPoints.CONTEXT_COMPACTED)

        harness("""{"trigger": 0.6, "target": 0.3, "clearAt": "off", "keepTurns": 1}""") {
            hook(compact).hook(compacted)
        }.execute {
            for (text in listOf("First.", "Second.")) ask(text).ticket.outcome()

            assertIs<TurnOutcome.Completed>(ask("Third.").ticket.outcome())
            assertEquals(1, compact.seen.size)
            assertEquals(emptyList(), compacted.seen)
            assertEquals(emptyList(), stored().filterIsInstance<SummaryEntry>())
        }
        model.assertFinished()
    }

    // Commands and measures.

    @Test
    fun `compact folds what it may on request with its focus in the summarizer's instructions`() {
        for (answer in listOf("One.", "Two.", "Three.")) model.reply(turns) { text(answer) }
        model.reply(summarizer) { text("The user counted to one.") }

        harness("{}").execute {
            commands.connect(get(), get(), get())
            val operator = channel().user("operator")
            for (text in listOf("First.", "Second.")) ask(text).ticket.outcome()
            val early = channel().receiveCommand("compact", issuer = operator) as Admission.Accepted
            early.ticket.outcome()
            ask("Third.").ticket.outcome()
            val compact = channel().receiveCommand(
                "compact",
                "the failing tests",
                issuer = operator,
            ) as Admission.Accepted
            compact.ticket.outcome()

            assertEquals(
                "Refused: There is nothing to compact yet: the conversation holds no more than its newest 2 turns, " +
                    "which are kept as they are.",
                channel().sent.first().message.text,
            )
            assertEquals("Folded 2.", channel().sent.last().message.text)
            val request = model.requests.single { it.summarizes }
            val focus = request.instructions.single { it.id == FOCUS_SECTION }
            assertEquals(
                "The person who asked for this summary wants it to keep this in view:\n| the failing tests",
                focus.text,
            )
            val summary = stored().filterIsInstance<SummaryEntry>().single()
            val turn = store.conversations.turn(summary.record!!.turn)!!
            assertEquals(listOf(summary), stored().filter { it.record?.turn == turn.id })
            val status = get<ConversationControl>().context(channel().chat())
            assertEquals(listOf(summary.record!!.createdAt, 2), listOf(status.compactedAt, status.summarized))
        }
    }

    @Test
    fun `context reports the last counted prompt, the window and the thresholds of the conversation`() {
        val usage = Usage.builder().inputTokens(12_345).contextTokens(12_345).build()
        model.reply(turns) {
            text("Hi.")
            usage(usage)
        }

        harness("""{"target": 15000}""").execute {
            val before = get<ConversationControl>().context(channel().chat())
            ask("First.").ticket.outcome()
            val after = get<ConversationControl>().context(channel().chat())

            assertEquals(listOf(null, null, WINDOW, 95_000L, 15_000L, null, 0), before.fields())
            assertEquals(12_345L, after.promptTokens)
            assertEquals(listOf(WINDOW, 95_000L, 15_000L, null, 0), after.fields().drop(2))
            assertEquals(store.conversations.current(key).id, after.conversation)
            assertEquals(model.ref, after.model)
        }
    }

    private fun ContextStatus.fields(): List<Any?> =
        listOf(promptTokens, measuredAt, window, trigger, target, compactedAt, summarized)

    @Test
    fun `a reported prompt that does not fit the window is distrusted, one that fits scales the estimate`() {
        fun compactions(reported: Long): Int {
            val model = ScriptedModel(models = listOf(ScriptedModel.modelInfo().rebuild { contextWindow(20_000) }))
            model.reply(turns) {
                text("Hi.")
                usage(Usage.builder().inputTokens(reported).contextTokens(reported).build())
            }.reply(turns) { text("Hello again.") }.reply(summarizer) { text("Said hi.") }
            val config = """{"trigger": 0.2, "target": 0.1, "keepTurns": 0}"""
            agentHarness(
                """{"previewIntervalMillis": 0, "agents": {"coder": {"model": "scripted/test-model", """ +
                    """"channels": {"test:main": {}}, "compaction": $config}}}""",
                MemoryStore(),
            ) { channel(operators = setOf("operator")).model(model) }.execute {
                ask("First.").ticket.outcome()
                ask("Second.").ticket.outcome()
            }
            return model.requests.count { it.summarizes }
        }

        assertEquals(0, compactions(1_000_000))
        assertEquals(1, compactions(9_900))
    }

    @Test
    fun `a model whose endpoint gives no context window has its conversation compacted within the assumed one`() {
        val model = ScriptedModel()
        model.reply(turns) { toolCall("fs.read", """{"path": "a"}""") }
            .reply(turns) { toolCall("fs.read", """{"path": "b"}""") }
            .reply(turns) { text("Read them.") }
            .reply(turns) { text("Done.") }
            .reply(summarizer) { text("Read a and b.") }
        val config = """{"trigger": 20000, "target": 5000, "clearAt": "off", "keepTurns": 0}"""

        agentHarness(
            """{"previewIntervalMillis": 0, "agents": {"coder": {"model": "scripted/test-model", """ +
                """"channels": {"test:main": {}}, "compaction": $config}}}""",
            store,
        ) { channel(operators = setOf("operator")).model(model).tool(read) }.execute {
            ask("First.").ticket.outcome()
            assertIs<TurnOutcome.Completed>(ask("Second.").ticket.outcome())
            val status = get<ConversationControl>().context(channel().chat())

            assertEquals(1, model.requests.count { it.summarizes })
            assertEquals(1, stored().filterIsInstance<SummaryEntry>().size)
            assertEquals(
                Triple(ASSUMED_CONTEXT_WINDOW, 20_000L, true),
                Triple(status.window, status.trigger, status.windowAssumed),
            )
        }
        model.assertFinished()
    }

    private companion object {
        const val WINDOW = 100_000
    }
}
