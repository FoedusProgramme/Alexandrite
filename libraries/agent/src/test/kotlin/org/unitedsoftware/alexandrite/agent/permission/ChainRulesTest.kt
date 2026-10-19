package org.unitedsoftware.alexandrite.agent.permission

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.agent.DirectCalls
import org.unitedsoftware.alexandrite.agent.TestClock
import org.unitedsoftware.alexandrite.agent.agentDirectory
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.control.SettingsStates
import org.unitedsoftware.alexandrite.agent.hook.TurnHooks
import org.unitedsoftware.alexandrite.agent.settings
import org.unitedsoftware.alexandrite.agent.tool.GateCall
import org.unitedsoftware.alexandrite.agent.tool.GateResult
import org.unitedsoftware.alexandrite.agent.tool.ToolRegistry
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.hook.Hooks
import org.unitedsoftware.alexandrite.sdk.hook.Interception
import org.unitedsoftware.alexandrite.sdk.hook.InterceptorPoint
import org.unitedsoftware.alexandrite.sdk.hook.ObserverPoint
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.plugin.PluginOrigins
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.CommandSegment
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import org.unitedsoftware.alexandrite.sdk.tool.SegmentJoint
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.turn.SettingChange
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.TestChatStates
import org.unitedsoftware.alexandrite.testkit.TestRecords
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testToolContext
import org.unitedsoftware.alexandrite.testkit.testToolDefinition
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUser
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** How the rules, the trust rules and the judge see a call of command.exec that runs several programs. */
class ChainRulesTest {
    private val clock = TestClock()
    private val store = MemoryStore(clock)
    private val records = TestRecords(store.records).of(AGENT)
    private val states = SettingsStates(TestChatStates(store.chatStates).of(AGENT))
    private val key = AgentChatKey(AgentId.MAIN, testChat())
    private val operator = testUser("operator", isOperator = true)
    private val exec = ScriptedExec()

    /** The rules, trust rules and gate of agent `main` with the permission section [permission]. */
    private inner class Setup(permission: String = "{}") {
        val directory: AgentDirectory =
            agentDirectory(settings("""{"agents": {"main": {"description": "Main.", "permission": $permission}}}"""))
        val registry = ToolRegistry(listOf(exec), emptyList(), directory, NO_BUILT_INS)
        val rules = AgentCallRules(directory, registry, records, store.conversations, states, clock)
        val trusts = AgentTrustRules(directory, registry, records, store.conversations)
        val judge = Judging()
        val approvals = Offering(rules)
        val gate =
            PermissionGate(TurnHooks(Proceeding), registry, rules, trusts, judge, approvals, states, directory, clock)

        /** How the gate decided [review] on an operator's turn, tainted when [tainted]: run, rule, judge, ask or refused. */
        suspend fun decide(review: CallReview, tainted: Boolean = false): String {
            exec.review = review
            val turn = operatorTurn()
            val taint = TurnTaint(turn.id, turn.key).apply { if (tainted) mark("a test") }
            val call = ToolCallPart(ToolCallId("call-1"), "command.exec", "{}")
            val admitted = gate.admit(
                GateCall(
                    turn,
                    call,
                    exec,
                    JsonObject(emptyMap()),
                    5.seconds,
                    taint,
                    testToolContext(turn),
                    DirectCalls,
                ),
            )
            return when (admitted) {
                is GateResult.Admitted ->
                    if (admitted.clearance.kind == ClearanceKind.NONE) "run" else "${admitted.clearance.kind}"

                is GateResult.Refused -> if (admitted.text == ASKED) "ask" else "refused"
            }
        }

        fun checked(review: CallReview): CheckedCall {
            val turn = operatorTurn()
            return CheckedCall(
                GateCall(
                    turn,
                    ToolCallPart(ToolCallId("call-1"), "command.exec", "{}"),
                    exec,
                    JsonObject(emptyMap()),
                    5.seconds,
                    TurnTaint(turn.id, turn.key),
                    testToolContext(turn),
                    DirectCalls,
                ),
                review,
                ToolRisk.EXEC,
                null,
            )
        }
    }

    private val conversation = blocking { store.conversations.current(key).id }

    private fun operatorTurn(): TurnInfo = testTurn { actor(operator).conversation(conversation) }

    private suspend fun setMode(mode: PermissionMode) {
        states.changeMode(key, SettingChange.SetTo(mode), null)
    }

    // Rules.

    @Test
    fun `a deny rule that covers one step refuses the whole chain`() = runTest {
        val setup = Setup("""{"deny": ["command.exec(rm)"], "allow": ["command.exec(git)"]}""")

        assertEquals("refused", setup.decide(chain(step("git", "status"), step("rm", "-rf", "build"))))
        assertEquals("ask", setup.decide(chain(step("git", "status"), step("make"))))
    }

    @Test
    fun `allow rules clear a chain only when they cover every step that does not only read`() = runTest {
        val setup = Setup("""{"allow": ["command.exec(git status)", "command.exec(make)", "command.exec(sh)"]}""")
        val covered = chain(cd(), step("git", "status"), step("ls", readOnly = true), step("make"))
        val partly = chain(step("git", "status"), step("npm", "test"))

        assertEquals("rule", setup.decide(covered))
        assertEquals("ask", setup.decide(partly))
        assertEquals(listOf(listOf("npm test")), setup.approvals.offered)
        assertEquals("ask", setup.decide(chain(step("make", environment = mapOf("CC" to "cc")))))
        assertEquals(1, setup.approvals.offered.size)
    }

