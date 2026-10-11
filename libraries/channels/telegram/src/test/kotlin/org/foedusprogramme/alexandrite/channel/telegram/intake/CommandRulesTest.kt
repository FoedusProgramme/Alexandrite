package org.foedusprogramme.alexandrite.channel.telegram.intake

import kotlinx.coroutines.test.runTest
import org.foedusprogramme.alexandrite.channel.telegram.ClaimedCommands
import org.foedusprogramme.alexandrite.channel.telegram.TelegramHarness.Companion.DEFAULT_COMMANDS
import org.foedusprogramme.alexandrite.channel.telegram.i18n.TextKeys
import org.foedusprogramme.alexandrite.sdk.chat.AgentId
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.testkit.RecordingAgentControl
import org.foedusprogramme.alexandrite.testkit.testChat
import org.foedusprogramme.alexandrite.testkit.testCommand
import org.foedusprogramme.alexandrite.testkit.testUser
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandRulesTest {
    private val kinds = listOf(ChatKind.DIRECT, ChatKind.GROUP, ChatKind.BROADCAST, ChatKind.UNKNOWN)
    private val control = RecordingAgentControl()
    private val rules = CommandRules(listOf(ClaimedCommands(*DEFAULT_COMMANDS), ClaimedCommands("mode")), control)

    /** The refusal of `/[command] [arguments]` in [chat] of [kind] for a member and for an admin. */
    private fun refusals(
        command: String,
        arguments: String,
        kind: ChatKind,
        chat: ChatAddress = testChat(),
    ): Pair<String?, String?> {
        val member = testCommand(command, arguments, chat, issuer = testUser("bob"))
        val admin = testCommand(command, arguments, chat, issuer = testUser("ada", isAdmin = true))
        return rules.refusal(member, kind) to rules.refusal(admin, kind)
    }

    private fun assertRow(command: String, arguments: String, direct: String?, others: String?) {
        for (kind in kinds) {
            val expected = (if (kind == ChatKind.DIRECT) direct else others) to null
            assertEquals(expected, refusals(command, arguments, kind), "/$command $arguments in a $kind chat")
        }
    }

    @Test
    fun `anyone may read the settings anywhere`() {
        assertRow("settings", "", direct = null, others = null)
        for (command in listOf("agent", "model", "reasoning", "language")) {
            assertRow(command, "", direct = null, others = null)
            assertRow(command, "  ", direct = null, others = null)
        }
    }

    @Test
    fun `anyone may change their direct chat, only admins a group`() {
        for ((command, arguments) in listOf(
            "new" to "",
            "cancel" to "",
            "agent" to "coder",
            "agent" to "reset",
            "reasoning" to "high",
            "language" to "zh-CN",
            "language" to "reset",
        )) {
            assertRow(command, arguments, direct = null, others = TextKeys.REFUSED_ADMIN_HERE)
        }
    }

    @Test
    fun `only admins may change the model, homes, links and trust`() {
        for ((command, arguments) in listOf(
            "model" to "deepseek/deepseek-chat",
            "model" to "reset",
            "home" to "",
            "home" to "off",
            "link" to "",
            "link" to "ABCD-EFGH",
            "unlink" to "",
            "trust" to "",
            "trust" to "/srv/repo",
            "untrust" to "/srv/repo",
        )) {
            assertRow(command, arguments, direct = TextKeys.REFUSED_ADMIN, others = TextKeys.REFUSED_ADMIN)
        }
    }

    @Test
    fun `names are matched ignoring case, and a command no handler claims reaches the agent from anyone`() {
        assertRow("New", "", direct = null, others = TextKeys.REFUSED_ADMIN_HERE)
        assertRow("MODEL", "x/y", direct = TextKeys.REFUSED_ADMIN, others = TextKeys.REFUSED_ADMIN)
        for (command in listOf("start", "help", "notes")) assertRow(command, "anything", direct = null, others = null)
    }

    @Test
    fun `a claimed command the matrix does not list is for admins alone`() {
        for ((command, arguments) in listOf("mode" to "", "mode" to "bypass", "Mode" to "plan")) {
            assertRow(command, arguments, direct = TextKeys.REFUSED_ADMIN, others = TextKeys.REFUSED_ADMIN)
        }
    }

    @Test
    fun `a direct chat linked with a group is ruled as the group`() = runTest {
        val group = testChat("-100")
        val linked = testChat("42")
        control.link(AgentId.MAIN, group, linked, testUser("ada", isAdmin = true))

        for (chat in listOf(linked, testChat("42", "7"))) {
            assertEquals(TextKeys.REFUSED_ADMIN_HERE to null, refusals("new", "", ChatKind.DIRECT, chat))
            assertEquals(TextKeys.REFUSED_ADMIN_HERE to null, refusals("language", "en", ChatKind.DIRECT, chat))
            assertEquals(null to null, refusals("settings", "", ChatKind.DIRECT, chat))
        }
        assertEquals(null to null, refusals("new", "", ChatKind.DIRECT, testChat("43")))
    }
}
