package org.unitedsoftware.alexandrite.commands.chat

import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.commands.CHINESE
import org.unitedsoftware.alexandrite.commands.GUEST
import org.unitedsoftware.alexandrite.commands.OPERATOR
import org.unitedsoftware.alexandrite.commands.PLUGIN
import org.unitedsoftware.alexandrite.commands.answer
import org.unitedsoftware.alexandrite.commands.commandContext
import org.unitedsoftware.alexandrite.commands.commandTexts
import org.unitedsoftware.alexandrite.runtime.TextCatalog
import org.unitedsoftware.alexandrite.sdk.channel.Channel
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandForms
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import org.unitedsoftware.alexandrite.testkit.RecordingAgent
import org.unitedsoftware.alexandrite.testkit.RecordingChannel
import org.unitedsoftware.alexandrite.testkit.TEST_INSTANCE
import org.unitedsoftware.alexandrite.testkit.testCommand
import kotlin.test.Test
import kotlin.test.assertEquals

class HelpCommandsTest {
    /** What a guest may run, by command: bare, then with arguments; anything else they may run. */
    private val guestRules = mutableMapOf("new" to (false to false), "notes" to (true to false))

    private val channels = object : ChannelDirectory {
        val channel = object : Channel by RecordingChannel() {
            override fun mayRun(chat: ChatAddress, issuer: ChatUser, command: String, withArguments: Boolean) =
                issuer.isOperator || guestRules[command]?.let { if (withArguments) it.second else it.first } ?: true
        }

        override val instances: Set<ChannelInstanceId> = setOf(TEST_INSTANCE)

        override fun channel(instance: ChannelInstanceId): Channel? = channel.takeIf { instance == TEST_INSTANCE }
    }

    private fun help(texts: Texts = commandTexts()): HelpCommands {
        lateinit var help: HelpCommands
        val agent = RecordingAgent()
        val others = listOf(ConversationCommands(agent.turns, agent.conversations, agent.links, texts), NotesCommands())
        help = HelpCommands(lazy { Handlers(others + help) }, channels, texts)
        return help
    }

    @Test
    fun `help lists the commands of every handler its issuer may run, marking those they may run only bare`() =
        runTest {
            val guest = help().answer(testCommand("help", issuer = GUEST))
            val operator = help().answer(testCommand("help", issuer = OPERATOR))

            assertEquals(
                """
                Commands you may run in this chat:
                /cancel — Stops the answer that the chat's agent, or the named one, is giving in this chat.
                /help — Lists the commands you may run in this chat.
                /notes — Lists the notes. (only without arguments)
                """.trimIndent(),
                guest,
            )
            assertEquals(
                """
                Commands you may run in this chat:
                /cancel — Stops the answer that the chat's agent, or the named one, is giving in this chat.
                /help — Lists the commands you may run in this chat.
                /new — Starts a new conversation with the agent of this chat.
                /notes — Lists the notes.
                """.trimIndent(),
                operator,
            )
        }

    @Test
    fun `help speaks the chat's language, with each plugin's description in it where the plugin gives one`() = runTest {
        val catalog = TextCatalog.builder()
            .texts(
                PLUGIN,
                CHINESE,
                mapOf(
                    "help.list" to "你在这个聊天中可以使用的命令：\n{commands}",
                    "help.item" to "/{command} — {description}",
                    "help.item_bare" to "/{command} — {description}（仅限不带参数）",
                    "command.help.description" to "列出你在这个聊天中可以使用的命令。",
                ),
            )
            .build()
        guestRules["cancel"] = false to false
        val invocation = testCommand("help", issuer = GUEST)

        val reply = help(commandTexts(catalog)).answer(invocation, commandContext(invocation, language = CHINESE))

        assertEquals(
            """
                你在这个聊天中可以使用的命令：
                /help — 列出你在这个聊天中可以使用的命令。
                /notes — 列出笔记。（仅限不带参数）
            """.trimIndent(),
            reply,
        )
    }

    @Test
    fun `help lists nothing when the issuer may run nothing`() = runTest {
        guestRules.putAll(listOf("help", "cancel", "notes").associateWith { false to false })

        assertEquals("You may run no command in this chat.", help().answer(testCommand("help", issuer = GUEST)))
    }

    @Test
    fun `a description that fails is the one its command declares`() = runTest {
        val failing = object : CommandHandler {
            override val commands = listOf(CommandSpec.builder("audit", "Audits the chat.").build())

            override fun description(command: String, language: LanguageTag?): String = error("no texts")

            override suspend fun handle(invocation: CommandInvocation, context: CommandContext) = Unit
        }
        val texts = commandTexts()
        lateinit var help: HelpCommands
        help = HelpCommands(lazy { Handlers(listOf(help, failing)) }, channels, texts)

        val reply = help.answer(testCommand("help", issuer = OPERATOR))

        assertEquals(
            "Commands you may run in this chat:\n/audit — Audits the chat.\n" +
                "/help — Lists the commands you may run in this chat.",
            reply,
        )
    }

    @Test
    fun `help keeps the forms of a command that are for operators from everyone else`() = runTest {
        val audit = object : CommandHandler {
            override val commands = listOf(
                CommandSpec.builder("audit", "Audits the chat.").operatorsOnly(CommandForms.WITH_ARGUMENTS).build(),
                CommandSpec.builder("purge", "Purges the chat.").operatorsOnly(CommandForms.ALL).build(),
            )

            override suspend fun handle(invocation: CommandInvocation, context: CommandContext) = Unit
        }
        lateinit var help: HelpCommands
        help = HelpCommands(lazy { Handlers(listOf(help, audit)) }, channels, commandTexts())

        assertEquals(
            "Commands you may run in this chat:\n/audit — Audits the chat. (only without arguments)\n" +
                "/help — Lists the commands you may run in this chat.",
            help.answer(testCommand("help", issuer = GUEST)),
        )
        assertEquals(
            "Commands you may run in this chat:\n/audit — Audits the chat.\n" +
                "/help — Lists the commands you may run in this chat.\n/purge — Purges the chat.",
            help.answer(testCommand("help", issuer = OPERATOR)),
        )
    }

    /** Another plugin's `/notes`, described in Chinese for Chinese. */
    private class NotesCommands : CommandHandler {
        override val commands: List<CommandSpec> = listOf(CommandSpec.builder("notes", "Lists the notes.").build())

        override fun description(command: String, language: LanguageTag?): String =
            if (language == CHINESE) "列出笔记。" else "Lists the notes."

        override suspend fun handle(invocation: CommandInvocation, context: CommandContext): Unit =
            error("The test runs no command.")
    }
}
