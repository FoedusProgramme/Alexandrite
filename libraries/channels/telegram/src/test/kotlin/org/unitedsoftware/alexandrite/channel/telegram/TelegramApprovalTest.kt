package org.unitedsoftware.alexandrite.channel.telegram

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.unitedsoftware.alexandrite.channel.telegram.TelegramHarness.Companion.ADA
import org.unitedsoftware.alexandrite.channel.telegram.fake.FakeBotMessage
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalRequest
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.QuoteTrust
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.tool.ToolRisk
import org.unitedsoftware.alexandrite.sdk.turn.Submission
import org.unitedsoftware.alexandrite.testkit.PluginHarness
import org.unitedsoftware.alexandrite.testkit.testTurn
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TelegramApprovalTest {
    private val telegram = TelegramHarness()
    private val fake = telegram.fake
    private val ada = ChatUser(UserAddress(WORK, "$ADA"), "Ada Lovelace", "ada_test", isBot = false, isOperator = true)

    @AfterTest
    fun close() {
        telegram.close()
    }

    @Test
    fun `a button pressed in Telegram answers the request, and the message then shows who answered`() {
        val request = request()

        telegram.run {
            val answer = coroutineScope {
                val asking = async(Dispatchers.Default) { telegramChannel().askApproval(request) }
                val asked = asked()
                fake.press(telegram.bob, asked, "Allow")
                fake.await { answers.singleOrNull() }
                fake.press(telegram.ada, asked, "Allow")
                asking.await()
            }

            assertEquals(ApprovalAnswer.Allowed(ada), answer)
            val shown = fake.await { botMessages(ADA).single().takeIf { it.buttons.isEmpty() } }
            assertEquals("Allowed by Ada Lovelace (@ada_test).", shown.text.substringAfterLast("\n"))
            val toasts = fake.await { answers.takeIf { it.size == 2 } }
            assertEquals(listOf("Only an admin of this bot can answer.", null), toasts.map { it.text })
        }
    }

    @Test
    fun `a reply to the request quotes the line that stands for it, which the channel vouches for`() {
        telegram.run {
            val asked = coroutineScope {
                val asking = async(Dispatchers.Default) { telegramChannel().askApproval(request()) }
                asked().also {
                    fake.press(telegram.ada, it, "Deny")
                    asking.await()
                }
            }
            fake.send(telegram.ada, telegram.adaChat, "Why not?", replyTo = asked.id)

            val quote = assertIs<Submission.Message>(telegram.submitted(1).single()).message.quote
            assertEquals(
                "Alexandrite's approval request k3x9 for fs.write (denied by Ada Lovelace (@ada_test))",
                quote?.text,
            )
            assertEquals(QuoteTrust(live = true, content = true), quote?.trust)
            assertEquals(true, quote?.senderIsBot)
        }
    }

    @Test
    fun `a heartbeat's request, of no chat, reaches the admins and takes their answer`() {
        val beat = testTurn(TurnKind.HEARTBEAT, ChatAddress.ofAgent(AgentId("coder")))
        val request = ApprovalRequest.builder(
            "b7q2",
            beat,
            "chat.send",
            ToolRisk.EXTERNAL_ACTION,
            "{}",
            "a test",
        ).build()

        telegram.run {
            val answer = coroutineScope {
                val asking = async(Dispatchers.Default) { telegramChannel().askApproval(request) }
                fake.press(telegram.ada, asked(), "Allow")
                asking.await()
            }

            assertEquals(ApprovalAnswer.Allowed(ada), answer)
        }
    }

    private fun request(): ApprovalRequest {
        val turn = testTurn(chat = telegram.address(ADA)) { actor(ada) }
        return ApprovalRequest.builder("k3x9", turn, "fs.write", ToolRisk.WORKSPACE_WRITE, "{}", "a test").build()
    }

    private fun PluginHarness.Running.telegramChannel() = checkNotNull(get<ChannelDirectory>().channel(WORK))

    /** The request in Ada's chat, once it waits for an answer. */
    private fun asked(): FakeBotMessage =
        fake.await { botMessages(ADA).singleOrNull()?.takeIf { it.buttons.isNotEmpty() } }
}
