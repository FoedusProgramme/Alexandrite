package org.foedusprogramme.alexandrite.channel.telegram.intake

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.channel.telegram.TelegramInstanceConfig
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApi
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiException
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure
import org.foedusprogramme.alexandrite.channel.telegram.api.Update
import org.foedusprogramme.alexandrite.channel.telegram.replay.Backlog
import org.foedusprogramme.alexandrite.channel.telegram.replay.Replay
import org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.di.Inject
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Long-polls the bot's updates and dispatches them in order: each poll confirms the updates before it, and so does the
 * close. Before its first poll it hands what the last run left to [resume], which gives the offset that run saved;
 * the offset is saved again after each batch and at the close.
 */
@ChannelInstanceScoped
internal class Poller(
    private val instance: ChannelInstance,
    private val config: TelegramInstanceConfig,
    private val api: BotApi,
    private val intake: (Update) -> Dispatch,
    private val session: BotSession,
    private val random: Random,
    private val timeSource: TimeSource.WithComparableMarks,
    private val resume: suspend () -> Long? = { null },
    private val save: suspend (Long) -> Unit = {},
) : Lifecycle {
    @Inject
    constructor(
        instance: ChannelInstance,
        config: TelegramInstanceConfig,
        api: BotApi,
        intake: Intake,
        session: BotSession,
        backlog: Backlog,
        replay: Replay,
    ) : this(
        instance,
        config,
        api,
        intake::dispatch,
        session,
        Random.Default,
        TimeSource.Monotonic,
        resume = { backlog.resume(intake::replay, replay::unfinished) },
        save = backlog::saveOffset,
    )

    @Volatile
    private var closing = false
    private var loop: Job? = null

    /** The id of the next update to dispatch, null before the first. */
    private var offset: Long? = null

    /** The offset that the last poll confirmed. */
    private var confirmed: Long? = null

    /** The offset that was saved last. */
    private var saved: Long? = null

    override suspend fun onOpen() {
        loop = instance.scope.launch(CoroutineName("${instance.id} polling")) { poll() }
    }

    override suspend fun onClose() {
        closing = true
        loop?.cancelAndJoin()
        val next = offset ?: return
        if (next != saved) save(next)
        if (next == confirmed) return
        try {
            withTimeoutOrNull(CONFIRM_TIMEOUT) { api.getUpdates(next, 1, 0) }
                ?: logger.warn("{} could not confirm its updates before {} in time", instance.id, next)
        } catch (e: BotApiException) {
            logger.warn("{} could not confirm its updates before {}: {}", instance.id, next, e.message)
        }
    }

    private suspend fun poll() {
        offset = resume()
        saved = offset
        val polling = config.polling
        var failures = 0
        var lastConflict: ComparableTimeMark? = null
        while (!closing) {
            val updates = try {
                api.getUpdates(offset, polling.limit, polling.timeoutSeconds)
            } catch (e: BotApiException) {
                failures++
                when (e.failure) {
                    BotApiFailure.UNAUTHORIZED -> {
                        session.revoked()
                        return
                    }

                    BotApiFailure.CONFLICT -> {
                        if (lastConflict == null || lastConflict.elapsedNow() >= CONFLICT_INTERVAL) {
                            lastConflict = timeSource.markNow()
                            logger.error(
                                "{} cannot poll: another program polls this bot, or a webhook was set ({})",
                                instance.id,
                                e.message,
                            )
                        }
                        delay(backOff(failures))
                    }

                    BotApiFailure.RATE_LIMITED -> delay(e.retryAfter ?: 1.seconds)

                    else -> {
                        if (failures == 1) logger.warn("{} cannot poll Telegram, retrying: {}", instance.id, e.message)
                        delay(backOff(failures))
                    }
                }
                continue
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                if (failures == 1) logger.warn("{} cannot poll Telegram, retrying", instance.id, e)
                delay(backOff(failures))
                continue
            }
            if (failures > 0) logger.info("{} polls Telegram again", instance.id)
            failures = 0
            confirmed = offset
            val going = dispatch(updates)
            offset?.takeIf { it != saved }?.let { next ->
                save(next)
                saved = next
            }
            if (!going) return
        }
    }

    /** Dispatches [updates] in order, false once the agent is closing. */
    private fun dispatch(updates: List<Update>): Boolean {
        for (update in updates.sortedBy { it.updateId }) {
            if (closing) return false
            if (offset?.let { update.updateId < it } == true) continue
            val dispatched = try {
                intake(update)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("{} could not dispatch update {}, which is skipped", instance.id, update.updateId, e)
                Dispatch.DONE
            }
            if (dispatched == Dispatch.CLOSING) return false
            offset = update.updateId + 1
        }
        return true
    }

    /** The pause after the [failures]th failed poll in a row: 1 s doubling to the configured longest, ±20 %. */
    private fun backOff(failures: Int): Duration {
        val longest = config.polling.retryMaxSeconds.seconds
        val base = (1.seconds * (1L shl (failures - 1).coerceAtMost(MAX_SHIFT)).toDouble()).coerceAtMost(longest)
        return base * random.nextDouble(1 - JITTER, 1 + JITTER)
    }

    private companion object {
        val CONFIRM_TIMEOUT = 3.seconds
        val CONFLICT_INTERVAL = 1.minutes
        const val JITTER = 0.2
        const val MAX_SHIFT = 16
    }
}

private val logger = LoggerFactory.getLogger(Poller::class.java)
