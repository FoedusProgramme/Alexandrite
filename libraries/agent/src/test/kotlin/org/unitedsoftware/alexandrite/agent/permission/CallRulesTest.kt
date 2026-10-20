package org.unitedsoftware.alexandrite.agent.permission

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.agent.DirectCalls
import org.unitedsoftware.alexandrite.agent.TestClock
import org.unitedsoftware.alexandrite.agent.agentDirectory
import org.unitedsoftware.alexandrite.agent.blocking
import org.unitedsoftware.alexandrite.agent.config.AgentDirectory
import org.unitedsoftware.alexandrite.agent.control.SettingsStates
import org.unitedsoftware.alexandrite.agent.hook.TurnHooks
import org.unitedsoftware.alexandrite.agent.logged
import org.unitedsoftware.alexandrite.agent.settings
import org.unitedsoftware.alexandrite.agent.tool.GateCall
import org.unitedsoftware.alexandrite.agent.tool.GateResult
import org.unitedsoftware.alexandrite.agent.tool.ToolRegistry
import org.unitedsoftware.alexandrite.agent.trust.TurnTaint
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.SettingChange
import org.unitedsoftware.alexandrite.sdk.chat.ToolCallId
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.hook.Hooks
import org.unitedsoftware.alexandrite.sdk.hook.Interception
import org.unitedsoftware.alexandrite.sdk.hook.InterceptorPoint
import org.unitedsoftware.alexandrite.sdk.hook.ObserverPoint
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.permission.PermissionMode
import org.unitedsoftware.alexandrite.sdk.plugin.PluginOrigins
import org.unitedsoftware.alexandrite.sdk.store.collection
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.turn.AllowRule
import org.unitedsoftware.alexandrite.sdk.turn.Revocation
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.TEST_TIME
import org.unitedsoftware.alexandrite.testkit.TestChatStates
import org.unitedsoftware.alexandrite.testkit.TestRecords
import org.unitedsoftware.alexandrite.testkit.recordingTool
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testRecords
import org.unitedsoftware.alexandrite.testkit.testToolContext
import org.unitedsoftware.alexandrite.testkit.testToolDefinition
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUser
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class CallRulesTest {
    private val clock = TestClock()
    private val store = MemoryStore(clock)
    private val records = TestRecords(store.records).of(AGENT)
    private val states = SettingsStates(TestChatStates(store.chatStates).of(AGENT), testRecords())
    private val key = AgentChatKey(AgentId.MAIN, testChat())
    private val operator = testUser("operator", isOperator = true)
    private val exec = ScriptedTool("command.exec", ToolRisk.EXEC)
    private val deploy = ScriptedTool("deploy.run", ToolRisk.EXEC)
    private val notes = ScriptedTool("notes.keep", ToolRisk.PERSISTENT_STATE)
    private val read = recordingTool("fs.read")

    /** The rules and gate of agent `main` with the permission section [permission]. */
    private inner class Setup(permission: String = "{}") {
        val directory: AgentDirectory =
            agentDirectory(settings("""{"agents": {"main": {"description": "Main.", "permission": $permission}}}"""))
        val registry = ToolRegistry(listOf(exec, deploy, notes, read), emptyList(), directory, NO_BUILT_INS)
        val rules = AgentCallRules(directory, registry, records, store.conversations, states, clock)
        val approvals = OfferingApprovals(rules)
        val gate =
            PermissionGate(
                TurnHooks(
                    Proceeding,
                ),
                registry, rules, NoTrust, UnsureJudge, approvals, states, directory, clock, NotContained,
                NoInstructionPlaces,
                NoForesight,
                NoMarkOffers,
            )

        /** How the gate decided a call of [tool] reviewed as [review] in [turn]: run, rule, mode, ask or refused. */
        suspend fun decide(tool: Tool, review: CallReview, turn: TurnInfo, tainted: Boolean = false): String {
            (tool as? ScriptedTool)?.review = review
            val taint = TurnTaint(turn.id, turn.key).apply { if (tainted) mark("a test") }
            val call = ToolCallPart(ToolCallId("call-1"), tool.definition.name, "{}")
            val context = testToolContext(turn)
            val admitted = gate.admit(
                GateCall(turn, call, tool, JsonObject(emptyMap()), 5.seconds, taint, context, DirectCalls),
            )
            return when (val result = admitted) {
                is GateResult.Admitted ->
                    if (result.clearance.kind == ClearanceKind.NONE) "run" else "${result.clearance.kind}"

                is GateResult.Refused -> if (result.text == ASKED) "ask" else "refused ${result.text}"
            }
        }
    }

    /** The allow rules the agent keeps in [store], as JSON. */
    private suspend fun storedRules() =
        TestRecords(store.records).of(AGENT).collection<JsonObject>("allow_rules").list()

    private suspend fun conversation(): ConversationId = store.conversations.current(key).id

    private suspend fun operator(): TurnInfo {
        val conversation = conversation()
        return testTurn(conversation = conversation) { actor(operator) }
    }

    private suspend fun guest(): TurnInfo {
        val conversation = conversation()
        return testTurn(conversation = conversation)
    }

    private suspend fun automation(): TurnInfo {
        val conversation = conversation()
        return testTurn(TurnKind.HEARTBEAT, conversation = conversation)
    }

    private suspend fun setMode(mode: PermissionMode) {
        val until = (clock.now + Duration.ofHours(1)).takeIf { mode == PermissionMode.BYPASS }
        states.changeMode(key, SettingChange.SetTo(mode), until)
    }

    /** [tool]'s call in [turn], reviewed as [review], as the gate checks it. */
    private fun checked(
        turn: TurnInfo,
        tool: ScriptedTool,
        review: CallReview,
        risk: ToolRisk = tool.risk,
        tainted: Boolean = false,
        instruction: String? = null,
    ) = CheckedCall(
        GateCall(
            turn,
            ToolCallPart(ToolCallId("call-1"), tool.definition.name, "{}"),
            tool,
            JsonObject(emptyMap()),
            5.seconds,
            TurnTaint(turn.id, turn.key).apply { if (tainted) note("a pasted page") },
            testToolContext(turn),
            DirectCalls,
        ),
        review,
        risk,
        null,
        instruction = instruction,
    )

    // Deny rules.

    @Test
    fun `a deny rule refuses the calls it covers for every principal in every mode, bypass included`() = runTest {
        val setup = Setup("""{"deny": ["command.exec(git push)"]}""")
        val push = run("git", "push", "origin")
        val status = run("git", "status")
        val denied = "refused [alexandrite:denied by=policy] Not run: the operator's rule command.exec(git push) " +
            "denies it. Do not retry this or an equivalent call; tell the user."

        for (mode in PermissionMode.entries) {
            setMode(mode)
            for (turn in listOf(operator(), guest(), automation())) {
                val decided = listOf(setup.decide(exec, push, turn), setup.decide(exec, status, turn))

                assertEquals(denied, decided[0], "$mode ${turn.kind} ${turn.actor}")
                assertEquals(false, "operator's rule" in decided[1], "$mode ${turn.kind} ${turn.actor}")
            }
        }
    }

    @Test
    fun `a deny rule of a whole tool refuses its read-only calls, and a prefix the program's real path`() = runTest {
        val setup = Setup("""{"deny": ["fs.read", "command.exec(git push)"]}""")
        setMode(PermissionMode.BYPASS)
        val turn = operator()

        val decided = listOf(
            setup.decide(read, CallReview.builder().build(), turn),
            setup.decide(exec, run("/usr/bin/git", "push", executable = "/usr/bin/git"), turn),
            setup.decide(exec, run("/usr/bin/git", "pull", executable = "/usr/bin/git"), turn),
        )

        assertEquals(
            listOf(
                "refused [alexandrite:denied by=policy] Not run: the operator's rule fs.read denies it. Do not retry " +
                    "this or an equivalent call; tell the user.",
                "refused [alexandrite:denied by=policy] Not run: the operator's rule command.exec(git push) denies " +
                    "it. Do not retry this or an equivalent call; tell the user.",
                "mode",
            ),
            decided,
        )
    }

    // Allow rules.

    @Test
    fun `a declared prefix allows an operator's matching calls in ask, allowedit and auto, never in manual`() =
        runTest {
            val setup = Setup("""{"allow": ["command.exec(npm test)"]}""")
            val test = run("npm", "test", "--info")
            val build = run("npm", "build")

            val decided = listOf("manual", "ask", "allowedit", "auto").map { mode ->
                setMode(PermissionMode.of(mode))
                setup.decide(exec, test, operator()) + " " + setup.decide(exec, build, operator())
            } + setup.decide(exec, test, guest())

            assertEquals(listOf("ask ask", "rule ask", "rule ask", "rule ask", "ask"), decided)
        }

    @Test
    fun `a declared prefix allows a program only as the entry names it, while a deny also catches its real path`() =
        runTest {
            val setup = Setup("""{"allow": ["command.exec(git status)"], "deny": ["command.exec(git push)"]}""")
            val turn = operator()

            val decided = listOf(
                setup.decide(exec, run("git", "status", executable = "/usr/bin/git"), turn),
                setup.decide(exec, run("./git", "status", executable = "/srv/repo/git"), turn),
                setup.decide(exec, run("/usr/bin/git", "status", executable = "/usr/bin/git"), turn),
                setup.decide(exec, run("/usr/bin/git", "push", executable = "/usr/bin/git"), turn),
            )

            assertEquals(
                listOf(
                    "rule",
                    "ask",
                    "ask",
                    "refused [alexandrite:denied by=policy] Not run: the operator's rule command.exec(git push) " +
                        "denies it. Do not retry this or an equivalent call; tell the user.",
                ),
                decided,
            )
            assertEquals(
                listOf(RuleSubject("command.exec", listOf("/srv/repo/git", "status"), "/srv/repo/git status")),
                setup.approvals.offered.take(1),
            )
        }

    @Test
    fun `a declared tool allows its calls, those that offer no subject too, never an opaque program`() = runTest {
        val setup = Setup("""{"allow": ["deploy.run", "command.exec"]}""")
        val subject = RuleSubject("deploy.run", listOf("staging"), "staging")
        val opaque = CallReview.builder()
            .preview(ReviewPreview.Command(listOf("sh", "-c", "make"), CWD, null, true))
            .build()
        val turn = operator()

        val decided = listOf(
            setup.decide(deploy, CallReview.builder().rule(subject).build(), turn),
            setup.decide(deploy, CallReview.builder().build(), turn),
            setup.decide(exec, run("make", "install"), turn),
            setup.decide(exec, opaque, turn),
            setup.decide(exec, opaque.withRule(RuleSubject("command.exec", listOf("sh"), "sh")), turn),
        )

        assertEquals(listOf("rule", "rule", "rule", "ask", "ask"), decided)
        assertEquals(listOf(null, null), setup.approvals.offered.takeLast(2))
    }

    @Test
    fun `no rule allows a call of persistent state, and none is offered for good`() = runTest {
        val setup = Setup("""{"allow": ["notes.keep"]}""")
        val subject = RuleSubject("notes.keep", listOf("todo"), "todo")
        val turn = operator()

        val decided = setup.decide(notes, CallReview.builder().rule(subject).build(), turn, tainted = true)
        val lowered = checked(turn, notes, CallReview.builder().rule(subject).build(), ToolRisk.EXEC)

        assertEquals("ask", decided)
        assertEquals(listOf<RuleSubject?>(null), setup.approvals.offered)
        assertEquals(listOf(subject), setup.rules.grantable(lowered))
    }

    @Test
    fun `an approver is offered the call's subject on an operator's turn outside manual mode only`() = runTest {
        val rules = Setup().rules
        val subject = RuleSubject("command.exec", listOf("make", "test"), "make test")
        val review = run("make", "test")
        val foreign = run("make", "test").withRule(RuleSubject("deploy.run", listOf("make"), "make"))

        val offered = listOf(
            rules.grantable(checked(operator(), exec, review)),
            rules.grantable(checked(guest(), exec, review)),
            rules.grantable(checked(automation(), exec, review)),
            rules.grantable(checked(operator(), exec, foreign)),
            rules.grantable(checked(operator(), exec, CallReview.builder().build())),
        ).also { setMode(PermissionMode.MANUAL) } + listOf(rules.grantable(checked(operator(), exec, review)))

        assertEquals(listOf(listOf(subject)) + List(5) { emptyList() }, offered)
    }

    @Test
    fun `nothing is offered for good in a tainted conversation or for a write of an instruction place`() = runTest {
        val rules = Setup("""{"allow": ["command.exec(make)"]}""").rules
        val review = run("make", "test")

        val offered = listOf(
            rules.grantable(checked(operator(), exec, review, tainted = true)),
            rules.grantable(checked(operator(), exec, review, instruction = "a trusted folder's instruction file")),
        )

        assertEquals(List(2) { emptyList() }, offered)
        val allowed = listOf(null, "a skill directory").map {
            rules.allowed(checked(operator(), exec, review, instruction = it))
        }
        assertEquals(listOf(true, false), allowed)
    }

    // Session rules.

    @Test
    fun `a granted rule allows calls of the same subject in its conversation only`() = runTest {
        val setup = Setup()
        val test = run("make", "test")
        val elsewhere = store.conversations.current(AgentChatKey(AgentId.MAIN, testChat("other"))).id
        val other = testTurn(chat = testChat("other"), conversation = elsewhere) { actor(operator) }
        val before = setup.decide(exec, test, operator())

        setup.rules.grant(checked(operator(), exec, test), listOf(test.rule!!), operator)

        assertEquals(
            listOf("ask", "rule", "ask", "ask", "ask"),
            listOf(
                before,
                setup.decide(exec, run("make", "test", "--verbose"), operator()),
                setup.decide(exec, run("make", "clean"), operator()),
                setup.decide(exec, test, other),
                setup.decide(exec, test, guest()),
            ),
        )
        val record = storedRules().single()
        assertEquals(
            listOf("${conversation()}", "command.exec", "[\"make\",\"test\"]", "make test", operator.address),
            listOf(
                record.value["conversation"].content(),
                record.value["tool"].content(),
                "${record.value["tokens"]}",
                record.value["display"].content(),
                record.createdBy,
            ),
        )
    }

    @Test
    fun `a rule is granted only for what the call offers`() {
        val rules = Setup().rules
        val test = run("make", "test")

        val lines = logged {
            blocking {
                setMode(PermissionMode.MANUAL)
                rules.grant(checked(operator(), exec, test), listOf(test.rule!!), operator)
                setMode(PermissionMode.ASK)
                rules.grant(
                    checked(operator(), exec, test),
                    listOf(RuleSubject("command.exec", listOf("make"), "make")),
                    operator,
                )
            }
        }

        assertEquals(emptyList(), blocking { storedRules() })
        assertEquals(
            listOf(
                "WARN Call call-1 in turn test-turn offers no rule 'make test' to grant, so none is granted",
                "WARN Call call-1 in turn test-turn offers no rule 'make' to grant, so none is granted",
            ),
            lines.filter { it.startsWith("WARN") },
        )
    }

    @Test
    fun `the rules of a sealed conversation end, and a restart keeps those of the current one`() = runTest {
        val first = Setup()
        val test = run("make", "test")
        val lint = run("make", "lint")
        val sealed = conversation()
        first.rules.grant(checked(operator(), exec, test), listOf(test.rule!!), operator)
        store.conversations.newConversation(key)
        first.rules.sealed(sealed)
        val sealedRules = first.rules.inForce(AgentId.MAIN, sealed)
        val stillCurrent = conversation()
        first.rules.grant(checked(operator(), exec, lint), listOf(lint.rule!!), operator)
        store.conversations.newConversation(key)
        first.rules.grant(checked(operator(), exec, test), listOf(test.rule!!), operator)
        val restarted = Setup()

        restarted.rules.onStart()

        assertEquals(emptyList(), sealedRules)
        assertEquals(emptyList(), restarted.rules.inForce(AgentId.MAIN, stillCurrent))
        assertEquals(
            listOf("rule", "ask"),
            listOf(restarted.decide(exec, test, operator()), restarted.decide(exec, lint, operator())),
        )
        assertEquals(
            listOf("${conversation()}"),
            storedRules().map { it.value["conversation"].content() },
        )
    }

    // Listing and revoking.

    @Test
    fun `the rules in force are the declared ones, then the conversation's with who and when`() = runTest {
        val setup = Setup("""{"allow": ["command.exec(git status)", "deploy.run"]}""")
        val sneaky = RuleSubject("command.exec", listOf("cat", "a‮b"), "cat a‮b")
        setup.rules.grant(checked(operator(), exec, run("cat", "a‮b").withRule(sneaky)), listOf(sneaky), operator)

        assertEquals(
            listOf(
                AllowRule(1, RuleSubject("command.exec", listOf("git", "status"), "git status"), true, null, null),
                AllowRule(2, RuleSubject("deploy.run", emptyList(), "deploy.run"), true, null, null),
                AllowRule(
                    3,
                    RuleSubject("command.exec", listOf("cat", "a‮b"), "cat a\\u202Eb"),
                    false,
                    TEST_TIME,
                    operator,
                ),
            ),
            setup.rules.inForce(AgentId.MAIN, conversation()),
        )
    }

    @Test
    fun `a granted rule is revoked, and a declared one is not, saying whether the config still allows its calls`() =
        runTest {
            val setup = Setup("""{"allow": ["command.exec(git status)"]}""")
            val status = run("/usr/bin/git", "status", executable = "/usr/bin/git")
                .withRule(RuleSubject("command.exec", listOf("git", "status"), "git status"))
            val test = run("make", "test")
            setup.rules.grant(checked(operator(), exec, test), listOf(test.rule!!), operator)
            setup.rules.grant(checked(operator(), exec, status), listOf(status.rule!!), operator)

            val revoked = listOf(
                setup.rules.revoke(AgentId.MAIN, conversation(), 1, operator),
                setup.rules.revoke(AgentId.MAIN, conversation(), 3, operator),
                setup.rules.revoke(AgentId.MAIN, conversation(), 2, operator),
                setup.rules.revoke(AgentId.MAIN, conversation(), 2, operator),
                setup.rules.revoke(AgentId.MAIN, conversation(), 0, operator),
            )

            assertEquals(
                listOf(
                    Revocation(removed = false, declared = true),
                    Revocation(removed = true, declared = true),
                    Revocation(removed = true, declared = false),
                    Revocation(removed = false, declared = false),
                    Revocation(removed = false, declared = false),
                ),
                revoked,
            )
            assertEquals(listOf(1), setup.rules.inForce(AgentId.MAIN, conversation()).map { it.number })
            assertEquals("ask", setup.decide(exec, test, operator()))
        }

    @Test
    fun `a rule that names a tool no plugin contributes is warned about`() {
        val lines = logged { Setup("""{"allow": ["deploy.run", "web.fetch"], "deny": ["command.exec(rm)"]}""") }

        assertEquals(
            listOf(
                "WARN The permission rule web.fetch of agent 'main' names a tool that no plugin contributes",
            ),
            lines.filter { it.startsWith("WARN") },
        )
    }

    private fun run(vararg argv: String, executable: String? = null): CallReview {
        val program = if ('/' in argv[0]) executable ?: argv[0] else argv[0]
        val tokens = listOfNotNull(program, argv.drop(1).firstOrNull { !it.startsWith("-") })
        return CallReview.builder()
            .preview(ReviewPreview.Command(argv.toList(), CWD, executable?.let(Path::of), false))
            .rule(RuleSubject("command.exec", tokens, tokens.joinToString(" ")))
            .build()
    }

    private fun CallReview.withRule(rule: RuleSubject): CallReview = CallReview.builder()
        .risk(risk)
        .refusal(refusal)
        .preview(preview)
        .snapshot(snapshot)
        .rule(rule)
        .problem(problem)
        .build()

    /** A tool whose calls are reviewed as [review] says. */
    private class ScriptedTool(name: String, val risk: ToolRisk) : ReviewedTool {
        var review: CallReview = CallReview.builder().build()

        override val definition: ToolDefinition = testToolDefinition(name, risk)

        override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview = review

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = ToolResult("Ran.")
    }

    /** Approvals that record what an approver would be offered for good, and refuse with [ASKED]. */
    private class OfferingApprovals(private val rules: CallRules) : Approvals {
        val offered = mutableListOf<RuleSubject?>()

        override suspend fun ask(call: CheckedCall, reason: String, note: String?, question: Question?): GateResult {
            offered += rules.grantable(call).firstOrNull()
            return GateResult.Refused(ToolOutcome.NotRun(NotRunReason.DENIED), ASKED)
        }
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

private fun JsonElement?.content(): String = (this as JsonPrimitive).content

/** A judge that leaves every call to a person. */
private object UnsureJudge : Judge {
    override suspend fun judge(call: CheckedCall): Verdict = Verdict.Ask("unsure")

    override fun allowedByOperator(key: AgentChatKey, tool: String) = Unit
}
