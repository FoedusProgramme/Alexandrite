package org.unitedsoftware.alexandrite.agent.sandbox

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.agent.DirectCalls
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.control.SettingsStates
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.agent.hook.TurnHooks
import org.unitedsoftware.alexandrite.agent.permission.Approvals
import org.unitedsoftware.alexandrite.agent.permission.CallRules
import org.unitedsoftware.alexandrite.agent.permission.CheckedCall
import org.unitedsoftware.alexandrite.agent.permission.InstructionPlaces
import org.unitedsoftware.alexandrite.agent.permission.Judge
import org.unitedsoftware.alexandrite.agent.permission.NoForesight
import org.unitedsoftware.alexandrite.agent.permission.NoMarkOffers
import org.unitedsoftware.alexandrite.agent.permission.PermissionGate
import org.unitedsoftware.alexandrite.agent.permission.Question
import org.unitedsoftware.alexandrite.agent.permission.Verdict
import org.unitedsoftware.alexandrite.agent.permission.session
import org.unitedsoftware.alexandrite.agent.tool.GateCall
import org.unitedsoftware.alexandrite.agent.tool.GateResult
import org.unitedsoftware.alexandrite.agent.tool.ToolRegistry
import org.unitedsoftware.alexandrite.agent.trust.CallTrust
import org.unitedsoftware.alexandrite.agent.trust.TrustRules
import org.unitedsoftware.alexandrite.agent.trust.TurnTaint
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.SettingChange
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.plugin.PluginOrigins
import org.unitedsoftware.alexandrite.sdk.sandbox.Boundary
import org.unitedsoftware.alexandrite.sdk.sandbox.Contained
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.CommandSegment
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.tool.rebuild
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.RecordingEnvironment
import org.unitedsoftware.alexandrite.testkit.testToolContext
import org.unitedsoftware.alexandrite.testkit.testToolDefinition
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUser
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/** How the gate decides calls of command.exec that run in their agent's sandbox. */
class SandboxGateTest {
    @TempDir
    lateinit var directory: Path

    private val operator = testUser("operator", isOperator = true)
    private val turn: TurnInfo = testTurn { agent(CODER).actor(operator) }

    private fun harness(
        sandbox: String = """{"type": "seatbelt"}""",
        permission: String = "{}",
        trusted: Path? = null,
    ): PluginHarness = agentHarness(
        """{"trustedFolders": [${trusted?.let { JsonPrimitive("$it") } ?: ""}], """ +
            """"agents": {"coder": {"description": "Codes.", "permission": $permission, "sandbox": $sandbox}}}""",
    ) {
        dataRoot(directory.resolve("data"))
        executionEnvironment(RecordingEnvironment("seatbelt"))
    }

    /** The gate of the running agent over [exec], a built-in tool unless [thirdParty]. */
    private inner class Gate(running: PluginHarness.Running, thirdParty: Boolean = false) {
        val exec = ScriptedExec()
        val rules: CallRules = running.get()
        val approvals = Offering(rules)
        val judge = Judging()
        private val states: SettingsStates = running.get()
        private val origins = object : PluginOrigins {
            override fun isBuiltIn(instance: Any): Boolean = !thirdParty
        }
        private val clock: Clock = running.get()
        val gate = PermissionGate(
            running.get<TurnHooks>(),
            ToolRegistry(listOf(exec), emptyList(), running.get<AgentDirectory>(), origins),
            rules,
            running.get<TrustRules>(),
            judge,
            approvals,
            states,
            running.get<AgentDirectory>(),
            clock,
            running.get<Sandboxes>(),
            running.get<InstructionPlaces>(),
            NoForesight,
            NoMarkOffers,
        )

        suspend fun mode(mode: PermissionMode, of: TurnInfo = turn) {
            val until = Instant.now().plus(1, ChronoUnit.HOURS).takeIf { mode == PermissionMode.BYPASS }
            states.changeMode(of.key, SettingChange.SetTo(mode), until)
        }

        /** What the gate decided of [review] in [of]. */
        suspend fun admit(review: CallReview, of: TurnInfo = turn, tainted: Boolean = false): GateResult {
            exec.review = review
            val taint = TurnTaint(of.id, of.key).apply { if (tainted) mark("a test") }
            val call = ToolCallPart(ToolCallId("call-1"), "command.exec", "{}")
            val context = testToolContext(of)
            return gate.admit(GateCall(of, call, exec, ARGUMENTS, 5.seconds, taint, context, DirectCalls))
        }

        /** How the gate decided [review] in [of]: run, sandbox, mode, rule, judge, ask, plan or refused. */
        suspend fun decide(review: CallReview, of: TurnInfo = turn, tainted: Boolean = false): String =
            when (val admitted = admit(review, of, tainted)) {
                is GateResult.Admitted ->
                    if (admitted.clearance.kind == ClearanceKind.NONE) "run" else "${admitted.clearance.kind}"

                is GateResult.Refused -> when {
                    admitted.text == ASKED -> "ask"
                    admitted.outcome == ToolOutcome.NotRun(NotRunReason.PLAN_MODE) -> "plan"
                    else -> "refused"
                }
            }
    }

