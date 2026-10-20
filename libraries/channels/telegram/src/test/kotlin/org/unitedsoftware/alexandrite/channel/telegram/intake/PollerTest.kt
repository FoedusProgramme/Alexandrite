package org.unitedsoftware.alexandrite.channel.telegram.intake

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.unitedsoftware.alexandrite.channel.telegram.TelegramBots
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiFailure
import org.unitedsoftware.alexandrite.channel.telegram.api.Update
import org.unitedsoftware.alexandrite.channel.telegram.logged
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PollerTest {
    private val api = MemoryBotApi()
    private val control = RecordingControl()
    private val dispatched = mutableListOf<Long>()
    private var answer: (Update) -> Dispatch = { Dispatch.DONE }

    private var savedOffset: Long? = null
    private val saves = mutableListOf<Long>()

    private fun TestScope.poller(retryMaxSeconds: Int = 30): Poller {
        api.clock = { currentTime }
        val instance = TestInstance(backgroundScope)
        val session = BotSession(instance, testConfig(), api, TelegramBots(), control)
        val intake = { update: Update ->
            dispatched += update.updateId
            answer(update)
        }
        val config = testConfig(retryMaxSeconds = retryMaxSeconds)
        val resume = suspend { savedOffset }
        val save: suspend (Long) -> Unit = { saves += it }
        return Poller(instance, config, api, intake, session, Random(7), testScheduler.timeSource, resume, save)
    }

    private fun batch(vararg ids: Long): () -> List<Update> = { ids.map { Update(it) } }

    @Test
    fun `updates go out in order, each poll confirms those before it and the close confirms the last`() = runTest {
        api.polls += batch(6, 5)
        api.polls += batch(7)
        val poller = poller()

        poller.onOpen()
        runCurrent()
        api.polls += batch()
        poller.onClose()

        assertEquals(listOf(5L, 6L, 7L), dispatched)
        assertEquals(listOf(null, 7L, 8L, 8L), api.offsets)
    }

    @Test
    fun `polling starts from the saved offset, which each batch and the close save again`() = runTest {
        savedOffset = 5
        api.polls += batch(5, 6)
        api.polls += batch(7)
        val poller = poller()

        poller.onOpen()
        runCurrent()
        api.polls += batch()
        poller.onClose()

        assertEquals(listOf(5L, 6L, 7L), dispatched)
        assertEquals(listOf<Long?>(5, 7, 8, 8), api.offsets)
        assertEquals(listOf(7L, 8L), saves)
    }

    @Test
    fun `an update whose dispatch throws is logged by its id and skipped`() = runTest {
        api.polls += batch(5, 6)
        answer = { if (it.updateId == 5L) error("A bug") else Dispatch.DONE }
        val poller = poller()

        val lines = logged {
            poller.onOpen()
            runCurrent()
        }

        assertEquals(listOf(5L, 6L), dispatched)
        assertEquals(listOf(null, 7L), api.offsets)
        val error = lines.first { it.startsWith("ERROR") }
        assertContains(error, "telegram:work could not dispatch update 5, which is skipped")
        poller.onClose()
    }

    @Test
    fun `failed polls back off from a second, doubling up to the longest, with one warning a streak`() = runTest {
        repeat(4) { api.failPoll(BotApiFailure.TRANSIENT) }
        api.polls += batch(5)
        val poller = poller(retryMaxSeconds = 4)

        val lines = logged {
            poller.onOpen()
            advanceTimeBy(1.minutes)
        }

        assertEquals(6, api.pollTimes.size)
        val gaps = api.pollTimes.zipWithNext { a, b -> b - a }.take(4)
        for ((gap, base) in gaps.zip(listOf(1000L, 2000L, 4000L, 4000L))) {
            assertTrue(gap in base * 8 / 10..base * 12 / 10, "$gap after $base")
        }
        assertEquals(listOf(5L), dispatched)
        assertEquals(1, lines.count { it.startsWith("WARN") && "cannot poll Telegram, retrying" in it }, "$lines")
        assertEquals(1, lines.count { it.startsWith("INFO") && "polls Telegram again" in it }, "$lines")
        poller.onClose()
    }

    @Test
    fun `a rate limit waits as long as Telegram asks`() = runTest {
        api.failPoll(BotApiFailure.RATE_LIMITED, retryAfter = 7.seconds)
        val poller = poller()

        poller.onOpen()
        advanceTimeBy(1.minutes)

        assertEquals(listOf(0L, 7000L), api.pollTimes)
        poller.onClose()
    }

    @Test
    fun `a conflict is an error at most once a minute`() = runTest {
        repeat(5) { api.failPoll(BotApiFailure.CONFLICT) }
        api.polls += batch(5)
        val poller = poller(retryMaxSeconds = 20)

        val lines = logged {
            poller.onOpen()
            advanceTimeBy(1.minutes)
        }

        assertEquals(7, api.pollTimes.size)
        assertEquals(listOf(5L), dispatched)
        assertEquals(1, lines.count { it.startsWith("ERROR") && "another program polls this bot" in it }, "$lines")
        poller.onClose()
    }
}
