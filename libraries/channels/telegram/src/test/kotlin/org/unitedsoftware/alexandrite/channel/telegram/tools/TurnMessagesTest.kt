package org.unitedsoftware.alexandrite.channel.telegram.tools

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.channel.telegram.api.Chat
import org.unitedsoftware.alexandrite.channel.telegram.api.Message
import org.unitedsoftware.alexandrite.channel.telegram.reply.DIRECT
import org.unitedsoftware.alexandrite.channel.telegram.reply.Output
import org.unitedsoftware.alexandrite.channel.telegram.tools.ChatDesk.Companion.QUOTED
import org.unitedsoftware.alexandrite.channel.telegram.tools.ChatDesk.Companion.THIS
import org.unitedsoftware.alexandrite.sdk.channel.ReplyRequest
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.testkit.testTurn
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TurnMessagesTest {
    @Test
    fun `a turn names the message it answers once its reply opens, before its submission returns`() = runTest {
        val output = Output(this)
        val chat = output.address()
        val trigger = ChannelMessageRef(chat, "41")
        val earlier = Message(40, date = 0, chat = Chat(DIRECT, "private"), text = "Earlier.")
        val message = Message(41, date = 0, chat = Chat(DIRECT, "private"), text = "React.", replyToMessage = earlier)
        output.turns.submitted(trigger, message)
        val turn = testTurn(chat = chat)

        output.channel.openReply(ReplyRequest(turn, trigger))

        assertEquals(41L to 40L, output.desk.message(turn, null, THIS) to output.desk.message(turn, null, QUOTED))
    }
}
