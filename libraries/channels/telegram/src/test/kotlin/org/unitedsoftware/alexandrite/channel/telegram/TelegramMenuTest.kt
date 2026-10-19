package org.unitedsoftware.alexandrite.channel.telegram

import org.unitedsoftware.alexandrite.channel.telegram.TelegramHarness.Companion.ADA
import org.unitedsoftware.alexandrite.channel.telegram.TelegramHarness.Companion.BOB
import org.unitedsoftware.alexandrite.channel.telegram.TelegramHarness.Companion.GROUP
import org.unitedsoftware.alexandrite.channel.telegram.menu.candidates
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.turn.ChatSettingsUpdate
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramMenuTest {
    private val telegram = TelegramHarness()
    private val fake = telegram.fake
    private val bobAddress = telegram.address(BOB)

    @AfterTest
    fun close() {
        telegram.close()
    }

    /** The commands of the menu of [type], [chat] and [user] in [language], once the bot set it. */
    private fun menu(type: String, chat: Long? = null, user: Long? = null, language: String = ""): List<String> =
        fake.await { menu(type, chat, user, language) }.keys.toList()

    // Scopes.

    @Test
    fun `members get what they may run in direct chats and in groups, admins every command in both`() {
        telegram.run(telegram.builder().commandHandler(ClaimedCommands("purge"))) {
            val direct = menu("all_private_chats")
            val group = menu("all_group_chats")
            val admin = menu("chat", ADA)
            val adminInGroup = menu("chat_member", GROUP, ADA)

            assertEquals(MEMBER_DIRECT, direct)
            assertEquals(MEMBER_GROUP, group)
            assertEquals((DEFAULT_COMMANDS + "purge").sorted(), admin)
            assertEquals(admin, adminInGroup)
            assertEquals("The test's /agent", fake.menu("all_private_chats")?.get("agent"))
            assertEquals(setOf(""), fake.menuKeys.map { it.second }.toSet())
            assertEquals(4, fake.menuKeys.size)
        }
    }

    @Test
    fun `a plugin's command that the bot's config opens is in the members' menus`() {
        val opened = telegram.instance(more = """, "commands": {"purge": "anyone", "audit": "direct"}""")
        val builder = telegram.builder("work" to opened).commandHandler(ClaimedCommands("purge", "audit"))

        telegram.run(builder) {
            assertEquals((MEMBER_DIRECT + "audit" + "purge").sorted(), menu("all_private_chats"))
            assertEquals((MEMBER_GROUP + "purge").sorted(), menu("all_group_chats"))
        }
    }

    @Test
    fun `with the menu switched off the bot sets none`() {
        val off = telegram.instance(more = """, "commandMenu": false""")

        telegram.run(telegram.builder("work" to off)) {
            fake.send(telegram.bob, telegram.bobChat, "/settings")
            telegram.submitted(1)
            telegram.consumed(1)

            assertEquals(emptyList(), fake.calls("setMyCommands"))
        }
    }

    // Languages.

    @Test
    fun `a menu goes out in each language its descriptions differ in, and in its own for a chat with a language`() {
        val notes = DescribedCommands()
        val builder = telegram.builder("work" to telegram.instance(more = """, "commands": {"notes": "anyone"}"""))
            .commandHandler(notes)

        telegram.run(builder) {
            assertEquals("Lists the notes.", fake.await { menu("all_private_chats") }["notes"])
            assertEquals("列出笔记。", fake.await { menu("all_private_chats", language = "zh") }["notes"])
            assertEquals("列出笔记。", fake.await { menu("chat", ADA, language = "zh") }["notes"])
            assertEquals("The test's /settings", fake.menu("all_private_chats", language = "zh")?.get("settings"))
            assertEquals(setOf("", "zh"), fake.menuKeys.map { it.second }.toSet())

            telegram.control.updateSettings(bobAddress, ChatSettingsUpdate.builder().language(CHINESE).build(), null)
            fake.send(telegram.bob, telegram.bobChat, "/settings")

            assertEquals("列出笔记。", fake.await { menu("chat", BOB) }["notes"])
            assertEquals((MEMBER_DIRECT + "notes").sorted(), fake.menu("chat", BOB)?.keys?.toList())

            telegram.control.updateSettings(bobAddress, ChatSettingsUpdate.builder().resetLanguage().build(), null)
            fake.send(telegram.bob, telegram.bobChat, "/settings")

            fake.await { Unit.takeIf { menu("chat", BOB) == null } }
        }
    }

    @Test
    fun `a description that changes is published again once a command ran`() {
        val notes = DescribedCommands()

        telegram.run(telegram.builder().commandHandler(notes)) {
            fake.await { menu("chat", ADA)?.get("notes")?.takeIf { it == "Lists the notes." } }

            notes.english = "Lists and finds the notes."
            fake.send(telegram.bob, telegram.bobChat, "/settings")

            fake.await { menu("chat", ADA)?.get("notes")?.takeIf { it == "Lists and finds the notes." } }
            assertFalse("notes" in menu("all_private_chats"))
        }
    }

    @Test
    fun `the languages a menu may go out in are those of the chats, then each two-letter one by itself`() {
        val languages = candidates(listOf(LanguageTag("zh-TW"), CHINESE, LanguageTag("fil"), LanguageTag("pt-BR")))

        assertEquals(listOf("pt" to LanguageTag("pt-BR"), "zh" to CHINESE), languages.take(2))
        assertTrue("en" to LanguageTag("en") in languages)
        assertEquals(languages.map { it.first }.distinct(), languages.map { it.first })
        assertTrue(languages.all { it.first.length == 2 })
    }

    // Who may run what.

    @Test
    fun `the channel tells who may run a command after its rules`() {
        val opened = telegram.instance(more = """, "commands": {"purge": "anyone"}""")

        telegram.run(telegram.builder("work" to opened).commandHandler(ClaimedCommands("purge", "audit"))) {
            val channel = checkNotNull(get<ChannelDirectory>().channel(WORK))
            val bob = ChatUser(UserAddress(WORK, "$BOB"), "Bob", "bob_test", isBot = false, isOperator = false)
            val ada = ChatUser(UserAddress(WORK, "$ADA"), "Ada", "ada_test", isBot = false, isOperator = true)
            val group = telegram.address(GROUP)

            assertTrue(channel.mayRun(bobAddress, bob, "new", false))
            assertFalse(channel.mayRun(group, bob, "new", false))
            assertTrue(channel.mayRun(group, bob, "model", false))
            assertFalse(channel.mayRun(group, bob, "model", true))
            assertTrue(channel.mayRun(group, bob, "purge", true))
            assertFalse(channel.mayRun(bobAddress, bob, "audit", false))
            assertTrue(channel.mayRun(group, ada, "audit", true))
            assertTrue(channel.mayRun(group, bob, "start", true))
        }
    }

    @Test
    fun `the channel counts its configured admins as admins of every chat and no one else`() {
        telegram.run {
            val channel = checkNotNull(get<ChannelDirectory>().channel(WORK))
            val group = telegram.address(GROUP)
            val elsewhere = ChannelInstanceId(WORK.type, "home")

            assertTrue(channel.isOperator(group, UserAddress(WORK, "$ADA")))
            assertFalse(channel.isOperator(group, UserAddress(WORK, "$BOB")))
            assertFalse(channel.isOperator(group, UserAddress(elsewhere, "$ADA")))
        }
    }

    /** `/notes`, described in Chinese for a Chinese language and by [english] otherwise. */
    private class DescribedCommands : CommandHandler {
        @Volatile
        var english = "Lists the notes."

        override val commands: List<CommandSpec> = listOf(CommandSpec.builder("notes", "Lists the notes.").build())

        override fun description(command: String, language: LanguageTag?): String =
            if (language?.value?.startsWith("zh") == true) "列出笔记。" else english

        override suspend fun handle(invocation: CommandInvocation, context: CommandContext): Unit =
            error("The test runs no command.")
    }

    private companion object {
        val CHINESE = LanguageTag("zh-CN")
        val DEFAULT_COMMANDS = TelegramHarness.DEFAULT_COMMANDS.toList() + "streaming"
        val MEMBER_GROUP = listOf(
            "agent", "context", "jobs", "language", "mode", "model", "profiles", "reasoning", "settings", "streaming",
        )
        val MEMBER_DIRECT = (MEMBER_GROUP + listOf("cancel", "compact", "new")).sorted()
    }
}