    @Test
    fun `a contained call runs unasked in ask, allowedit and auto, not in plan or manual`() {
        harness().execute {
            val gate = Gate(this)
            val decided = PermissionMode.entries.associate { mode ->
                gate.mode(mode)
                mode.id to gate.decide(contained())
            }

            assertEquals(
                mapOf(
                    "plan" to "plan",
                    "manual" to "ask",
                    "ask" to "sandbox",
                    "allowedit" to "sandbox",
                    "auto" to "sandbox",
                    "bypass" to "mode",
                ),
                decided,
            )
            assertEquals(emptyList(), gate.judge.calls)
        }
    }

    @Test
    fun `a contained call still asks for a deny rule, a floor ask place, variables or a run outside the sandbox`() {
        harness(permission = """{"deny": ["command.exec(rm)"]}""").execute {
            val gate = Gate(this)
            val variables = CommandSegment(listOf("make"), CWD, null, null, environment = mapOf("A" to "1"))

            assertEquals("refused", gate.decide(contained(argv = listOf("rm", "-rf", "build"))))
            assertEquals("ask", gate.decide(contained(person = "it reaches a place the floor asks a person about")))
            assertEquals("ask", gate.decide(contained(segments = listOf(variables))))
            assertEquals("ask", gate.decide(CallReview.builder().preview(command()).build()))
        }
    }

    @Test
    fun `a contained call of a guest, of an agent that does not auto-allow, or of a third-party tool asks`() {
        harness().execute {
            assertEquals("ask", Gate(this).decide(contained(), testTurn { agent(CODER) }))
            assertEquals("ask", Gate(this).decide(contained(), testTurn(TurnKind.HEARTBEAT) { agent(CODER) }))
            assertEquals("ask", Gate(this, thirdParty = true).decide(contained()))
        }
        harness("""{"type": "seatbelt", "autoAllow": false}""").execute {
            assertEquals("ask", Gate(this).decide(contained()))
        }
    }

    @Test
    fun `taint does not change the clearance of a contained call that reaches no host`() {
        harness().execute {
            assertEquals("sandbox", Gate(this).decide(contained(), tainted = true))
        }
    }

    @Test
    fun `under taint a contained call that may reach a host goes the mode's way, offering nothing for good`() {
        harness("""{"type": "seatbelt", "network": {"allowedDomains": ["pypi.org"]}}""").execute {
            val gate = Gate(this)

            assertEquals("sandbox", gate.decide(contained()))
            assertEquals("ask", gate.decide(contained(), tainted = true))
            gate.mode(PermissionMode.AUTO)
            assertEquals("ask", gate.decide(contained(), tainted = true))
            assertEquals(emptyList(), gate.judge.calls)
            val make = CommandSegment(listOf("make"), CWD, Path.of("/usr/bin/make"), null)
            assertEquals("ask", gate.decide(contained(segments = listOf(make)), tainted = true))
            assertEquals(1, gate.judge.calls.size)
        }
        harness().execute {
            val gate = Gate(this)

            assertEquals("ask", gate.decide(contained(hosts = listOf("example.com")), tainted = true))
            assertEquals(emptyList(), gate.approvals.offered)
        }
    }

    @Test
    fun `a contained call that reaches no host from a trusted folder keeps its programs' output trusted`() {
        val project = directory.resolve("project").createDirectories().toRealPath()
        val inProject = { review: CallReview ->
            review.rebuild { preview(ReviewPreview.Command(listOf("make"), project, Path.of("/usr/bin/make"), false)) }
        }
        harness(trusted = project).execute {
            val trust = assertIs<GateResult.Admitted>(Gate(this).admit(inProject(contained()))).trust
            val elsewhere = assertIs<GateResult.Admitted>(Gate(this).admit(contained())).trust

            assertEquals(CallTrust.PROGRAMS to CallTrust.NONE, trust to elsewhere)
        }
        harness("""{"type": "seatbelt", "network": {"allowedDomains": ["pypi.org"]}}""", trusted = project).execute {
            assertEquals(CallTrust.NONE, assertIs<GateResult.Admitted>(Gate(this).admit(inProject(contained()))).trust)
        }
    }

