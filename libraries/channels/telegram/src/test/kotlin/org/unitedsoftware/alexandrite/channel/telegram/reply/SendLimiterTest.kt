package org.unitedsoftware.alexandrite.channel.telegram.reply

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiException
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SendLimiterTest {
    private fun TestScope.limiter() = SendLimiter(timeSource = testScheduler.timeSource)

    private fun rateLimited(seconds: Int?) = BotApiException(
        BotApiFailure.RATE_LIMITED,
        "sendMessage",
        "Too Many Requests: retry after $seconds",
        seconds?.seconds,
    )

    // Pacing.

    @Test
    fun `sends to the same chat are spaced by the chat interval`() = runTest {
        val limiter = limiter()
        val at = mutableListOf<Long>()

        repeat(3) { limiter.message(1L) { at += currentTime } }

        assertEquals(listOf(0L, 1_000L, 2_000L), at)
    }

    @Test
    fun `sends to different chats only follow the bot's pace`() = runTest {
        val limiter = limiter()
        val at = mutableListOf<Long>()

        for (chat in 1L..5L) limiter.message(chat) { at += currentTime }

        assertEquals(listOf(0L, 40L, 80L, 120L, 160L), at)
    }

    @Test
    fun `previews are spaced by the group interval in a group and by the edit interval in a direct chat`() = runTest {
        val group = limiter()
        val direct = limiter()
        val inGroup = mutableListOf<Long>()
        val inDirect = mutableListOf<Long>()

        val groupJob = launch { repeat(3) { group.preview(-100L, group = true) { inGroup += currentTime } } }
        val directJob = launch { repeat(3) { direct.preview(7L, group = false) { inDirect += currentTime } } }
        groupJob.join()
        directJob.join()

        assertEquals(listOf(0L, 3_000L, 6_000L), inGroup)
        assertEquals(listOf(0L, 1_000L, 2_000L), inDirect)
    }

    @Test
    fun `a final waits for the chat's slot alone, not for the next preview`() = runTest {
        val limiter = limiter()
        val at = mutableListOf<String>()

        limiter.preview(-100L, group = true) { at += "preview ${currentTime}ms" }
        limiter.message(-100L) { at += "final ${currentTime}ms" }
        limiter.preview(-100L, group = true) { at += "preview ${currentTime}ms" }

        assertEquals(listOf("preview 0ms", "final 1000ms", "preview 3000ms"), at)
    }

    @Test
    fun `calls cancelled while they wait free their slots for the final, whatever order they took them in`() = runTest {
        val limiter = limiter()
        val at = mutableListOf<Long>()

        limiter.message(1L) { at += currentTime }
        val text = launch { limiter.message(1L) { at += currentTime } }
        val activity = launch { limiter.message(1L) { at += currentTime } }
        runCurrent()
        text.cancelAndJoin()
        activity.cancelAndJoin()
        limiter.message(1L) { at += currentTime }

        assertEquals(listOf(0L, 1_000L), at)
    }

    @Test
    fun `a cancelled call frees its slot, and a later one still waits for the one before it`() = runTest {
        val limiter = limiter()
        val at = mutableListOf<Long>()

        limiter.message(1L) { at += currentTime }
        val first = launch { limiter.message(1L) { at += currentTime } }
        val second = launch { limiter.message(1L) { at += currentTime } }
        runCurrent()
        first.cancelAndJoin()
        second.join()
        limiter.message(1L) { at += currentTime }

        assertEquals(listOf(0L, 2_000L, 3_000L), at)
    }

    @Test
    fun `a chat action follows only the bot's pace`() = runTest {
        val limiter = limiter()
        val at = mutableListOf<Long>()

        limiter.message(1L) { at += currentTime }
        repeat(2) { limiter.action(1L) { at += currentTime } }

        assertEquals(listOf(0L, 40L, 80L), at)
    }

    // 429.

    @Test
    fun `a 429 is waited out and the call made once more`() = runTest {
        val limiter = limiter()
        val at = mutableListOf<Long>()

        val result = limiter.message(3L) {
            at += currentTime
            if (at.size == 1) throw rateLimited(2)
            "sent"
        }

        assertEquals("sent", result)
        assertEquals(listOf(0L, 2_000L), at)
    }

    @Test
    fun `a second 429 ends the call and backs the chat off again`() = runTest {
        val limiter = limiter()
        var calls = 0
        val second = rateLimited(5)

        val thrown = assertFailsWith<BotApiException> {
            limiter.message(3L) {
                calls++
                throw if (calls == 1) rateLimited(1) else second
            }
        }
        var next = -1L
        limiter.message(3L) { next = currentTime }

        assertSame(second, thrown)
        assertEquals(2, calls)
        assertEquals(6_000L, next)
    }

    @Test
    fun `a 429 waits what it asks for up to a minute, and a second when it asks for nothing`() = runTest {
        val limiter = limiter()
        for ((asked, waited) in listOf(600 to 60_000L, null to 1_000L)) {
            val at = mutableListOf<Long>()

            limiter.message(asked?.toLong() ?: 1L) {
                at += currentTime
                if (at.size == 1) throw rateLimited(asked)
            }

            assertEquals(waited, at[1] - at[0], "retry_after $asked")
        }
    }

    @Test
    fun `other failures are not retried`() = runTest {
        val limiter = limiter()
        var calls = 0

        val thrown = assertFailsWith<BotApiException> {
            limiter.message(3L) {
                calls++
                throw BotApiException(BotApiFailure.TRANSIENT, "sendMessage")
            }
        }

        assertEquals(BotApiFailure.TRANSIENT, thrown.failure)
        assertEquals(1, calls)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `a 429 in one chat holds up no other chat`() = runTest {
        val limiter = limiter()
        var callsA = 0
        val chatA = launch {
            limiter.message(1L) {
                callsA++
                if (callsA == 1) throw rateLimited(30)
            }
        }
        runCurrent()
        assertEquals(1, callsA)

        var sentB = -1L
        limiter.message(2L) { sentB = currentTime }

        assertTrue(sentB < 1_000L, "chat 2 sent at $sentB ms")
        assertEquals(1, callsA)
        chatA.join()
        assertEquals(2, callsA)
        assertEquals(30_000L, currentTime)
    }

    @Test
    fun `a 429 on a call with no chat backs off the whole bot`() = runTest {
        val limiter = limiter()
        var calls = 0
        val botCall = launch {
            limiter.message(null) {
                calls++
                if (calls == 1) throw rateLimited(5)
            }
        }
        runCurrent()

        var sentB = -1L
        limiter.message(2L) { sentB = currentTime }
        botCall.join()

        assertEquals(2, calls)
        assertTrue(sentB >= 5_000L, "chat 2 sent at $sentB ms")
    }

    @Test
    fun `a 429 on a chat action is not retried and backs off only its chat`() = runTest {
        val limiter = limiter()
        var actions = 0

        val thrown = assertFailsWith<BotApiException> {
            limiter.action(1L) {
                actions++
                throw rateLimited(30)
            }
        }
        var sentOther = -1L
        limiter.message(2L) { sentOther = currentTime }
        var sentOwn = -1L
        limiter.message(1L) { sentOwn = currentTime }

        assertEquals(BotApiFailure.RATE_LIMITED, thrown.failure)
        assertEquals(1, actions)
        assertTrue(sentOther < 1_000L, "chat 2 sent at $sentOther ms")
        assertTrue(sentOwn >= 30_000L, "chat 1 sent at $sentOwn ms")
    }

    // Closing.

    @Test
    fun `once closed a 429 is neither waited out nor retried`() = runTest {
        val limiter = limiter()
        limiter.close()
        var calls = 0

        val thrown = assertFailsWith<BotApiException> {
            limiter.message(1L) {
                calls++
                throw rateLimited(60)
            }
        }

        assertEquals(BotApiFailure.RATE_LIMITED, thrown.failure)
        assertEquals(1, calls)
        assertEquals(0L, currentTime)
    }

    @Test
    fun `a back-off in progress ends when the limiter closes`() = runTest {
        val limiter = limiter()
        val at = mutableListOf<Long>()
        val call = launch {
            limiter.message(1L) {
                at += currentTime
                if (at.size == 1) throw rateLimited(60)
            }
        }

        advanceTimeBy(3.seconds)
        limiter.close()
        call.join()

        assertEquals(listOf(0L, 3_000L), at)
    }

    @Test
    fun `a closed limiter still keeps the preview spacing`() = runTest {
        val limiter = limiter()
        limiter.close()
        val at = mutableListOf<Long>()

        repeat(3) { limiter.preview(-100L, group = true) { at += currentTime } }

        assertEquals(listOf(0L, 3_000L, 6_000L), at)
    }

    @Test
    fun `a closed limiter waits out no 429 that came before, and keeps the chat interval`() = runTest {
        val limiter = limiter()
        assertFailsWith<BotApiException> { limiter.action(1L) { throw rateLimited(30) } }
        limiter.close()
        val at = mutableListOf<Long>()

        repeat(2) { limiter.message(1L) { at += currentTime } }

        assertEquals(listOf(40L, 1_000L), at)
    }

    // Pruning.

    @Test
    fun `chats whose slots have passed are dropped once too many are kept`() = runTest {
        val limiter = limiter()
        for (chat in 0L until SendLimiter.MAX_TRACKED_CHATS) limiter.message(chat) {}
        assertEquals(SendLimiter.MAX_TRACKED_CHATS, limiter.trackedChats)

        limiter.message(0L) {}
        assertEquals(SendLimiter.MAX_TRACKED_CHATS, limiter.trackedChats)

        delay(2.seconds)
        limiter.message(-1L) {}
        assertEquals(1, limiter.trackedChats)
    }
}
