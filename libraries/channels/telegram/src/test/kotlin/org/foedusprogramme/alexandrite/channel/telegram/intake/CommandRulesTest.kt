package org.foedusprogramme.alexandrite.channel.telegram.intake

import org.foedusprogramme.alexandrite.channel.telegram.i18n.TextKeys
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.testkit.testCommand
import org.foedusprogramme.alexandrite.testkit.testUser
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandRulesTest {
    private val kinds = listOf(ChatKind.DIRECT, ChatKind.GROUP, ChatKind.BROADCAST, ChatKind.UNKNOWN)

    /** The refusal of `/[command] [arguments]` for a member and for an admin in a chat of [kind]. */
    private fun refusals(command: String, arguments: String, kind: ChatKind): Pair<String?, String?> {
        val member = testCommand(command, arguments, issuer = testUser("bob"))
        val admin = testCommand(command, arguments, issuer = testUser("ada", isAdmin = true))
        return CommandRules.refusal(member, kind) to CommandRules.refusal(admin, kind)
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
    fun `names are matched ignoring case, and other commands pass to the agent`() {
        assertRow("New", "", direct = null, others = TextKeys.REFUSED_ADMIN_HERE)
        assertRow("MODEL", "x/y", direct = TextKeys.REFUSED_ADMIN, others = TextKeys.REFUSED_ADMIN)
        for (command in listOf("start", "help", "notes")) assertRow(command, "anything", direct = null, others = null)
    }
}
