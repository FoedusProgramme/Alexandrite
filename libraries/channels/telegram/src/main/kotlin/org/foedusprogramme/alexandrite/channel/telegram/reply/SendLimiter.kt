package org.foedusprogramme.alexandrite.channel.telegram.reply

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiException
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure
import org.slf4j.LoggerFactory
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Paces one bot's calls under Telegram's limits and makes a call that got a 429 once more after the wait Telegram
 * asks for.
 *
 * A chat's slot is waited for before the bot's, so a chat that backs off holds up no other chat.
 */
internal class SendLimiter(
    perSecond: Int = 25,
    /** The shortest pause between two sends or edits in a chat. */
    private val chatInterval: Duration = 1.seconds,
    /** The shortest pause between two previews in a direct chat. */
    private val editInterval: Duration = 1.seconds,
    /** The shortest pause between two previews in a group. */
    private val groupEditInterval: Duration = 3.seconds,
    private val maxRetryAfter: Duration = 60.seconds,
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    init {
        require(perSecond > 0) { "perSecond must be positive, was $perSecond." }
    }

    private val globalInterval = 1.seconds / perSecond
    private val lock = Any()
    private var nextGlobal = timeSource.markNow()
    private val chats = HashMap<Long, ChatSlots>()
    private val closed = CompletableDeferred<Unit>()

    /** The chats whose slots are kept. */
    internal val trackedChats: Int get() = synchronized(lock) { chats.size }

    /** Runs a send or an edit in [chat], or a call for no chat when it is null. */
    suspend fun <T> message(chat: Long?, call: suspend () -> T): T = run(chat, paced = true, null, retried = true, call)

    /** Runs a preview's send or edit in [chat], which is a group or a direct chat. */
    suspend fun <T> preview(chat: Long, group: Boolean, call: suspend () -> T): T =
        run(chat, paced = true, if (group) groupEditInterval else editInterval, retried = true, call)

    /** Runs a chat action for [chat] at the bot's pace, never again after a 429. */
    suspend fun <T> action(chat: Long, call: suspend () -> T): T = run(chat, paced = false, null, retried = false, call)

    /** Stops waiting out back-offs and retrying. */
    fun close() {
        closed.complete(Unit)
    }

    private suspend fun <T> run(
        chat: Long?,
        paced: Boolean,
        spacing: Duration?,
        retried: Boolean,
        call: suspend () -> T,
    ): T {
        var retry = retried
        while (true) {
            acquire(chat, paced, spacing)
            try {
                return call()
            } catch (e: BotApiException) {
                if (e.failure != BotApiFailure.RATE_LIMITED) throw e
                val wait = (e.retryAfter ?: 1.seconds).coerceIn(Duration.ZERO, maxRetryAfter)
                backOff(chat, wait)
                if (!retry || closed.isCompleted) {
                    logger.warn("{} (chat {}): not retried", e.message, chat)
                    throw e
                }
                logger.warn("{} (chat {}): retried once in {}", e.message, chat, wait)
                retry = false
            }
        }
    }

    private suspend fun acquire(chat: Long?, paced: Boolean, spacing: Duration?) {
        if (chat != null && spacing != null) {
            pause(
                reserve { now ->
                    val slots = slots(chat, now)
                    maxOf(now, slots.preview).also { slots.preview = it + spacing }
                },
            )
        }
        if (chat != null && paced) {
            pause(
                reserve { now ->
                    val slots = slots(chat, now)
                    maxOf(now, slots.next).also { slots.next = it + chatInterval }
                },
            )
        }
        pause(reserve { now -> maxOf(now, nextGlobal).also { nextGlobal = it + globalInterval } })
    }

    /** The wait until the slot that [take] reserves at the time it is given. */
    private inline fun reserve(take: (now: ComparableTimeMark) -> ComparableTimeMark): Duration = synchronized(lock) {
        val now = timeSource.markNow()
        take(now) - now
    }

    /** Moves the next slot of [chat], or the bot's for no chat, [wait] from now. */
    private fun backOff(chat: Long?, wait: Duration) {
        synchronized(lock) {
            val now = timeSource.markNow()
            if (chat == null) {
                nextGlobal = maxOf(nextGlobal, now + wait)
            } else {
                val slots = slots(chat, now)
                slots.next = maxOf(slots.next, now + wait)
            }
        }
    }

    /** Waits [wait], or less once closed when it is longer than [chatInterval]. */
    private suspend fun pause(wait: Duration) {
        when {
            !wait.isPositive() -> Unit
            wait <= chatInterval -> delay(wait)
            else -> withTimeoutOrNull(wait) { closed.await() }
        }
    }

    /** The slots of [chat], dropping those of chats whose slots have all passed once there are too many. */
    private fun slots(chat: Long, now: ComparableTimeMark): ChatSlots {
        if (chats.size >= MAX_TRACKED_CHATS && chat !in chats) {
            chats.values.removeIf { it.next <= now && it.preview <= now }
        }
        return chats.getOrPut(chat) { ChatSlots(now, now) }
    }

    private class ChatSlots(var next: ComparableTimeMark, var preview: ComparableTimeMark)

    internal companion object {
        const val MAX_TRACKED_CHATS = 1024
    }
}

private val logger = LoggerFactory.getLogger(SendLimiter::class.java)
