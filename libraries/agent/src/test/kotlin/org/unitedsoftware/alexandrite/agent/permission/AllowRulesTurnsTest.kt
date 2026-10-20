package org.unitedsoftware.alexandrite.agent.permission

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.unitedsoftware.alexandrite.agent.agentHarness
import org.unitedsoftware.alexandrite.agent.execute
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.chat.AgentChatKey
import org.unitedsoftware.alexandrite.sdk.permission.ClearanceKind
import org.unitedsoftware.alexandrite.sdk.tool.CallReview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewPreview
import org.unitedsoftware.alexandrite.sdk.tool.ReviewedTool
import org.unitedsoftware.alexandrite.sdk.tool.RuleSubject
import org.unitedsoftware.alexandrite.sdk.tool.ToolContext
import org.unitedsoftware.alexandrite.sdk.tool.ToolDefinition
import org.unitedsoftware.alexandrite.sdk.tool.ToolResult
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.transcript.NotRunReason
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart
import org.unitedsoftware.alexandrite.sdk.transcript.ToolOutcome
import org.unitedsoftware.alexandrite.sdk.transcript.ToolResultEntry
import org.unitedsoftware.alexandrite.sdk.turn.Admission
import org.unitedsoftware.alexandrite.sdk.turn.AllowRule
import org.unitedsoftware.alexandrite.sdk.turn.ConversationControl
import org.unitedsoftware.alexandrite.sdk.turn.PermissionRules
import org.unitedsoftware.alexandrite.sdk.turn.Revocation
import org.unitedsoftware.alexandrite.testkit.MemoryStore
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.ScriptedModel
import org.unitedsoftware.alexandrite.testkit.testToolDefinition
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AllowRulesTurnsTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val exec = FakeExec()
    private val test = RuleSubject("command.exec", listOf("make", "test"), "make test")

    private fun harness(permission: String = "{}"): PluginHarness = agentHarness(
        """
        {
          "previewIntervalMillis": 0,
          "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}, "permission": $permission}}
        }
        """.trimIndent(),
        store,
    ) { channel(operators = setOf("operator")).model(model).tool(exec) }

    /** Has the operator ask in [chat] for a turn that runs [argv] and then answers. */
    private suspend fun PluginHarness.Running.run(vararg argv: String, chat: String = "chat") {
        val arguments = JsonObject(mapOf("argv" to JsonArray(argv.map(::JsonPrimitive))))
        model.reply { toolCall("command.exec", "$arguments") }.reply { text("Done.") }
        val turn = channel().receive("Run it.", channel().chat(chat), channel().user("operator")) as Admission.Accepted
        turn.ticket.outcome()
    }

    private suspend fun results(chat: String = "chat"): List<Pair<ToolOutcome, String>> {
        val conversation = store.conversations.current(AgentChatKey.parse("coder@test:main:$chat")).id
        return store.transcripts.entries(conversation).filterIsInstance<ToolResultEntry>()
            .map { result -> result.outcome to result.content.joinToString("") { (it as TextPart).text } }
    }

    @Test
    fun `always allow grants a rule, so the same call runs unasked while another subcommand asks`() {
        harness().execute {
            val operator = channel().user("operator")
            channel().scriptApprovals(ApprovalAnswer.Allowed(operator, always = true), ApprovalAnswer.Denied(operator))
            run("make", "test")
            run("make", "test", "--verbose")
            run("make", "clean")

            val (first, second) = channel().approvals
            assertEquals(test, first.always)
            assertEquals(RuleSubject("command.exec", listOf("make", "clean"), "make clean"), second.always)
            assertEquals(
                listOf(
                    ToolOutcome.Succeeded to "Ran make test.",
                    ToolOutcome.Succeeded to "Ran make test --verbose.",
                    ToolOutcome.NotRun(NotRunReason.DENIED, second.id) to Refusals.DENIED_BY_OPERATOR,
                ),
                results(),
            )
            assertEquals(listOf(ClearanceKind.HUMAN, ClearanceKind.RULE), exec.cleared.toList())
            val rule = get<PermissionRules>().allowRules(channel().chat()).single()
            assertEquals(
                listOf(1, test, false, operator),
                listOf(rule.number, rule.subject, rule.declared, rule.grantedBy),
            )
        }
    }

    @Test
    fun `new ends the conversation's rules, and another chat never sees them`() {
        harness().execute {
            val operator = channel().user("operator")
            channel().scriptApprovals(
                ApprovalAnswer.Allowed(operator, always = true),
                ApprovalAnswer.Denied(operator),
                ApprovalAnswer.Denied(operator),
            )
            run("make", "test", chat = "other")
            run("make", "test")
            get<ConversationControl>().newConversation(channel().chat("other"), operator)
            run("make", "test", chat = "other")

            assertEquals(3, channel().approvals.size)
            assertEquals(listOf(ClearanceKind.HUMAN), exec.cleared.toList())
            assertEquals(emptyList(), get<PermissionRules>().allowRules(channel().chat("other")))
            assertEquals(emptyList(), get<PermissionRules>().allowRules(channel().chat()))
        }
    }

    @Test
    fun `a restart keeps the rules of the current conversation`() {
        harness().execute {
            channel().scriptApprovals(ApprovalAnswer.Allowed(channel().user("operator"), always = true))
            run("make", "test")
        }

        harness().execute {
            run("make", "test")

            assertEquals(emptyList(), channel().approvals)
        }
        assertEquals(listOf(ClearanceKind.HUMAN, ClearanceKind.RULE), exec.cleared.toList())
    }

    @Test
    fun `the permission rules list the declared rules and the granted ones, and revokes a granted one`() {
        harness("""{"allow": ["command.exec(git status)"]}""").execute {
            val operator = channel().user("operator")
            val control = get<PermissionRules>()
            channel().scriptApprovals(ApprovalAnswer.Allowed(operator, always = true), ApprovalAnswer.Denied(operator))
            run("make", "test")
            val listed = control.allowRules(channel().chat())

            val revoked = listOf(
                control.revokeAllowRule(channel().chat(), 1, operator),
                control.revokeAllowRule(channel().chat(), 2, operator),
                control.revokeAllowRule(channel().chat(), 2, operator),
            )
            run("make", "test")
            run("git", "status")

            val status = RuleSubject("command.exec", listOf("git", "status"), "git status")
            assertEquals(
                listOf(AllowRule(1, status, true, null, null), test),
                listOf(listed[0], listed[1].subject),
            )
            assertEquals(
                listOf(Revocation(false, true), Revocation(true, false), Revocation(false, false)),
                revoked,
            )
            assertEquals(2, channel().approvals.size)
            assertEquals(listOf(ClearanceKind.HUMAN, ClearanceKind.RULE), exec.cleared.toList())
        }
    }

    @Test
    fun `permission status shows the model the declared rules, then those granted here with who and when`() {
        harness("""{"allow": ["command.exec(git status)"]}""").execute {
            val operator = channel().user("operator")
            channel().scriptApprovals(ApprovalAnswer.Allowed(operator, always = true))
            run("make", "test")
            model.reply { toolCall("permission.status") }.reply { text("Two rules.") }
            val asked = channel().receive("What runs unasked?", channel().chat(), operator) as Admission.Accepted
            asked.ticket.outcome()

            val (outcome, text) = results().last()
            val lines = text.lines()
            assertEquals(ToolOutcome.Succeeded, outcome)
            assertEquals(
                listOf(
                    "Allow rules (on an operator's turn, in ask, allowedit and auto modes, the calls they cover " +
                        "run without asking; not in plan or manual mode, nor for an opaque program or a " +
                        "lasting-state call):",
                    "1. command.exec: git status (declared in the config file)",
                ),
                lines.subList(2, 4),
            )
            assertTrue(GRANTED.matches(lines[4]), lines[4])
            assertEquals("Conversation: not tainted.", lines.last())
        }
    }

    @Test
    fun `a call into a place the floor asks about offers no rule, and a rule granted before does not clear it`() {
        harness().execute {
            val operator = channel().user("operator")
            channel().scriptApprovals(
                ApprovalAnswer.Allowed(operator, always = true),
                ApprovalAnswer.Allowed(operator, always = true),
            )
            run("make", "test")
            run("make", "test", "${ASKED}notes")

            val asked = channel().approvals[1]
            assertEquals(listOf(null, emptyList<RuleSubject>()), listOf(asked.always, asked.alwaysRules))
            assertEquals("${ASKED}notes is in a place the floor asks about", asked.reason)
            assertEquals(listOf(ClearanceKind.HUMAN, ClearanceKind.HUMAN), exec.cleared.toList())
            assertEquals(1, get<PermissionRules>().allowRules(channel().chat()).size)
        }
    }

    /** `command.exec` reviewed as a run of the `argv` it is given, whose rule is the program and its first word. */
    private class FakeExec : ReviewedTool {
        val cleared = CopyOnWriteArrayList<ClearanceKind?>()

        override val definition: ToolDefinition = testToolDefinition("command.exec", ToolRisk.EXEC)

        override suspend fun review(arguments: JsonObject, context: ToolContext): CallReview {
            val argv = argv(arguments)
            val tokens = argv.take(2)
            return CallReview.builder()
                .preview(ReviewPreview.Command(argv, Path.of("/srv"), null, false))
                .rule(RuleSubject("command.exec", tokens, tokens.joinToString(" ")))
                .person(argv.firstOrNull { it.startsWith(ASKED) }?.let { "$it is in a place the floor asks about" })
                .build()
        }

        override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
            cleared += context.clearance?.kind
            return ToolResult("Ran ${argv(arguments).joinToString(" ")}.")
        }

        private fun argv(arguments: JsonObject): List<String> =
            (arguments.getValue("argv") as JsonArray).map { (it as JsonPrimitive).content }
    }

    private companion object {
        const val ASKED = "/asked/"

        val GRANTED = Regex(
            """2\. command\.exec: make test \(allowed by Operator at \d{4}-\d\d-\d\d \d\d:\d\d \S+ until /new\)""",
        )
    }
}
