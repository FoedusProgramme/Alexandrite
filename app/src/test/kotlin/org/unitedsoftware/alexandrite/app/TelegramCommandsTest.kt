package org.unitedsoftware.alexandrite.app

import org.junit.jupiter.api.io.TempDir
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.ADA
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.BOB
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.BOT_MENTION
import org.unitedsoftware.alexandrite.app.TelegramApp.Companion.WAIT
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramCommandsTest {
    @TempDir
    lateinit var directory: Path

    private val app = TelegramApp()
    private val fake = app.fake

    @AfterTest
    fun close() {
        app.close()
    }

    @Test
    fun `the menus and help list what each user may run, other plugins' commands included, in the chat's language`() {
        val builder = app.builder(directory)
            .texts(AppTexts.packs(AppPlugin::class.java.classLoader))
            .commandHandler(NotesCommands())

        app.run(builder) {
            val members = fake.await(WAIT) { menu("all_private_chats") }
            val admin = fake.await(WAIT) { menu("chat", ADA) }
            assertEquals("Lists the commands you may run in this chat.", members["help"])
            assertTrue("new" in members && "home" !in members && "notes" !in members, "$members")
            assertTrue("home" in admin && "notes" in admin, "$admin")
            assertEquals("列出笔记。", fake.await(WAIT) { menu("chat", ADA, language = "zh") }["notes"])

            app.send(app.bob, app.group, "/help$BOT_MENTION")
            val group = app.messages(app.group, 1).single().text
            app.send(app.ada, app.adaChat, "/help")
            val adaHelp = app.messages(app.adaChat, 1).single().text

            assertTrue(group.startsWith("Commands you may run in this chat:\n"), group)
            assertTrue("\n/settings — Shows the settings of this chat." in group, group)
            assertTrue(
                "\n/model — Shows the model of this chat, lists an endpoint's models, or switches the " +
                    "model. (only without arguments)" in group,
                group,
            )
            for (command in listOf("new", "home", "notes")) assertFalse("\n/$command —" in group, group)
            assertTrue("\n/notes — Lists the notes." in adaHelp && "\n/home —" in adaHelp, adaHelp)

            app.send(app.bob, app.bobChat, "/language zh-CN")
            app.messages(app.bobChat, 1)

            val chinese = fake.await(WAIT) { menu("chat", BOB) }
            assertEquals("列出你在这个聊天中可以使用的命令。", chinese["help"])
            fake.await(WAIT) { menu("all_private_chats", language = "zh")?.takeIf { it == chinese } }
            app.send(app.bob, app.bobChat, "/help")
            val bobHelp = app.messages(app.bobChat, 2).last().text
            assertTrue(bobHelp.startsWith("你在这个聊天中可以使用的命令：\n"), bobHelp)
            assertTrue("\n/new — 和这个聊天的智能体开始新对话。" in bobHelp, bobHelp)
            idle()
        }
    }

    /** Another plugin's `/notes`, which only admins may run. */
    private class NotesCommands : CommandHandler {
        override val commands: List<CommandSpec> = listOf(CommandSpec.builder("notes", "Lists the notes.").build())

        override fun description(command: String, language: LanguageTag?): String =
            if (language?.value?.startsWith("zh") == true) "列出笔记。" else "Lists the notes."

        override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
            context.reply("No notes yet.")
        }
    }
}
