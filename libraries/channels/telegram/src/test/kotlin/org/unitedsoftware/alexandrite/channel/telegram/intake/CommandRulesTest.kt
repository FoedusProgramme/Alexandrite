package org.unitedsoftware.alexandrite.channel.telegram.intake

import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.channel.telegram.ClaimedCommands
import org.unitedsoftware.alexandrite.channel.telegram.TelegramHarness.Companion.DEFAULT_COMMANDS
import org.unitedsoftware.alexandrite.channel.telegram.Who
import org.unitedsoftware.alexandrite.channel.telegram.i18n.TextKeys
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatKind
import org.unitedsoftware.alexandrite.testkit.RecordingAgentControl
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testCommand
import org.unitedsoftware.alexandrite.testkit.testUser
import kotlin.test.Test
import kotlin.test.assertEquals

class CommandRulesTest {
    private val kinds = listOf(ChatKind.DIRECT, ChatKind.GROUP, ChatKind.BROADCAST, ChatKind.UNKNOWN)
    private val control = RecordingAgentControl()
    private val rules =
        CommandRules(listOf(ClaimedCommands(*DEFAULT_COMMANDS), ClaimedCommands("purge", "heartbeat")), control)

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
    fun `anyone may read the settings, the jobs, the context and the heartbeat anywhere`() {
        assertRow("settings", "", direct = null, others = null)
        assertRow("heartbeat", "", direct = null, others = null)
        assertRow("heartbeat", "now", direct = null, others = null)
        assertRow("context", "", direct = null, others = null)
        assertRow("profiles", "", direct = null, others = null)
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
    fun `anyone lists the scheduled tasks, only admins cancel one`() {
        val rules = CommandRules(listOf(ClaimedCommands("schedule")), control)
        val member = testUser("bob")
        val admin = testUser("ada", isAdmin = true)

        for (kind in kinds) {
            assertEquals(null, rules.refusal(testCommand("schedule", "", issuer = member), kind))
            assertEquals(
                TextKeys.REFUSED_ADMIN,
                rules.refusal(testCommand("schedule", "cancel t-4k2p", issuer = member), kind),
            )
            assertEquals(null, rules.refusal(testCommand("schedule", "cancel t-4k2p", issuer = admin), kind))
        }
        assertEquals(true, rules.listed("schedule", admin = false, direct = false))
    }

    @Test
    fun `anyone sees the MCP servers, only admins restart one`() {
        val rules = CommandRules(listOf(ClaimedCommands("mcp")), control)
        val member = testUser("bob")
        val admin = testUser("ada", isAdmin = true)

        for (kind in kinds) {
            assertEquals(null, rules.refusal(testCommand("mcp", "", issuer = member), kind))
            assertEquals(
                TextKeys.REFUSED_ADMIN,
                rules.refusal(testCommand("mcp", "restart github", issuer = member), kind),
            )
            assertEquals(null, rules.refusal(testCommand("mcp", "restart github", issuer = admin), kind))
        }
        assertEquals(true, rules.listed("mcp", admin = false, direct = false))
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
    fun `the bot's config opens a plugin's command by name, in the form it names`() {
        val opened = mapOf("purge" to Who.ANYONE, "notes" to Who.DIRECT)
        val rules = CommandRules(listOf(ClaimedCommands("purge", "notes", "audit")), control, opened)
        val member = testUser("bob")

        for (arguments in listOf("", "all")) {
            for (kind in kinds) {
                assertEquals(null, rules.refusal(testCommand("purge", arguments, issuer = member), kind))
                val notes = rules.refusal(testCommand("notes", arguments, issuer = member), kind)
                assertEquals(if (kind == ChatKind.DIRECT) null else TextKeys.REFUSED_ADMIN_HERE, notes)
                val audit = rules.refusal(testCommand("audit", arguments, issuer = member), kind)
                assertEquals(TextKeys.REFUSED_ADMIN, audit)
            }
        }
    }

    @Test
    fun `whether an issuer may run a command follows the refusals, the kind of chat told by its id`() {
        val member = testUser("bob")
        val admin = testUser("ada", isAdmin = true)
        val direct = testChat("42")
        val group = testChat("-100", "7")

        for ((command, arguments) in listOf(
            "settings" to "",
            "model" to "",
            "model" to "x/y",
            "new" to "",
            "agent" to "coder",
            "home" to "",
            "purge" to "",
            "start" to "x",
        )) {
            for (chat in listOf(direct, group)) {
                val kind = if (chat == direct) ChatKind.DIRECT else ChatKind.GROUP
                val refused = rules.refusal(testCommand(command, arguments, chat, member), kind)
                val withArguments = arguments.isNotEmpty()
                assertEquals(refused == null, rules.mayRun(chat, false, command, withArguments), "/$command $arguments")
                assertEquals(true, rules.mayRun(chat, true, command, withArguments))
                assertEquals(null, rules.refusal(testCommand(command, arguments, chat, admin), kind))
            }
        }
    }

    @Test
    fun `a menu lists what its users may run in some form, a direct chat taken as linked with no other`() {
        val member = { direct: Boolean -> DEFAULT_COMMANDS.filter { rules.listed(it, false, direct) }.toSet() }

        assertEquals(
            setOf(
                "settings", "context", "profiles", "agent", "reasoning", "language", "model", "mode", "jobs", "new",
                "compact", "cancel",
            ),
            member(true),
        )
        assertEquals(
            setOf("settings", "context", "profiles", "agent", "reasoning", "language", "model", "mode", "jobs"),
            member(false),
        )
        val claimed = DEFAULT_COMMANDS.toList() + "purge"
        for (direct in listOf(true, false)) assertEquals(claimed, claimed.filter { rules.listed(it, true, direct) })
        assertEquals(false, rules.listed("purge", false, true))
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
