package org.foedusprogramme.alexandrite.channel.telegram.reply

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApi
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiException
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure
import org.foedusprogramme.alexandrite.channel.telegram.intake.BotSession
import org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Shows that the bot is typing in each chat with an open reply, one ticker per chat. */
@ChannelInstanceScoped
internal class Typing(
    private val instance: ChannelInstance,
    private val api: BotApi,
    private val limiter: SendLimiter,
    private val session: BotSession,
) : Lifecycle {
    private val tickers = HashMap<ChatAddress, Ticker>()
    private val ticking = SupervisorJob(instance.scope.coroutineContext[Job])
    private val scope = CoroutineScope(instance.scope.coroutineContext + ticking)

    /** Starts typing in [chat], the Telegram chat [target], for one more reply. */
    fun start(chat: ChatAddress, target: Target) {
        synchronized(tickers) {
            val ticker = tickers.getOrPut(chat, ::Ticker)
            ticker.replies++
            if (ticker.job?.isActive != true) {
                ticker.job = scope.launch(CoroutineName("$chat typing")) { tick(chat, target) }
            }
        }
    }

    /** Stops typing in [chat] for one reply, and for good after the last. */
    fun stop(chat: ChatAddress) {
        synchronized(tickers) {
            val ticker = tickers[chat] ?: return
            if (--ticker.replies > 0) return
            tickers.remove(chat)
            ticker.job?.cancel()
        }
    }

    override fun onStop() {
        ticking.cancel()
        synchronized(tickers) { tickers.clear() }
    }

    private suspend fun tick(chat: ChatAddress, target: Target) {
        withTimeoutOrNull(LIMIT) {
            while (true) {
                try {
                    limiter.action(target.chat) { api.sendChatAction(target.chat, target.thread) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e is BotApiException && e.failure == BotApiFailure.UNAUTHORIZED) session.revoked()
                    logger.debug("{} could not show typing in chat {}: {}", instance.id, chat, e.toString())
                }
                delay(INTERVAL)
            }
        }
    }

    private class Ticker {
        var replies = 0
        var job: Job? = null
    }

    private companion object {
        val INTERVAL = 4.seconds

        /** How long a ticker types at most. */
        val LIMIT = 30.minutes
    }
}

private val logger = LoggerFactory.getLogger(Typing::class.java)