    @Test
    fun `always allowing a chain grants one rule per program not yet covered, never the chain itself`() = runTest {
        val setup = Setup("""{"allow": ["command.exec(git status)"]}""")
        val review = chain(step("git", "status"), step("npm", "test"), step("make"), step("npm", "test"))
        val call = setup.checked(review)

        val offered = setup.rules.grantable(call)
        setup.rules.grant(call, offered, operator)

        assertEquals(listOf("npm test", "make"), offered.map { it.display })
        assertEquals(
            listOf("git status", "npm test", "make"),
            setup.rules.inForce(AgentId.MAIN, conversation).map { it.subject.display },
        )
        assertEquals("rule", setup.decide(review))
    }

    // Trust.

    @Test
    fun `trust rules on every step trust the whole chain, on every unvetted step only its programs`() = runTest {
        val setup = Setup("""{"trust": ["command.exec(git)", "command.exec(./gradlew)"]}""")

        val trusts = listOf(
            chain(cd(), step("git", "status"), step("./gradlew", "test")),
            chain(step("git", "log"), step("ls", readOnly = true, untrusted = false)),
            chain(step("git", "log"), step("make")),
            CallReview(preview = command("git", "log"), untrusted = true),
            CallReview(preview = command("make"), untrusted = true),
        ).map { setup.trusts.trust(setup.checked(it)) }

        assertEquals(
            listOf(CallTrust.WHOLE, CallTrust.PROGRAMS, CallTrust.NONE, CallTrust.WHOLE, CallTrust.NONE),
            trusts,
        )
    }

    // The judge.

    @Test
    fun `in auto mode a tainted conversation's chain of plain programs goes to the judge, an opaque one to a person`() =
        runTest {
            val setup = Setup()
            setMode(PermissionMode.AUTO)
            val opaque = CallReview(
                preview = ReviewPreview.Command(listOf("sh", "-c", "ls | wc"), CWD, null, opaque = true),
            )

            assertEquals("ask", setup.decide(chain(cd(), step("git", "status"), step("make")), tainted = true))
            assertEquals(1, setup.judge.calls.size)
            assertEquals("ask", setup.decide(opaque, tainted = true))
            assertEquals(1, setup.judge.calls.size)
        }

    private fun chain(vararg steps: CommandSegment): CallReview {
        val joined = steps.withIndex().map { (index, step) ->
            CommandSegment(
                step.argv,
                step.cwd,
                step.executable,
                if (index == 0) null else SegmentJoint.AND,
                step.opaque,
                step.changesDirectory,
                step.readOnly,
                step.untrusted,
                step.environment,
                step.rule,
            )
        }
        val script = joined.joinToString(" && ") { it.argv.joinToString(" ") }
        return CallReview(
            preview = ReviewPreview.Command(listOf("sh", "-c", script), CWD, null, false, joined),
            untrusted = joined.any { it.untrusted },
        )
    }

    private fun cd(): CommandSegment =
        CommandSegment(listOf("cd", "sub"), CWD.resolve("sub"), null, null, changesDirectory = true, readOnly = true)

    private fun step(
        vararg argv: String,
        readOnly: Boolean = false,
        untrusted: Boolean = !readOnly,
        environment: Map<String, String> = emptyMap(),
    ): CommandSegment {
        val tokens = argv.take(2)
        val rule = RuleSubject("command.exec", tokens, tokens.joinToString(" ")).takeIf { environment.isEmpty() }
        return CommandSegment(
            argv.toList(),
            CWD,
            Path.of("/usr/bin/${argv[0]}"),
            null,
            readOnly = readOnly,
            untrusted = untrusted,
            environment = environment,
            rule = rule,
        )
    }

    private fun command(vararg argv: String): ReviewPreview.Command =
        ReviewPreview.Command(argv.toList(), CWD, Path.of("/usr/bin/${argv[0]}"), false)

    /** command.exec, whose calls are reviewed as [review] says. */
    private class ScriptedExec : ReviewedTool {
        var review: CallReview = CallReview()

        override val definition: ToolDefinition = testToolDefinition("command.exec", ToolRisk.EXEC)

        override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = review

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = ToolResult("Ran.")
    }

    /** Approvals that record the subjects an approver would be offered for good, and refuse with [ASKED]. */
    private class Offering(private val rules: CallRules) : Approvals {
        val offered = CopyOnWriteArrayList<List<String>>()

        override suspend fun ask(call: CheckedCall, reason: String): GateResult {
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

    private object Proceeding : Hooks {
        override suspend fun <P : Any> fire(point: InterceptorPoint<P>, payload: P): Interception<P> =
            Interception.Proceed(payload)

        override suspend fun <P : Any> fire(point: ObserverPoint<P>, payload: P) = Unit
    }

    private companion object {
        const val AGENT = "alexandrite-agent"
        const val ASKED = "Asked a person."
        val CWD: Path = Path.of("/srv")
        val NO_BUILT_INS = object : PluginOrigins {
            override fun isBuiltIn(instance: Any): Boolean = false
        }
    }
}
