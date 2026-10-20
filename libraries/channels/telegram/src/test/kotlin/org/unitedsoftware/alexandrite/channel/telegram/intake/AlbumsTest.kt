package org.unitedsoftware.alexandrite.channel.telegram.intake

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.channel.telegram.WORK
import org.unitedsoftware.alexandrite.channel.telegram.api.Chat
import org.unitedsoftware.alexandrite.channel.telegram.api.Message
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumsTest {
    private val ada = ChatAddress(WORK, "1001")
    private val bob = ChatAddress(WORK, "1002")
    private val dispatched = mutableListOf<List<Long>>()

    private fun TestScope.albums() =
        Albums(backgroundScope, 1500.milliseconds, Any()) { parts -> dispatched += parts.map { it.messageId } }

    private fun message(id: Long, group: String? = null) =
        Message(id, date = 0, chat = Chat(1001, "private"), mediaGroupId = group)

    @Test
    fun `an album goes out as one once no part came for the wait, and the chat's later messages after it`() = runTest {
        val albums = albums()

        assertTrue(albums.hold(ada, message(2, "g")))
        advanceTimeBy(1_000)
        assertTrue(albums.hold(ada, message(1, "g")))
        assertTrue(albums.hold(ada, message(3)))
        assertFalse(albums.hold(bob, message(4)))
        advanceTimeBy(1_000)
        val early = dispatched.toList()
        advanceTimeBy(600)

        assertEquals(emptyList(), early)
        assertEquals(listOf(listOf(1L, 2L), listOf(3L)), dispatched)
    }

    @Test
    fun `an album behind another waits for its own parts once the first goes out`() = runTest {
        val albums = albums()

        listOf(message(1, "a"), message(2), message(3, "b"), message(4, "b"), message(5)).forEach {
            albums.hold(ada, it)
        }
        advanceTimeBy(1_600)
        val first = dispatched.toList()
        advanceTimeBy(1_600)

        assertEquals(listOf(listOf(1L), listOf(2L)), first)
        assertEquals(listOf(listOf(1L), listOf(2L), listOf(3L, 4L), listOf(5L)), dispatched)
    }

    @Test
    fun `releasing all hands every album over at once, and their timers then do nothing`() = runTest {
        val albums = albums()
        albums.hold(ada, message(1, "a"))
        albums.hold(ada, message(2, "a"))
        albums.hold(ada, message(3))
        albums.hold(bob, message(4, "c"))

        albums.releaseAll()
        advanceTimeBy(5_000)

        assertEquals(listOf(listOf(1L, 2L), listOf(3L), listOf(4L)), dispatched)
    }
}
