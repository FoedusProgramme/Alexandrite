package org.unitedsoftware.alexandrite.channel.telegram.replay

import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.channel.telegram.WORK
import org.unitedsoftware.alexandrite.channel.telegram.api.Chat
import org.unitedsoftware.alexandrite.channel.telegram.api.Message
import org.unitedsoftware.alexandrite.channel.telegram.intake.Dispatch
import org.unitedsoftware.alexandrite.channel.telegram.intake.TestInstance
import org.unitedsoftware.alexandrite.channel.telegram.intake.testConfig
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.testkit.testRecords
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class BacklogTest {
    @Test
    fun `a replay that throws is skipped and the messages after it are still replayed`() = runTest {
        val backlog = Backlog(TestInstance(backgroundScope), testRecords(), Clock.systemUTC(), testConfig())
        backlog.onStart()
        val chat = Chat(1001, "private")
        val now = Instant.now().epochSecond
        for (id in 1L..3L) {
            val trigger = ChannelMessageRef(ChatAddress(WORK, "1001"), "$id")
            val message = backlog.accepted(trigger, direct = true, listOf(Message(id, date = now + id, chat = chat)))
            backlog.ended(message, replayed = true)
        }
        backlog.saveOffset(4)

        val replayed = mutableListOf<Long>()
        val offset = backlog.resume({ parts ->
            val id = parts.single().messageId
            if (id == 2L) error("the file is gone")
            replayed += id
            Dispatch.DONE
        }) {}

        assertEquals(listOf(1L, 3L), replayed)
        assertEquals(4L, offset)
    }
}
