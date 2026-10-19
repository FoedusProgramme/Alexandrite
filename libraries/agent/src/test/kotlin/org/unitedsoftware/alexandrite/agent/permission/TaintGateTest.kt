package org.unitedsoftware.alexandrite.agent.permission

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.unitedsoftware.alexandrite.agent.DirectCalls
import org.unitedsoftware.alexandrite.agent.TestClock
import org.unitedsoftware.alexandrite.agent.agentDirectory
import org.unitedsoftware.alexandrite.agent.control.SettingsStates
import org.unitedsoftware.alexandrite.agent.hook.TurnHooks
import org.unitedsoftware.alexandrite.agent.settings
import org.unitedsoftware.alexandrite.agent.tool.GateCall
import org.unitedsoftware.alexandrite.agent.tool.GateResult
import org.unitedsoftware.alexandrite.agent.tool.ToolRegistry
import org.unitedsoftware.alexandrite.agent.trust.TurnTaint
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.chat.SettingChange
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
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import org.unitedsoftware.alexandrite.sdk.tool.Tool
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.ToolCallPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.turn.AllowRule
import org.unitedsoftware.alexandrite.sdk.turn.Revocation
import org.unitedsoftware.alexandrite.testkit.TestChatStates
import org.unitedsoftware.alexandrite.testkit.recordingTool
import org.unitedsoftware.alexandrite.testkit.testToolContext
import org.unitedsoftware.alexandrite.testkit.testToolDefinition
import org.unitedsoftware.alexandrite.testkit.testTurn
import org.unitedsoftware.alexandrite.testkit.testUser
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** How the gate decides the calls of a conversation that took in untrusted content. */
class TaintGateTest {
    private val clock = TestClock()
    private val states = SettingsStates(TestChatStates().of("alexandrite-agent"))
    private val operator: TurnInfo = testTurn { actor(testUser("operator", isOperator = true)) }
    private val judge = Judging()
    private val approvals = Asking()
    private val rules = Rules()

    /** A gate where `main` sets [permission], every call of [tools] is built in and [places] steer the agent. */
    private fun gate(
        tools: List<Tool>,
        permission: String = "{}",
        places: InstructionPlaces = Steering,
    ): PermissionGate {
        val directory = agentDirectory(
            settings("""{"agents": {"main": {"description": "Main.", "permission": $permission}}}"""),
        )
        val origins = object : PluginOrigins {
            override fun isBuiltIn(instance: Any): Boolean = true
        }
        return PermissionGate(
            TurnHooks(Proceeding),
            ToolRegistry(tools, emptyList(), directory, origins),
            rules,
            NoTrust,
            judge,
            approvals,
            states,
            directory,
            clock,
            NotContained,
            places,
            NoForesight,
            NoMarkOffers,
        )
    }

    /** How [gate] decided a call of [tool] in each mode: run, plan, ask or the clearance. */
    private suspend fun decided(gate: PermissionGate, tool: Tool, tainted: Boolean): List<String> =
        PermissionMode.entries.map { mode ->
            val until = (clock.now + Duration.ofHours(1)).takeIf { mode == PermissionMode.BYPASS }
            states.changeMode(operator.key, SettingChange.SetTo(mode), until)
            val taint = TurnTaint(operator.id, operator.key).apply { if (tainted) mark("a pasted page") }
            val call = ToolCallPart(ToolCallId("call-1"), tool.definition.name, "{}")
            val context = testToolContext(operator)
            when (
                val result =
                    gate.admit(
                        GateCall(operator, call, tool, JsonObject(emptyMap()), 5.seconds, taint, context, DirectCalls),
                    )
            ) {
                is GateResult.Admitted ->
                    if (result.clearance.kind == ClearanceKind.NONE) "run" else "${result.clearance.kind}".lowercase()

                is GateResult.Refused -> when (result.outcome) {
                    ToolOutcome.NotRun(NotRunReason.PLAN_MODE) -> "plan"
                    else -> "ask"
                }
            }
        }

