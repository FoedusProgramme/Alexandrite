package org.unitedsoftware.alexandrite.agent.permission

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.agent.TestClock
import org.unitedsoftware.alexandrite.agent.agentDirectory
import org.unitedsoftware.alexandrite.agent.logged
import org.unitedsoftware.alexandrite.agent.model.Endpoints
import org.unitedsoftware.alexandrite.agent.provider
import org.unitedsoftware.alexandrite.agent.settings
import org.unitedsoftware.alexandrite.agent.tool.GateCall
import org.unitedsoftware.alexandrite.agent.tool.TurnCalls
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.model.ModelEndpoint
import org.unitedsoftware.alexandrite.sdk.model.ModelErrorKind
import org.unitedsoftware.alexandrite.sdk.model.ModelEvent
import org.unitedsoftware.alexandrite.sdk.model.ModelRequest
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.CommandSegment
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.SegmentJoint
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.EndpointId
import org.unitedsoftware.alexandrite.sdk.transcript.ModelRef
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.UserEntry
import org.unitedsoftware.alexandrite.sdk.transcript.UserOrigin
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.recordingTool
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testToolContext
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUser
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SideModelJudgeTest {
    private val model = ScriptedModel()
    private val operatorUser = testUser("operator", isOperator = true)
    private val operator: TurnInfo = testTurn { actor(operatorUser) }
    private val write = recordingTool("fs.write", ToolRisk.WORKSPACE_WRITE)
    private val exec = recordingTool("command.exec", ToolRisk.EXEC)

    /** A judge of agent `main` over [endpoints], with [permission] in the agent's permission section. */
    private fun judge(permission: String = "", vararg endpoints: ModelEndpoint = arrayOf(model)): SideModelJudge {
        val config = """
            {
              "agents": {
                "main": {"description": "Main.", "model": "scripted/test-model", "permission": {$permission}}
              }
            }
        """.trimIndent()
        val settings = settings(config)
        val providers = listOf(provider(*endpoints))
        return SideModelJudge(
            Endpoints(providers, settings, TestClock()),
            agentDirectory(settings, providers = providers),
        )
    }

    /** A call of [tool] in [turn] whose operator wrote [words], as the gate hands it to the judge. */
    private fun call(
        words: List<String> = listOf(WORDS),
        arguments: String = """{"path": "notes.md", "content": "hello"}""",
        tool: Tool = write,
        turn: TurnInfo = operator,
        preview: ReviewPreview? = null,
        required: String? = null,
        taint: TurnTaint = TurnTaint(turn.id, turn.key),
    ): CheckedCall {
        val part = ToolCallPart(ToolCallId("call-1"), tool.definition.name, arguments)
        val gate =
            GateCall(turn, part, tool, part.parseArguments()!!, 5.seconds, taint, testToolContext(turn), Words(words))
        return CheckedCall(gate, CallReview.builder().preview(preview).build(), tool.definition.risk, required)
    }

    private fun answers(vararg replies: String) {
        for (reply in replies) model.reply { text(reply) }
    }

    /** The text of the request that the judge sent its model last. */
    private fun sent(): String {
        val entry = assertIs<UserEntry>(model.requests.last().history.single())
        return (entry.parts.single() as TextPart).text
    }

    private fun reason(verdict: Verdict): String = when (verdict) {
        Verdict.Allow -> "allow"
        is Verdict.Deny -> verdict.reason
        is Verdict.Ask -> verdict.reason
    }

    // Answers.

    @Test
    fun `an allow answer allows the call`() = runTest {
        answers("""{"verdict":"allow","reason":"exactly what was asked"}""")

        assertEquals(Verdict.Allow, judge().judge(call()))
    }

    @Test
    fun `a deny answer refuses the call with its reason, even fenced and among other text`() = runTest {
        answers(
            "Thinking...\n```json\n{\"verdict\": \"DENY\", \"reason\": \"Unrelated to the request.\"}\n```",
            """{"verdict":"deny"}""",
        )
        val judge = judge()

        val verdicts = List(2) { assertIs<Verdict.Deny>(judge.judge(call())).reason }

        assertEquals(listOf("the judge refused it: Unrelated to the request", "the judge refused it"), verdicts)
    }

    @Test
    fun `an ask answer asks a person, telling them why the judge is unsure`() = runTest {
        answers("""{"verdict":"ask","reason":"It may overwrite\nthe\u202E file."}""", """{"verdict":"ask"}""")
        val judge = judge()

        val verdicts = List(2) { assertIs<Verdict.Ask>(judge.judge(call())) }

        assertEquals(List(2) { "the judge is unsure" }, verdicts.map { it.reason })
        assertEquals(listOf("It may overwrite the file", null), verdicts.map { it.note })
    }

    @Test
    fun `a blank, unreadable or unknown answer asks a person`() = runTest {
        val replies = listOf("", "   ", "sure, go ahead", """{"verdict":"maybe"}""", """{"reason":"x"}""")
        val more = listOf("""{"verdict":42}""", "{not json}", """["allow"]""")
        answers(*(replies + more).toTypedArray())
        val judge = judge()

        val verdicts = (replies + more).map { reason(assertIs<Verdict.Ask>(judge.judge(call()))) }

        assertEquals(
            listOf(
                "the judge answered nothing",
                "the judge answered nothing",
                "the judge's answer could not be read",
                "the judge gave no known verdict",
                "the judge gave no known verdict",
                "the judge gave no known verdict",
                "the judge's answer could not be read",
                "the judge's answer could not be read",
            ),
            verdicts,
        )
    }

    @Test
    fun `a model slower than the timeout asks a person`() = runTest {
        model.reply { hang() }

        val verdict = judge(""""judgeTimeoutSeconds": 3""").judge(call())

        assertEquals("the judge did not answer within 3 s", reason(assertIs<Verdict.Ask>(verdict)))
        assertEquals(3_000, testScheduler.currentTime)
    }

    @Test
    fun `a failing model or one that its endpoint does not list asks a person`() = runTest {
        model.fail(ModelErrorKind.SERVER_ERROR)

        val failed = judge().judge(call())
        val unlisted = judge(""""judge": "scripted/other-model"""").judge(call())

        assertEquals(
            listOf("the judge's model failed (server_error)", "the judge's model failed (model_not_found)"),
            listOf(failed, unlisted).map { reason(assertIs<Verdict.Ask>(it)) },
        )
        assertEquals(1, model.requests.size)
    }

    @Test
    fun `a stray cancellation from inside the model asks a person while the caller is alive`() = runTest {
        val stray = object : ModelEndpoint by model {
            override fun stream(request: ModelRequest): Flow<ModelEvent> =
                flow { throw CancellationException("Inside the endpoint.") }
        }

        val verdict = judge("", stray).judge(call())

        assertEquals("the judge's model failed (CancellationException)", reason(assertIs<Verdict.Ask>(verdict)))
    }

    @Test
    fun `the caller's cancellation ends the judgement with it`() = runTest {
        model.reply { hang() }
        val judge = judge()
        var ended: Throwable? = null

        val judging = launch {
            try {
                judge.judge(call())
            } catch (e: CancellationException) {
                ended = e
                throw e
            }
        }
        runCurrent()
        judging.cancel()
        runCurrent()

        assertIs<CancellationException>(ended)
    }

    // What the model is asked.

    @Test
    fun `the configured judge model is asked, else the agent's own`() = runTest {
        val other = ScriptedModel(EndpointId("judge")).reply { text("""{"verdict":"allow"}""") }
        answers("""{"verdict":"allow"}""")

        judge(""""judge": "judge/test-model"""", model, other).judge(call())
        judge("", model, other).judge(call())

        assertEquals(
            listOf(ModelRef.parse("judge/test-model"), ModelRef.parse("scripted/test-model")),
            (other.requests + model.requests).map { it.model },
        )
    }

    @Test
    fun `the request carries only the call and the operator's words, at temperature 0 without tools`() = runTest {
        answers("""{"verdict":"ask","reason":"unsure"}""")

        judge().judge(call(words = listOf(WORDS, "Into the one in the root.")))

        val request = model.requests.single()
        assertEquals(
            """
            What the operator typed in this turn, one block per message:
            <<<
            Please write hello into notes.md.
            >>>
            <<<
            Into the one in the root.
            >>>

            The call:
            Tool: fs.write
            Risk: WORKSPACE_WRITE
            Arguments (canonical JSON):
            | {"content":"hello","path":"notes.md"}
            """.trimIndent(),
            sent(),
        )
        assertEquals(UserOrigin.SystemWritten("permission-judge"), (request.history.single() as UserEntry).origin)
        val instructions = request.instructions.single()
        assertEquals("permission-judge" to true, instructions.id to instructions.stable)
        assertContains(instructions.text, """{"verdict":"allow|deny|ask","reason":"<one short sentence>"}""")
        assertEquals(
            listOf(0.0, 1024, emptyList<Any>(), null),
            listOf(request.options.temperature, request.options.maxOutputTokens, request.tools, request.cacheKey),
        )
        assertEquals(operator.id to operator.conversation, request.ids.turn to request.ids.conversation)
    }

    @Test
    fun `the judge is told that the conversation is tainted and why, and that an unread mark counts`() = runTest {
        repeat(2) { answers("""{"verdict":"ask"}""") }
        val tainted = TurnTaint(operator.id, operator.key).apply {
            note("a call of web.fetch, which reads the network")
        }

        judge().judge(call(taint = tainted))
        assertContains(
            sent(),
            "\n\n$TAINTED_INTRODUCTION\n<<<\na call of web.fetch, which reads the network\n>>>\n\nThe call:\n",
        )
        judge().judge(call(taint = TurnTaint(operator.id, operator.key, unread = true)))
        assertContains(
            sent(),
            "$TAINTED_INTRODUCTION\n<<<\nits mark could not be read, so it counts as tainted\n>>>\n",
        )
        assertContains(model.requests.last().instructions.single().text, "When the conversation took in untrusted")
    }

    @Test
    fun `the judge reads the review's preview in place of the arguments, and the hook's reason`() = runTest {
        repeat(4) { answers("""{"verdict":"ask"}""") }
        val judge = judge()
        val command = ReviewPreview.Command(
            listOf("sh", "-c", "rm -rf build && echo it's done"),
            Path.of("/srv/repo"),
            Path.of("/bin/sh"),
            opaque = true,
        )
        val diff = ReviewPreview.Diff(Path.of("/srv/notes.md"), "--- a\n+++ b\n@@ -1 +1 @@\n-old\n+new\n")

        val shown = listOf(
            call(tool = exec, preview = command, required = "Look first."),
            call(preview = diff),
            call(preview = ReviewPreview.Text("Sends a message\nto the team.")),
            call(preview = ReviewPreview.Diff(Path.of("/srv/notes.md"), " ")),
        ).map {
            judge.judge(it)
            sent().substringAfter("The call:\n")
        }

        assertEquals(
            listOf(
                """
                Tool: command.exec
                Risk: EXEC
                A hook requires approval: Look first.
                Working directory: /srv/repo
                Program: /bin/sh
                Opaque: yes
                Runs in: no sandbox, on the host with the operator's full rights
                Command line:
                | sh -c 'rm -rf build && echo it'\''s done'
                """.trimIndent(),
                """
                Tool: fs.write
                Risk: WORKSPACE_WRITE
                File: /srv/notes.md
                Change (unified diff):
                | --- a
                | +++ b
                | @@ -1 +1 @@
                | -old
                | +new
                """.trimIndent(),
                """
                Tool: fs.write
                Risk: WORKSPACE_WRITE
                Preview:
                | Sends a message
                | to the team.
                """.trimIndent(),
                """
                Tool: fs.write
                Risk: WORKSPACE_WRITE
                Arguments (canonical JSON):
                | {"content":"hello","path":"notes.md"}
                """.trimIndent(),
            ),
            shown,
        )
    }

    @Test
    fun `the judge reads each step of a chain, with how it follows the one before and its variables`() = runTest {
        answers("""{"verdict":"ask"}""")
        val sub = Path.of("/srv/repo/sub")
        val chain = ReviewPreview.Command(
            listOf("sh", "-c", "cd sub && A=1 make || echo failed"),
            Path.of("/srv/repo"),
            Path.of("/bin/sh"),
            opaque = false,
            segments = listOf(
                CommandSegment(listOf("cd", "sub"), sub, null, null, changesDirectory = true),
                CommandSegment(
                    listOf("make"),
                    sub,
                    Path.of("/usr/bin/make"),
                    SegmentJoint.AND,
                    environment = mapOf("A" to "1"),
                ),
                CommandSegment(listOf("echo", "failed"), sub, null, SegmentJoint.OR, opaque = true),
            ),
        )

        judge().judge(call(tool = exec, preview = chain))

        assertEquals(
            """
            Tool: command.exec
            Risk: EXEC
            Working directory: /srv/repo
            Program: /bin/sh
            Opaque: no
            Runs in: no sandbox, on the host with the operator's full rights
            Alexandrite runs these steps itself, as plain programs, in place of the command line:
            Step 1: cd sub — changes the directory to /srv/repo/sub
            Step 2 (after &&): make — program /usr/bin/make, variable A=1
            Step 3 (after ||): echo failed — program not found, opaque
            Command line:
            | sh -c 'cd sub && A=1 make || echo failed'
            """.trimIndent(),
            sent().substringAfter("The call:\n"),
        )
    }

    @Test
    fun `the judge reads a file's path, size, hash and destination, and its caption or else the arguments`() = runTest {
        repeat(2) { answers("""{"verdict":"ask"}""") }
        val judge = judge()
        val chat = ChatAddress.parse("test:main:chat")
        val sha256 = "ab".repeat(32)
        val captioned = ReviewPreview.OutgoingFile(Path.of("/srv/out/report.pdf"), 1200, sha256, chat, "This week")
        val bare = ReviewPreview.OutgoingFile(Path.of("/srv/out/report.pdf"), 1200, sha256, chat)

        val shown = listOf(captioned, bare).map {
            judge.judge(call(preview = it, arguments = """{"path": "/srv/out/report.pdf"}"""))
            sent().substringAfter("The call:\n").substringAfter("SHA-256: $sha256\n")
        }

        assertTrue(
            "File: /srv/out/report.pdf\nSize: 1200 bytes\nSHA-256: $sha256\n" in sent(),
            sent(),
        )
        assertEquals(
            listOf(
                "Sent to the chat: test:main:chat\nCaption:\n| This week",
                "Sent to the chat: test:main:chat\nArguments (canonical JSON):\n| {\"path\":\"/srv/out/report.pdf\"}",
            ),
            shown,
        )
    }

    @Test
    fun `the judge sees invisible and reordering characters escaped, keeping the operator's line breaks`() = runTest {
        answers("""{"verdict":"ask"}""", """{"verdict":"ask"}""")
        val judge = judge()

        judge.judge(
            call(
                words = listOf("write\u2066 it\nplease\u0000"),
                arguments = "{\"path\": \"notes\u202Egpj.md\", \"x\": \"\u200B\u2028\"}",
            ),
        )
        val escaped = sent()
        val command = ReviewPreview.Command(listOf("rm", "a\u202Eb\nc"), Path.of("/srv"), null, opaque = false)
        judge.judge(call(tool = exec, preview = command))
        val commandLine = sent()

        assertContains(escaped, "<<<\nwrite\\u2066 it\nplease\\u0000\n>>>")
        assertContains(escaped, "| {\"path\":\"notes\\u202Egpj.md\",\"x\":\"\\u200B\\u2028\"}")
        assertContains(
            commandLine,
            "Program: not found\nOpaque: no\nRuns in: no sandbox, on the host with the operator's full rights\n" +
                "Command line:\n| rm 'a\\u202Eb\\u000Ac'",
        )
        val hidden = setOf('\u202E', '\u2066', '\u200B', '\u2028', '\u0000')
        assertFalse((escaped + commandLine).any { it in hidden })
    }

    // What the judge decides without its model.

    @Test
    fun `without the operator's own words the judge asks without calling the model`() = runTest {
        val judge = judge()

        val verdicts = listOf(emptyList(), listOf(" ", "")).map { reason(judge.judge(call(words = it))) }

        assertEquals(List(2) { "the operator typed nothing in this turn for the judge to go by" }, verdicts)
        assertEquals(emptyList(), model.requests)
    }

    @Test
    fun `a call longer than the judge can see is asked about without calling the model`() = runTest {
        answers("""{"verdict":"allow"}""")
        val judge = judge()
        val room = 32_000 - "fs.write".length - """{"c":""}""".length
        val long = """{"c": "${"a".repeat(room + 1)}"}"""
        val fits = """{"c": "${"a".repeat(room)}"}"""

        val verdicts = listOf(long, fits).map { reason(judge.judge(call(arguments = it))) }

        assertEquals(listOf(CALL_TOO_LONG, "allow"), verdicts)
        assertEquals(1, model.requests.size)
        assertContains(sent(), "| {\"c\":\"${"a".repeat(room)}\"}")
    }

    @Test
    fun `the operator's words longer than the judge can see are asked about without calling the model`() = runTest {
        answers("""{"verdict":"allow"}""")
        val judge = judge()
        val long = listOf("a".repeat(16_000), "a".repeat(16_000) + " and yes, write it")

        val verdicts = List(4) { reason(judge.judge(call(words = long))) } +
            reason(judge.judge(call(words = listOf("a".repeat(16_000), "a".repeat(16_000)))))

        assertEquals(List(4) { WORDS_TOO_LONG } + "allow", verdicts)
        assertEquals(1, model.requests.size)
    }

    @Test
    fun `the configured limit holds for the call's fields together and for the operator's words apart`() = runTest {
        answers("""{"verdict":"allow"}""", """{"verdict":"allow"}""")
        val judge = judge(""""judgeMaxChars": 100""")
        val path = Path.of("/srv/notes.md")
        val body = "+".repeat(100 - "fs.write".length - "$path".length)
        val words = listOf("a".repeat(50), "a".repeat(50))

        val verdicts = listOf(
            call(words = words, preview = ReviewPreview.Diff(path, body)),
            call(words = words + "a", preview = ReviewPreview.Diff(path, body)),
            call(preview = ReviewPreview.Diff(path, "$body+")),
            call(preview = ReviewPreview.Diff(path, body), required = "!"),
            call(preview = ReviewPreview.Diff(path, body)),
        ).map { reason(judge.judge(it)) }

        assertEquals(listOf("allow", WORDS_TOO_LONG, CALL_TOO_LONG, CALL_TOO_LONG, "allow"), verdicts)
        assertEquals(2, model.requests.size)
    }

    @Test
    fun `the length limit applies to the escaped text`() = runTest {
        val judge = judge()

        val verdicts = listOf(
            call(arguments = """{"c": "${"\u202E".repeat(6000)}"}"""),
            call(words = listOf("\u202E".repeat(6000))),
            call(required = "\u202E".repeat(6000)),
        ).map { reason(judge.judge(it)) }

        assertEquals(listOf(CALL_TOO_LONG, WORDS_TOO_LONG, CALL_TOO_LONG), verdicts)
        assertEquals(emptyList(), model.requests)
    }

    // The streak of asks.

    @Test
    fun `three asks in a row for one chat and tool stop the model calls, while others keep their own count`() =
        runTest {
            answers(*Array(3) { """{"verdict":"ask","reason":"unsure"}""" })
            val judge = judge()
            val elsewhere = testTurn(chat = testChat("elsewhere")) { actor(operatorUser) }

            val asked = List(3) { judge.judge(call()) }
            val tripped = judge.judge(call())
            answers("""{"verdict":"allow"}""", """{"verdict":"allow"}""")
            val others = listOf(judge.judge(call(tool = exec)), judge.judge(call(turn = elsewhere)))

            assertEquals(List(3) { "the judge is unsure" }, asked.map(::reason))
            assertEquals("the judge was unsure about fs.write 3 times in a row in this chat", reason(tripped))
            assertEquals(listOf(Verdict.Allow, Verdict.Allow), others)
            assertEquals(5, model.requests.size)
        }

    @Test
    fun `an allow or a deny of the model resets the count`() = runTest {
        val replies = listOf("ask", "ask", "allow", "ask", "ask", "deny", "ask", "ask", "ask")
        answers(*replies.map { """{"verdict":"$it"}""" }.toTypedArray())
        val judge = judge()

        repeat(9) { judge.judge(call()) }
        judge.judge(call())

        assertEquals(9, model.requests.size)
    }

    @Test
    fun `an operator's allow of that tool in that chat resets the count`() = runTest {
        answers(*Array(3) { """{"verdict":"ask"}""" })
        val judge = judge()
        repeat(4) { judge.judge(call()) }

        judge.allowedByOperator(testTurn(chat = testChat("elsewhere")).key, "fs.write")
        judge.allowedByOperator(operator.key, "command.exec")
        judge.judge(call())
        val before = model.requests.size
        judge.allowedByOperator(operator.key, "fs.write")
        answers("""{"verdict":"allow"}""")

        assertEquals(Verdict.Allow, judge.judge(call()))
        assertEquals(3 to 4, before to model.requests.size)
    }

    @Test
    fun `only the model's own asks count, never its failures, missing words or long fields`() = runTest {
        repeat(4) { model.fail(ModelErrorKind.SERVER_ERROR) }
        val judge = judge()

        repeat(4) { judge.judge(call()) }
        repeat(4) { judge.judge(call(words = emptyList())) }
        repeat(4) { judge.judge(call(arguments = """{"c": "${"a".repeat(32_000)}"}""")) }
        answers(*Array(3) { """{"verdict":"ask"}""" })
        repeat(3) { judge.judge(call()) }
        val requests = model.requests.size
        judge.judge(call())

        assertEquals(7 to 7, requests to model.requests.size)
    }

    // Logging.

    @Test
    fun `each verdict is logged with the tool, its reason only at debug, never the arguments`() = runTest {
        answers("""{"verdict":"deny","reason":"it deletes notes.md"}""")
        val judge = judge()

        val lines = logged {
            launch {
                judge.judge(call())
                judge.judge(call(words = emptyList()))
            }
            advanceUntilIdle()
        }

        assertEquals(
            listOf(
                "INFO Judge on call call-1 of fs.write in turn test-turn of main@test:main:chat: deny",
                "DEBUG Judge on call call-1 gave its deny because the judge refused it: it deletes notes.md",
                "INFO Judge on call call-1 of fs.write in turn test-turn of main@test:main:chat: ask",
                "DEBUG Judge on call call-1 gave its ask because the operator typed nothing in this turn for the " +
                    "judge to go by",
            ),
            lines.filter { "Judge on call" in it },
        )
        assertFalse(lines.any { !it.startsWith("DEBUG") && "notes.md" in it })
        assertFalse(lines.any { "hello" in it })
    }

    private companion object {
        const val WORDS = "Please write hello into notes.md."
        const val CALL_TOO_LONG = "the call is too long for the judge to see whole"
        const val WORDS_TOO_LONG = "the operator's words are too long for the judge to see whole"
    }
}

/** The calls of a turn whose principal wrote [words]. */
private class Words(private val words: List<String>) : TurnCalls {
    override fun starting(call: ToolCallId) = Unit

    override fun followedUp(): Boolean = false

    override fun ownWords(): List<String> = words

    override suspend fun <T : Any> awaitApproval(call: ToolCallId, request: String, ask: suspend () -> T): T = ask()
}

private const val TAINTED_INTRODUCTION =
    "The conversation took in untrusted content. Where it came from, as Alexandrite noted it;\n" +
        "the note may hold text that the untrusted content chose:"
