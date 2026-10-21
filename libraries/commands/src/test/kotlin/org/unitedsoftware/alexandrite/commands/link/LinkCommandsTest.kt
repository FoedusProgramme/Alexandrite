package org.unitedsoftware.alexandrite.commands.link

import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.commands.CHINESE
import org.unitedsoftware.alexandrite.commands.OPERATOR
import org.unitedsoftware.alexandrite.commands.PLUGIN
import org.unitedsoftware.alexandrite.commands.TestClock
import org.unitedsoftware.alexandrite.commands.answer
import org.unitedsoftware.alexandrite.commands.commandContext
import org.unitedsoftware.alexandrite.commands.commandTexts
import org.unitedsoftware.alexandrite.runtime.TextCatalog
import org.unitedsoftware.alexandrite.sdk.InternalAlexandriteApi
import org.unitedsoftware.alexandrite.sdk.channel.Channel
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatSettingsUpdate
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.turn.ChatLinks
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.Revocation
import org.unitedsoftware.alexandrite.testkit.RecordingAgent
import org.unitedsoftware.alexandrite.testkit.RecordingAgent.Call
import org.unitedsoftware.alexandrite.testkit.RecordingChannel
import org.unitedsoftware.alexandrite.testkit.TEST_INSTANCE
import org.unitedsoftware.alexandrite.testkit.testChat
import org.unitedsoftware.alexandrite.testkit.testCommand
import org.unitedsoftware.alexandrite.testkit.testUser
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinkCommandsTest {
    private val clock = TestClock()
    private val control = RecordingAgent()
    private val channel = RecordingChannel()
    private val channels = object : ChannelDirectory {
        override val instances: Set<ChannelInstanceId> = setOf(TEST_INSTANCE)

        override fun channel(instance: ChannelInstanceId): Channel? = channel.takeIf { instance == TEST_INSTANCE }
    }
    private val commands = commands()
    private val first = testChat("first")
    private val second = testChat("second")
    private val other = testUser("other", isOperator = true)

    // Offers and redemptions.

    @Test
    fun `a code offered in one chat links another chat that redeems it, and the first chat is told`() = runTest {
        val context = commandContext(link(first))
        commands.handle(link(first), context)
        val offer = context.replies.single()
        val code = codeIn(offer.text)

        val reply = commands.answer(link(second, code, other))

        assertEquals(
            "To share the conversation of main in this chat with another chat, an operator sends `/link $code` there " +
                "within 10 minutes.",
            offer.text,
        )
        assertEquals(Markup.MARKDOWN, offer.markup)
        assertEquals("This chat now shares the conversation of main with test:main:first.", reply)
        assertEquals(listOf(Call.Link(AgentId.MAIN, first, second, other)), control.calls)
        val notice = channel.sent.single()
        assertEquals(first, notice.chat)
        assertEquals(MessageKind.NOTICE, notice.message.kind)
        assertEquals("test:main:second now shares the conversation of main with this chat.", notice.message.text)
    }

    @Test
    fun `the notice to the first chat is in that chat's language and a failed notice changes nothing`() = runTest {
        control.settings.update(first, ChatSettingsUpdate.builder().language(CHINESE).build(), OPERATOR)
        val catalog = TextCatalog.builder()
            .text(PLUGIN, CHINESE, "link.anchor_notice", "{chat} 现在和这个聊天共享 {agent} 的对话。")
            .build()
        val commands = commands(commandTexts(catalog))
        channel.scriptDeliveries(Delivery.NotDelivered(DeliveryFailure.FORBIDDEN), chat = first)

        val reply = commands.answer(link(second, offer(first, commands), other))

        assertEquals("This chat now shares the conversation of main with test:main:first.", reply)
        assertEquals("test:main:second 现在和这个聊天共享 main 的对话。", channel.sent.single().message.text)
    }

    @Test
    fun `a chat that another agent serves is told how to take part`() = runTest {
        val code = offer(first)
        val invocation = link(second, code, other)

        val reply = commands.answer(invocation, commandContext(invocation, agent = AgentId("coder")))

        assertEquals(
            "This chat now shares the conversation of main with test:main:first.\n" +
                "This chat talks to coder now: switch with /agent main to take part in the shared conversation.",
            reply,
        )
    }

    @Test
    fun `an offer in a linked chat names the chats it shares the conversation with`() = runTest {
        control.links.link(AgentId.MAIN, first, testChat("x_y"), OPERATOR)

        val reply = commands.answer(link(first))

        assertEquals("This chat shares it with test:main:x\\_y already.", reply.substringAfter("\n\n"))
    }

    @Test
    fun `a code is spent once redeemed, also when the link is refused`() = runTest {
        val code = offer(first)
        control.refuseNext("test:main:second is linked already.")

        assertEquals("test:main:second is linked already.", commands.answer(link(second, code, other)))
        assertEquals(
            "This code is unknown, used or expired. Tries left: 4.",
            commands.answer(link(testChat("third"), code, other)),
        )
    }

    @Test
    fun `a new offer replaces the chat's earlier code`() = runTest {
        val earlier = offer(first)
        val later = offer(first)

        assertEquals(
            "This code is unknown, used or expired. Tries left: 4.",
            commands.answer(link(second, earlier, other)),
        )
        assertEquals(
            "This chat now shares the conversation of main with test:main:first.",
            commands.answer(link(second, later.lowercase().replace("-", " "), other)),
        )
    }

    @Test
    fun `a chat's own code is neither spent nor counted`() = runTest {
        val code = offer(first)

        assertEquals(
            "This code belongs to this chat: send it in the other chat.",
            commands.answer(link(first, code, other)),
        )
        assertEquals(
            "This chat now shares the conversation of main with test:main:first.",
            commands.answer(link(second, code, other)),
        )
    }

    @Test
    fun `a malformed code is not counted`() = runTest {
        assertEquals("This is no link code. A code looks like ABCD-EFGH.", commands.answer(link(second, "hello")))
        assertEquals(
            "This code is unknown, used or expired. Tries left: 4.",
            commands.answer(link(second, "ABCD-EFGH")),
        )
    }

    @Test
    fun `five wrong codes lock the chat out until the first of them is ten minutes old`() = runTest {
        repeat(5) { commands.answer(link(second, "ABCD-EFGH")) }
        clock.now += Duration.ofSeconds(30)
        val code = offer(first)

        assertEquals("Too many wrong codes from this chat. Try again in 10 min.", commands.answer(link(second, code)))
        clock.now += Duration.ofSeconds(9 * 60)
        assertEquals("Too many wrong codes from this chat. Try again in 1 min.", commands.answer(link(second, code)))
        assertEquals(
            "This code is unknown, used or expired. Tries left: 4.",
            commands.answer(link(testChat("third"), "ABCD-EFGH")),
        )
        clock.now += Duration.ofSeconds(30)
        assertEquals(
            "This chat now shares the conversation of main with test:main:first.",
            commands.answer(link(second, code)),
        )
    }

    // /unlink.

    @Test
    fun `unlink takes this chat out of its linked chats`() = runTest {
        control.links.link(AgentId.MAIN, first, second, OPERATOR)

        assertEquals("test:main:second no longer shares the conversation of main.", commands.answer(unlink(second)))
        assertEquals("This chat shares no conversation of main.", commands.answer(unlink(second)))
        assertEquals(Call.Unlink(AgentId.MAIN, second, OPERATOR), control.calls.last())
    }

    @Test
    fun `unlink with a chat takes that chat out of this chat's linked chats`() = runTest {
        control.links.link(AgentId.MAIN, first, second, OPERATOR)
        control.links.link(AgentId.MAIN, first, testChat("third"), OPERATOR)

        assertEquals(
            "test:main:third no longer shares the conversation of main.",
            commands.answer(unlink(second, "test:main:third")),
        )
        assertEquals(
            "test:main:fourth is not linked to this chat.",
            commands.answer(unlink(second, "test:main:fourth")),
        )
        assertEquals(
            "This chat shares no conversation of main.",
            commands.answer(unlink(testChat("fifth"), "test:main:second")),
        )
        assertEquals(listOf(Call.Unlink(AgentId.MAIN, testChat("third"), OPERATOR)), control.calls.drop(2))
    }

    @Test
    @OptIn(InternalAlexandriteApi::class)
    fun `unlink of a chat that the config file links says to edit the file, also once its runtime link is gone`() =
        runTest {
            control.declareLink(AgentId.MAIN, first, listOf(second))
            val dropping = object : ChatLinks by control.links {
                override suspend fun unlink(agent: AgentId, member: ChatAddress, by: ChatUser?): Revocation =
                    Revocation(removed = true, declared = true)
            }

            assertEquals(
                "The config file links test:main:second to the conversation of main: edit the file to unlink it.",
                commands.answer(unlink(second)),
            )
            assertEquals(
                "The link of test:main:second made at runtime is removed, but the config file still links it to the " +
                    "conversation of main: edit the file to unlink it.",
                LinkCommands(dropping, control.settings, channels, LinkCodes(clock), commandTexts(), clock)
                    .answer(unlink(second)),
            )
        }

    @Test
    fun `unlink takes one chat address at most`() = runTest {
        val usage = "Send /unlink to take this chat out of its linked chats, or /unlink <chat> to take another chat " +
            "out of them."

        assertEquals(usage, commands.answer(unlink(first, "third")))
        assertEquals(usage, commands.answer(unlink(first, "test:main:a test:main:b")))
        assertEquals(emptyList(), control.calls)
    }

    private fun commands(texts: Texts = commandTexts()) =
        LinkCommands(control.links, control.settings, channels, LinkCodes(clock), texts, clock)

    private fun link(chat: ChatAddress, code: String = "", issuer: ChatUser = OPERATOR): CommandInvocation =
        testCommand("link", code, chat, issuer)

    private fun unlink(chat: ChatAddress, target: String = ""): CommandInvocation =
        testCommand("unlink", target, chat, OPERATOR)

    private suspend fun offer(chat: ChatAddress, commands: LinkCommands = this.commands): String =
        codeIn(commands.answer(link(chat)))

    private fun codeIn(text: String): String {
        val code = Regex("`/link ([0-9A-Z]{4}-[0-9A-Z]{4})`").find(text)?.groupValues?.get(1)
        assertTrue(code != null, "No code in: $text")
        return code
    }
}