    @Test
    fun `a write of an instruction place is cleared by no rule or allowedit, and needs a person under taint`() =
        runTest {
            val instructions = writing("fs.write", Path.of("/srv/AGENTS.md"))
            val notes = writing("fs.edit", Path.of("/srv/notes.md"))
            val gate = gate(listOf(instructions, notes))
            rules.allowed = true
            judge.verdict = Verdict.Allow

            //                       plan    manual  ask     allowedit auto     bypass
            assertEquals(listOf("plan", "ask", "ask", "ask", "judge", "mode"), decided(gate, instructions, false))
            val clean = approvals.asked.map { it.first }.toSet()
            approvals.asked.clear()
            assertEquals(listOf("plan", "ask", "ask", "ask", "ask", "ask"), decided(gate, instructions, true))
            val tainted = approvals.asked.map { it.first }.toSet()
            assertEquals(listOf("plan", "ask", "rule", "mode", "rule", "mode"), decided(gate, notes, true))
            assertEquals(1, judge.calls.size)
            assertEquals(setOf("the call writes a trusted folder's instruction file"), clean)
            assertEquals(
                setOf(
                    "the call writes a trusted folder's instruction file, and the conversation took in untrusted " +
                        "content",
                ),
                tainted,
            )
        }

    @Test
    fun `network reads of a tainted conversation run unless the agent sends them to the gate`() = runTest {
        val fetch = recordingTool("web.fetch", ToolRisk.NETWORK_READ)
        val gating = gate(listOf(fetch), """{"taintedNetworkReads": "gate"}""")
        judge.verdict = Verdict.Allow

        assertEquals(List(6) { "run" }, decided(gate(listOf(fetch)), fetch, true))
        assertEquals(List(6) { "run" }, decided(gating, fetch, false))
        //                     plan    manual  ask    allowedit auto     bypass
        assertEquals(listOf("plan", "ask", "ask", "ask", "judge", "mode"), decided(gating, fetch, true))
        assertEquals(
            List(3) { "the call reads the network, and the conversation took in untrusted content" },
            approvals.asked.map { it.first },
        )
    }

    @Test
    fun `the person asked in the judge's place reads its note apart from Alexandrite's reason`() = runTest {
        val write = recordingTool("fs.write", ToolRisk.WORKSPACE_WRITE)
        judge.verdict = Verdict.Ask("the judge is unsure", "The operator already allowed this earlier.")

        val auto = decided(gate(listOf(write)), write, false)[PermissionMode.entries.indexOf(PermissionMode.AUTO)]

        assertEquals("ask", auto)
        assertEquals(
            "the judge is unsure" to "The operator already allowed this earlier.",
            approvals.asked.single { it.second != null },
        )
    }

    /** A built-in tool that changes [path]. */
    private fun writing(name: String, path: Path): ReviewedTool = object : ReviewedTool {
        override val definition = testToolDefinition(name, ToolRisk.WORKSPACE_WRITE)

        override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview =
            CallReview.builder().preview(ReviewPreview.Diff(path, "+x\n")).build()

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult = ToolResult("Written.")
    }

    /** Instruction places: every `AGENTS.md`. */
    private object Steering : InstructionPlaces {
        override fun kind(path: Path, agent: AgentId): String? =
            "a trusted folder's instruction file".takeIf { path.fileName.toString() == "AGENTS.md" }

        override fun sandboxPaths(): List<Path> = emptyList()
    }

    private object Proceeding : Hooks {
        override suspend fun <P : Any> fire(point: InterceptorPoint<P>, payload: P): Interception<P> =
            Interception.Proceed(payload)

        override suspend fun <P : Any> fire(point: ObserverPoint<P>, payload: P) = Unit
    }

    private class Judging : Judge {
        val calls = CopyOnWriteArrayList<CheckedCall>()
        var verdict: Verdict = Verdict.Ask("unsure")

        override suspend fun judge(call: CheckedCall): Verdict {
            calls += call
            return verdict
        }

        override fun allowedByOperator(key: AgentChatKey, tool: String) = Unit
    }

    /** Approvals that record each reason and note, and refuse. */
    private class Asking : Approvals {
        val asked = CopyOnWriteArrayList<Pair<String, String?>>()

        override suspend fun ask(call: CheckedCall, reason: String, note: String?, question: Question?): GateResult {
            asked += reason to note
            return GateResult.Refused(ToolOutcome.NotRun(NotRunReason.DENIED), "Asked.")
        }
    }

    private class Rules : CallRules {
        var allowed = false

        override suspend fun denied(call: CheckedCall): String? = null

        override suspend fun allowed(call: CheckedCall): Boolean = allowed

        override suspend fun grantable(call: CheckedCall): List<RuleSubject> = emptyList()

        override suspend fun grant(call: CheckedCall, subjects: List<RuleSubject>, by: ChatUser) = Unit

        override fun inForce(agent: AgentId, conversation: ConversationId): List<AllowRule> = emptyList()

        override suspend fun revoke(agent: AgentId, conversation: ConversationId, number: Int, by: ChatUser?) =
            Revocation(removed = false, declared = false)
    }
}
