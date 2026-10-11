package org.foedusprogramme.alexandrite.channel.telegram.reply

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.channel.telegram.TelegramInstanceConfig
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiException
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.di.Inject
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.slf4j.LoggerFactory
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Paces one bot's calls under Telegram's limits and makes a call that got a 429 once more after the wait Telegram
 * asks for.
 *
 * A chat's slot is waited for before the bot's, so a chat that backs off holds up no other chat.
 */
@ChannelInstanceScoped
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
) : Lifecycle {
    @Inject
    constructor(config: TelegramInstanceConfig) : this(
        editInterval = config.streaming.editIntervalMillis.milliseconds,
        groupEditInterval = config.streaming.groupEditIntervalMillis.milliseconds,
    )

    init {
        require(perSecond > 0) { "perSecond must be positive, was $perSecond." }
    }

    private val globalInterval = 1.seconds / perSecond
    private val lock = Any()
    private var nextGlobal = timeSource.markNow()

    /** When the bot's last 429 has been waited out. */
    private var globalBackOff = nextGlobal
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

    /** Stops waiting out 429s and retrying. */
    fun close() {
        closed.complete(Unit)
    }

    override suspend fun onClose() {
        close()
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
        val backOff = synchronized(lock) {
            val now = timeSource.markNow()
            val until = if (chat != null && paced) maxOf(globalBackOff, slots(chat, now).backOff) else globalBackOff
            until - now
        }
        if (backOff.isPositive()) withTimeoutOrNull(backOff) { closed.await() }
        if (chat != null && spacing != null) {
            delay(
                reserve { now ->
                    val slots = slots(chat, now)
                    maxOf(now, slots.preview).also { slots.preview = it + spacing }
                },
            )
        }
        if (chat != null && paced) {
            delay(
                reserve { now ->
                    val slots = slots(chat, now)
                    maxOf(now, slots.next).also { slots.next = it + chatInterval }
                },
            )
        }
        delay(reserve { now -> maxOf(now, nextGlobal).also { nextGlobal = it + globalInterval } })
    }

    /** The wait until the slot that [take] reserves at the time it is given. */
    private inline fun reserve(take: (now: ComparableTimeMark) -> ComparableTimeMark): Duration = synchronized(lock) {
        val now = timeSource.markNow()
        take(now) - now
    }

    /** Holds the calls of [chat], or every call for no chat, [wait] from now. */
    private fun backOff(chat: Long?, wait: Duration) {
        synchronized(lock) {
            val now = timeSource.markNow()
            if (chat == null) {
                globalBackOff = maxOf(globalBackOff, now + wait)
            } else {
                val slots = slots(chat, now)
                slots.backOff = maxOf(slots.backOff, now + wait)
            }
        }
    }

    /** The slots of [chat], dropping those of chats whose slots have all passed once there are too many. */
    private fun slots(chat: Long, now: ComparableTimeMark): ChatSlots {
        if (chats.size >= MAX_TRACKED_CHATS && chat !in chats) {
            chats.values.removeIf { it.next <= now && it.preview <= now && it.backOff <= now }
        }
        return chats.getOrPut(chat) { ChatSlots(now, now, now) }
    }

    private class ChatSlots(
        var next: ComparableTimeMark,
        var preview: ComparableTimeMark,
        /** When the chat's last 429 has been waited out. */
        var backOff: ComparableTimeMark,
    )

    internal companion object {
        const val MAX_TRACKED_CHATS = 1024
    }
}

private val logger = LoggerFactory.getLogger(SendLimiter::class.java)
