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
    private val rules = CommandRules(listOf(ClaimedCommands(*DEFAULT_COMMANDS), ClaimedCommands("purge")), control)

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
    fun `anyone may read the settings, the jobs and the context anywhere`() {
        assertRow("settings", "", direct = null, others = null)
        assertRow("context", "", direct = null, others = null)
        for (command in listOf("agent", "model", "mode", "reasoning", "language", "jobs")) {
            assertRow(command, "", direct = null, others = null)
            assertRow(command, "  ", direct = null, others = null)
        }
    }

    @Test
    fun `anyone may change their direct chat, only admins a group`() {
        for ((command, arguments) in listOf(
            "new" to "",
            "compact" to "",
            "compact" to "the open bugs",
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
    fun `only admins may change the model, the permission mode, homes, links, trust and allow rules, or stop a job`() {
        for ((command, arguments) in listOf(
            "model" to "deepseek/deepseek-chat",
            "model" to "reset",
            "mode" to "plan",
            "mode" to "bypass 2h",
            "mode" to "reset",
            "home" to "",
            "home" to "off",
            "link" to "",
            "link" to "ABCD-EFGH",
            "unlink" to "",
            "trust" to "",
            "trust" to "/srv/repo",
            "untrust" to "/srv/repo",
            "allow" to "",
            "allow" to "revoke 1",
            "jobs" to "stop j-7kq2mx",
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
        for ((command, arguments) in listOf("purge" to "", "purge" to "all", "Purge" to "now")) {
            assertRow(command, arguments, direct = TextKeys.REFUSED_ADMIN, others = TextKeys.REFUSED_ADMIN)
        }
    }

    @Test
    fun `a direct chat linked with a group is ruled as the group for what changes their shared conversation`() =
        runTest {
            val group = testChat("-100")
            val linked = testChat("42")
            control.link(AgentId.MAIN, group, linked, testUser("ada", isAdmin = true))

            for (chat in listOf(linked, testChat("42", "7"))) {
                for ((command, arguments) in listOf(
                    "new" to "",
                    "compact" to "",
                    "reasoning" to "high",
                    "language" to "en",
                )) {
                    val refused = refusals(command, arguments, ChatKind.DIRECT, chat)
                    assertEquals(TextKeys.REFUSED_ADMIN_HERE to null, refused, "/$command $arguments")
                }
                for ((command, arguments) in listOf("cancel" to "", "agent" to "coder", "agent" to "reset")) {
                    val refused = refusals(command, arguments, ChatKind.DIRECT, chat)
                    assertEquals(null to null, refused, "/$command $arguments")
                }
                assertEquals(null to null, refusals("settings", "", ChatKind.DIRECT, chat))
                assertEquals(null to null, refusals("context", "", ChatKind.DIRECT, chat))
                assertEquals(null to null, refusals("language", "", ChatKind.DIRECT, chat))
            }
            assertEquals(null to null, refusals("new", "", ChatKind.DIRECT, testChat("43")))
        }
}
