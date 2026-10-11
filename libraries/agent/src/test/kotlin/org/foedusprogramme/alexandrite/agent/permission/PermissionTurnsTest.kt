package org.foedusprogramme.alexandrite.agent.permission

import org.foedusprogramme.alexandrite.agent.agentHarness
import org.foedusprogramme.alexandrite.agent.execute
import org.foedusprogramme.alexandrite.agent.turn.Interceptor
import org.foedusprogramme.alexandrite.sdk.channel.ApprovalAnswer
import org.foedusprogramme.alexandrite.sdk.chat.AgentChatKey
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.hook.HookDecision
import org.foedusprogramme.alexandrite.sdk.permission.ClearanceKind
import org.foedusprogramme.alexandrite.sdk.permission.PermissionMode
import org.foedusprogramme.alexandrite.sdk.tool.ToolResult
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.sdk.transcript.NotRunReason
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.transcript.ToolOutcome
import org.foedusprogramme.alexandrite.sdk.transcript.ToolResultEntry
import org.foedusprogramme.alexandrite.sdk.turn.Admission
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.ChatSettingsUpdate
import org.foedusprogramme.alexandrite.sdk.turn.ControlRefusedException
import org.foedusprogramme.alexandrite.sdk.turn.TurnPoints
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.PluginHarness
import org.foedusprogramme.alexandrite.testkit.ScriptedModel
import org.foedusprogramme.alexandrite.testkit.recordingTool
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PermissionTurnsTest {
    private val store = MemoryStore()
    private val model = ScriptedModel()
    private val coder = AgentId("coder")
    private val write = recordingTool("fs.write", ToolRisk.WORKSPACE_WRITE) { _, context ->
        ToolResult("Written, cleared by ${context.clearance?.kind}.")
    }

    private fun harness(agent: String = "", configure: PluginHarness.Builder.() -> Unit = {}): PluginHarness =
        agentHarness(
            """
            {
              "previewIntervalMillis": 0,
              "agents": {"coder": {"model": "scripted/test-model", "channels": {"test:main": {}}$agent}}
            }
            """.trimIndent(),
            store,
        ) { channel(admins = setOf("admin")).model(model).tool(write).apply(configure) }

    private fun PluginHarness.Running.ask(text: String = "Hi", user: String = "admin"): Admission.Accepted =
        channel().receive(text, sender = channel().user(user)) as Admission.Accepted

    private suspend fun PluginHarness.Running.setMode(mode: PermissionMode, until: Instant? = null) =
        get<AgentControl>().updateSettings(
            channel().chat(),
            ChatSettingsUpdate.builder().mode(mode, until).build(),
            channel().user("admin"),
        )

    private suspend fun results(): List<ToolResultEntry> {
        val conversation = store.conversations.current(AgentChatKey.parse("coder@test:main:chat")).id
        return store.transcripts.entries(conversation).filterIsInstance<ToolResultEntry>()
    }

    private fun ToolResultEntry.text(): String = content.joinToString("") { (it as TextPart).text }

    @Test
    fun `in plan mode a gated call does not run, and the model reads why`() {
        model.reply { toolCall("fs.write", """{"path": "a"}""") }.reply { text("I would write a.") }

        harness().execute {
            setMode(PermissionMode.PLAN)
            ask().ticket.outcome()

            val result = results().single()
            assertEquals(ToolOutcome.NotRun(NotRunReason.PLAN_MODE), result.outcome)
            assertEquals(result, model.requests.last().history.last())
            assertTrue(result.text().startsWith("[alexandrite:plan_mode] Not run: this chat is in plan mode."))
            assertEquals(emptyList(), write.calls)
        }
    }

    @Test
    fun `a mode change applies at once, and the running turn's next gated call reads it`() {
        val read = recordingTool("fs.read").hold()
        model.reply { toolCall("fs.read") }.reply { toolCall("fs.write") }.reply { text("Done.") }

        harness { tool(read) }.execute {
            setMode(PermissionMode.PLAN)
            val turn = ask()
            read.awaitCalls(1)
            setMode(PermissionMode.BYPASS)
            read.release()
            turn.ticket.outcome()

            assertEquals(
                listOf(ToolOutcome.Succeeded to "done", ToolOutcome.Succeeded to "Written, cleared by mode."),
                results().map { it.outcome to it.text() },
            )
        }
    }

    @Test
    fun `a member's gated call asks an approver even in the operator's bypass`() {
        model.reply { toolCall("fs.write") }.reply { text("Not written.") }

        harness(""", "tools": {"forMembers": ["fs.write"]}""").execute {
            setMode(PermissionMode.BYPASS)
            channel().scriptApprovals(ApprovalAnswer.Denied(channel().user("admin")))
            ask(user = "member").ticket.outcome()

            val request = channel().approvals.single()
            assertEquals("a member's call", request.reason)
            assertEquals(
                ToolOutcome.NotRun(NotRunReason.DENIED, request.id) to Refusals.DENIED_BY_ADMIN,
                results().single().let { it.outcome to it.text() },
            )
            assertEquals(emptyList(), write.calls)
        }
    }

    @Test
    fun `a tool before hook that requires approval keeps a read from running in bypass`() {
        val read = recordingTool("fs.read")
        val careful =
            Interceptor(TurnPoints.TOOL_BEFORE) { HookDecision.Replace(it.withRequireApproval("Look first.")) }
        model.reply { toolCall("fs.read") }.reply { text("Not read.") }

        harness { tool(read).hook(careful) }.execute {
            setMode(PermissionMode.BYPASS)
            ask().ticket.outcome()

            assertEquals(ToolOutcome.NotRun(NotRunReason.DENIED_BY_POLICY), results().single().outcome)
            assertEquals(emptyList(), read.calls)
        }
    }

    @Test
    fun `untrusted content a tool reports gates the operator's lasting changes for the rest of the turn`() {
        val fetch = recordingTool("web.fetch", ToolRisk.NETWORK_READ) { _, context ->
            context.untrusted("a web page")
            ToolResult("Page.")
        }
        val persona = recordingTool("profile.save", ToolRisk.PERSISTENT_STATE)
        model.reply { toolCall("profile.save") }.reply { text("Edited.") }
            .reply { toolCall("web.fetch") }.reply { toolCall("profile.save") }.reply { text("Not edited.") }

        harness { tool(fetch).tool(persona) }.execute {
            ask().ticket.outcome()
            ask().ticket.outcome()

            assertEquals(
                listOf(ToolOutcome.Succeeded, ToolOutcome.Succeeded, ToolOutcome.NotRun(NotRunReason.DENIED_BY_POLICY)),
                results().map { it.outcome },
            )
            assertEquals(1, persona.calls.size)
        }
    }

    @Test
    fun `a bypass lasts an hour unless it ends sooner, a day at most, and never ends in the past`() {
        harness().execute {
            val before = Instant.now()
            val default = setMode(PermissionMode.BYPASS).modeUntil!!
            val long = setMode(PermissionMode.BYPASS, before + Duration.ofDays(3)).modeUntil!!
            val short = before + Duration.ofMinutes(10)
            val chosen = setMode(PermissionMode.BYPASS, short).modeUntil
            val after = Instant.now()

            assertTrue(default in before + Duration.ofHours(1)..after + Duration.ofHours(1), "$default")
            assertTrue(long in before + Duration.ofDays(1)..after + Duration.ofDays(1), "$long")
            assertEquals(short, chosen)
            val past = assertFailsWith<ControlRefusedException> { setMode(PermissionMode.BYPASS, before) }
            assertEquals("A bypass cannot end at $before, which has passed.", past.message)
            val unknown = assertFailsWith<ControlRefusedException> { setMode(PermissionMode.of("yolo")) }
            assertEquals(
                "'yolo' is no permission mode: use plan, manual, ask, allowedit, auto, bypass.",
                unknown.message,
            )
            assertEquals(
                PermissionMode.BYPASS to short,
                get<AgentControl>().settings(channel().chat()).let {
                    it.mode to it.modeUntil
                },
            )
        }
    }

    @Test
    fun `a chat's mode survives a restart and shows beside the agent's default`() {
        harness(""", "permission": {"mode": "manual"}""").execute {
            val before = get<AgentControl>().settings(channel().chat())
            setMode(PermissionMode.ALLOW_EDIT)
            assertEquals(
                listOf(null, PermissionMode.MANUAL, PermissionMode.MANUAL),
                listOf(before.mode, before.defaultMode, before.effectiveMode),
            )
        }
        harness(""", "permission": {"mode": "manual"}""").execute {
            val after = get<AgentControl>().settings(channel().chat())

            assertEquals(PermissionMode.ALLOW_EDIT to PermissionMode.ALLOW_EDIT, after.mode to after.effectiveMode)
        }
    }

    @Test
    fun `linked chats share the mode kept at their anchor`() {
        harness().execute {
            val control = get<AgentControl>()
            val anchor = channel().chat("anchor")
            val member = channel().chat()
            control.link(coder, anchor, member, channel().user("admin"))
            control.updateSettings(member, ChatSettingsUpdate.builder().mode(PermissionMode.PLAN).build(), null)

            assertEquals(PermissionMode.PLAN, control.settings(anchor).mode)
        }
    }

    @Test
    fun `a gated call of an operator's turn runs in bypass mode, cleared by the mode`() {
        model.reply { toolCall("fs.write") }.reply { text("Written.") }

        harness().execute {
            setMode(PermissionMode.BYPASS)
            ask().ticket.outcome()

            assertEquals("Written, cleared by ${ClearanceKind.MODE}.", results().single().text())
        }
    }
}
