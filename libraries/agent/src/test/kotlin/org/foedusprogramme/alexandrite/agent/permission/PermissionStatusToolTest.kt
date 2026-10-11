package org.foedusprogramme.alexandrite.agent.permission

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.foedusprogramme.alexandrite.agent.TestClock
import org.foedusprogramme.alexandrite.agent.agentDirectory
import org.foedusprogramme.alexandrite.agent.control.SettingsStates
import org.foedusprogramme.alexandrite.agent.hostPaths
import org.foedusprogramme.alexandrite.agent.settings
import org.foedusprogramme.alexandrite.agent.tool.AgentToolContext
import org.foedusprogramme.alexandrite.agent.workspace.Workspaces
import org.foedusprogramme.alexandrite.sdk.chat.ToolCallId
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.permission.PermissionMode
import org.foedusprogramme.alexandrite.sdk.store.ConversationTaint
import org.foedusprogramme.alexandrite.sdk.tool.ToolContext
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import org.foedusprogramme.alexandrite.sdk.turn.SettingChange
import org.foedusprogramme.alexandrite.testkit.MemoryStore
import org.foedusprogramme.alexandrite.testkit.TestChatStates
import org.foedusprogramme.alexandrite.testkit.testToolContext
import org.foedusprogramme.alexandrite.testkit.testTurn
import org.foedusprogramme.alexandrite.testkit.testUser
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class PermissionStatusToolTest {
    private val clock = TestClock()
    private val states = SettingsStates(TestChatStates().of("alexandrite-agent"))
    private val store = MemoryStore(clock)
    private val operator: TurnInfo = testTurn { actor(testUser("admin", isAdmin = true)) }
    private val forwarded = ConversationTaint(clock.now, "a forwarded message")

    private fun tool(mode: String? = null): PermissionStatusTool {
        val agent = mode?.let { """"main": {"permission": {"mode": "$it"}}""" }.orEmpty()
        val directory = agentDirectory(settings("""{"agents": {$agent}}"""))
        return PermissionStatusTool(states, directory, store.conversations, clock)
    }

    private fun context(turn: TurnInfo, taint: TurnTaint): ToolContext =
        AgentToolContext(turn, ToolCallId("call-1"), Workspaces(hostPaths(null), settings("{}")), null, taint)

    private suspend fun status(
        tool: PermissionStatusTool = tool(),
        turn: TurnInfo = operator,
        context: ToolContext = context(turn, TurnTaint(turn.id, turn.key)),
    ): List<String> = (tool.execute(JsonObject(emptyMap()), context).content.single() as TextPart).text.lines()

    @Test
    fun `a clean conversation of an operator's turn in the default mode`() = runTest {
        assertEquals(
            listOf(
                "Permission status of this chat (only an administrator changes it):",
                "Mode: ask (the default).",
                "Conversation: not tainted.",
            ),
            status(),
        )
    }

    @Test
    fun `the mode says where it comes from, and a bypass how long it has left and what follows it`() = runTest {
        val key = operator.key
        val modes = mutableListOf(status(tool("auto"))[1])
        states.changeMode(key, SettingChange.SetTo(PermissionMode.ALLOW_EDIT), null)
        modes += status(tool("auto"))[1]
        states.changeMode(key, SettingChange.SetTo(PermissionMode.BYPASS), clock.now + Duration.ofMinutes(90))
        modes += status(tool("auto"))[1]
        modes += status(tool())[1]
        clock.now += Duration.ofMinutes(89).plusSeconds(30)
        modes += status(tool())[1]
        clock.now += Duration.ofSeconds(30)
        modes += status(tool("auto"))[1]

        assertEquals(
            listOf(
                "Mode: auto (the agent's setting).",
                "Mode: allowedit (set in this chat).",
                "Mode: bypass (set in this chat) for 1 h 30 min more, until 2026-01-01 01:30 Z, then auto.",
                "Mode: bypass (set in this chat) for 1 h 30 min more, until 2026-01-01 01:30 Z, then ask.",
                "Mode: bypass (set in this chat) for 1 min more, until 2026-01-01 01:30 Z, then ask.",
                "Mode: auto (the agent's setting).",
            ),
            modes,
        )
    }

    @Test
    fun `a turn of anyone but the operator learns that the mode does not decide its calls`() = runTest {
        val member = testTurn()

        assertEquals(
            "This turn is no operator's, so its gated calls ask an administrator whatever the mode.",
            status(turn = member)[2],
        )
    }

    @Test
    fun `a tainted conversation shows since when and why, and what that holds back`() = runTest {
        val heartbeat = testTurn(TurnKind.HEARTBEAT)
        fun recorded(turn: TurnInfo) =
            TurnTaint(turn.id, turn.key, clock, turn.conversation, store.conversations, forwarded)
        val unstored = TurnTaint(operator.id, operator.key, clock).apply { note("a web page") }
        val unread =
            TurnTaint(operator.id, operator.key, clock, operator.conversation, store.conversations, unread = true)

        val lines = listOf(
            status(context = context(operator, recorded(operator))).last(),
            status(turn = heartbeat, context = context(heartbeat, recorded(heartbeat))).last(),
            status(context = context(operator, unstored)).last(),
            status(context = context(operator, unread)).last(),
        )

        val holds = "Until /new starts a new conversation, calls that change your lasting state, such as " +
            "persona.edit, need an approval."
        assertEquals(
            listOf(
                "Conversation: tainted since 2026-01-01 00:00 Z (a forwarded message). $holds",
                "Conversation: tainted since 2026-01-01 00:00 Z (a forwarded message). Heartbeat turns share this " +
                    "conversation, which /new does not replace.",
                "Conversation: tainted since 2026-01-01 00:00 Z (a web page). The mark could not be stored yet. $holds",
                "Conversation: counted as tainted, as its mark could not be read.",
            ),
            lines,
        )
    }

    @Test
    fun `outside a call of the agent the store says whether the conversation is tainted`() = runTest {
        val conversation = store.conversations.current(operator.key)
        val turn = testTurn { actor(testUser("admin", isAdmin = true)).conversation(conversation.id) }
        store.conversations.markTainted(conversation.id, forwarded.reason, forwarded.at)

        val line = status(turn = turn, context = testToolContext(turn)).last()

        assertEquals(
            "Conversation: tainted since 2026-01-01 00:00 Z (a forwarded message). Until /new starts a new " +
                "conversation, calls that change your lasting state, such as persona.edit, need an approval.",
            line,
        )
    }
}
