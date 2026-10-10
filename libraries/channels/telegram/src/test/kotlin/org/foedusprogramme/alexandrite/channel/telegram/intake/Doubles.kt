package org.foedusprogramme.alexandrite.channel.telegram.intake

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import org.foedusprogramme.alexandrite.channel.telegram.PollingConfig
import org.foedusprogramme.alexandrite.channel.telegram.TelegramInstanceConfig
import org.foedusprogramme.alexandrite.channel.telegram.WORK
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApi
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiException
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure
import org.foedusprogramme.alexandrite.channel.telegram.api.EditMessageText
import org.foedusprogramme.alexandrite.channel.telegram.api.Message
import org.foedusprogramme.alexandrite.channel.telegram.api.SendMessage
import org.foedusprogramme.alexandrite.channel.telegram.api.Update
import org.foedusprogramme.alexandrite.channel.telegram.api.User
import org.foedusprogramme.alexandrite.channel.telegram.api.WebhookInfo
import org.foedusprogramme.alexandrite.channel.telegram.fake.FAKE_BOT_TOKEN
import org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.config.Secret
import org.foedusprogramme.alexandrite.sdk.runtime.RuntimeControl
import org.foedusprogramme.alexandrite.sdk.runtime.StopRequest
import kotlin.time.Duration

/** A [BotApi] in memory whose calls answer what the test queued, a poll with nothing queued waiting for ever. */
internal class MemoryBotApi : BotApi {
    val me = User(1234567890, isBot = true, firstName = "Alexandrite Test Bot", username = "alexandrite_test_bot")

    /** What each call of `getMe` and `getWebhookInfo` does before it answers, in order. */
    val starts = ArrayDeque<BotApiFailure?>()

    /** What each `getUpdates` answers, in order. */
    val polls = ArrayDeque<() -> List<Update>>()

    /** The offset of each `getUpdates`, in order. */
    val offsets = mutableListOf<Long?>()

    /** When each `getUpdates` was made, by [clock]. */
    val pollTimes = mutableListOf<Long>()

    var clock: () -> Long = { 0 }

    var webhook = ""

    override suspend fun getMe(): User = started("getMe") { me }

    override suspend fun getWebhookInfo(): WebhookInfo = started("getWebhookInfo") { WebhookInfo(webhook) }

    override suspend fun getUpdates(offset: Long?, limit: Int, timeoutSeconds: Int): List<Update> {
        offsets += offset
        pollTimes += clock()
        val next = polls.removeFirstOrNull() ?: awaitCancellation()
        return next()
    }

    override suspend fun sendMessage(request: SendMessage): Message = throw UnsupportedOperationException()

    override suspend fun editMessageText(request: EditMessageText): Message = throw UnsupportedOperationException()

    override suspend fun sendChatAction(chat: Long, thread: Long?) = throw UnsupportedOperationException()

    fun failPoll(failure: BotApiFailure, retryAfter: Duration? = null) {
        polls += { throw BotApiException(failure, "getUpdates", "scripted", retryAfter) }
    }

    private fun <T> started(method: String, answer: () -> T): T {
        starts.removeFirstOrNull()?.let { throw BotApiException(it, method, "scripted") }
        return answer()
    }
}

internal class TestInstance(override val scope: CoroutineScope, override val id: ChannelInstanceId = WORK) :
    ChannelInstance

internal class RecordingControl : RuntimeControl {
    val requests = mutableListOf<StopRequest>()

    override fun stop(request: StopRequest) {
        requests += request
    }
}

internal fun testConfig(allowed: List<Long> = listOf(1001), retryMaxSeconds: Int = 30): TelegramInstanceConfig {
    val polling = PollingConfig(retryMaxSeconds = retryMaxSeconds)
    return TelegramInstanceConfig(Secret(FAKE_BOT_TOKEN), listOf(1001), allowed, polling = polling)
}