    @Test
    fun `extra hosts are asked for in ask mode, judged in auto, and always allowing grants the domain`() {
        harness("""{"type": "seatbelt", "network": {"allowedDomains": ["pypi.org"]}}""").execute {
            val gate = Gate(this)

            assertEquals("sandbox", gate.decide(contained(hosts = listOf("pypi.org"))))
            assertEquals("ask", gate.decide(contained(hosts = listOf("pypi.org", "example.com"))))
            assertEquals(listOf(listOf("network: example.com")), gate.approvals.offered)
            gate.mode(PermissionMode.AUTO)
            assertEquals("ask", gate.decide(contained(hosts = listOf("example.com"))))
            assertEquals(1, gate.judge.calls.size)
            gate.mode(PermissionMode.BYPASS)
            assertEquals("mode", gate.decide(contained(hosts = listOf("example.com"))))
        }
    }

    @Test
    fun `an always-allowed domain lets later calls of the session reach it unasked`() {
        harness().execute {
            val gate = Gate(this)
            val asking = contained(hosts = listOf("example.com"))
            val checked = CheckedCall(
                GateCall(
                    turn,
                    ToolCallPart(ToolCallId("c"), "command.exec", "{}"),
                    gate.exec,
                    ARGUMENTS,
                    5.seconds,
                    TurnTaint(turn.id, turn.key),
                    testToolContext(turn),
                    DirectCalls,
                ),
                asking,
                ToolRisk.EXEC,
                null,
                get<Sandboxes>().containment(turn, asking),
            )

            gate.rules.grant(checked, gate.rules.grantable(checked), operator)

            assertEquals(
                listOf("network: example.com"),
                gate.rules.inForce(CODER, session(turn)).map { it.subject.display },
            )
            assertEquals("sandbox", gate.decide(asking))
            assertEquals("ask", gate.decide(contained(hosts = listOf("api.example.com"))))
        }
    }

    private fun command(
        argv: List<String> = listOf("sh", "-c", "make && ./run"),
        segments: List<CommandSegment> = emptyList(),
    ) = ReviewPreview.Command(argv, CWD, Path.of("/bin/${argv.first()}"), opaque = segments.isEmpty(), segments)

    private fun contained(
        hosts: List<String> = emptyList(),
        argv: List<String> = listOf("sh", "-c", "make && ./run"),
        segments: List<CommandSegment> = emptyList(),
        person: String? = null,
    ) = CallReview.builder()
        .preview(command(argv, segments))
        .untrusted(true)
        .person(person)
        .contained(Contained("seatbelt", Boundary(listOf(CWD)), hosts))
        .build()

    /** command.exec, whose calls are reviewed as [review] says. */
    private class ScriptedExec : ReviewedTool {
        var review: CallReview = CallReview.builder().build()

        override val definition: ToolDefinition = testToolDefinition("command.exec", ToolRisk.EXEC)

        override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = review

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = ToolResult("Ran.")
    }

    /** Approvals that record what an approver would be offered for good, and refuse with [ASKED]. */
    private class Offering(private val rules: CallRules) : Approvals {
        val offered = CopyOnWriteArrayList<List<String>>()

        override suspend fun ask(call: CheckedCall, reason: String, note: String?, question: Question?): GateResult {
            rules.grantable(call).takeIf { it.isNotEmpty() }?.let { subjects -> offered += subjects.map { it.display } }
            return GateResult.Refused(ToolOutcome.NotRun(NotRunReason.DENIED), ASKED)
        }
    }

    /** A judge that records what it was asked and leaves each call to a person. */
    private class Judging : Judge {
        val calls = CopyOnWriteArrayList<CheckedCall>()

        override suspend fun judge(call: CheckedCall): Verdict {
            calls += call
            return Verdict.Ask("unsure")
        }

        override fun allowedByOperator(key: AgentChatKey, tool: String) = Unit
    }

    private companion object {
        val CODER = AgentId("coder")
        val CWD: Path = Path.of("/srv")
        val ARGUMENTS = JsonObject(emptyMap())
        const val ASKED = "Asked a person."
    }
}
