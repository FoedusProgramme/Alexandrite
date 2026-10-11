package org.foedusprogramme.alexandrite.channel.telegram

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.foedusprogramme.alexandrite.channel.telegram.TelegramHarness.Companion.ADA
import org.foedusprogramme.alexandrite.sdk.channel.ApprovalAnswer
import org.foedusprogramme.alexandrite.sdk.channel.ApprovalRequest
import org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.tool.ToolRisk
import org.foedusprogramme.alexandrite.testkit.testTurn
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramApprovalTest {
    private val telegram = TelegramHarness()
    private val fake = telegram.fake
    private val ada = ChatUser(UserAddress(WORK, "$ADA"), "Ada Lovelace", "ada_test", isBot = false, isAdmin = true)

    @AfterTest
    fun close() {
        telegram.close()
    }

    @Test
    fun `a button pressed in Telegram answers the request, and the message then shows who answered`() {
        val turn = testTurn(chat = telegram.address(ADA)) { actor(ada) }
        val request = ApprovalRequest("k3x9", turn, "fs.write", ToolRisk.WORKSPACE_WRITE, "{}", null, "a test", null)

        telegram.run {
            val channel = checkNotNull(get<ChannelDirectory>().channel(WORK))
            val answer = coroutineScope {
                val asking = async(Dispatchers.Default) { channel.askApproval(request) }
                val asked = fake.await { botMessages(ADA).singleOrNull()?.takeIf { it.buttons.isNotEmpty() } }
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
}
