package org.foedusprogramme.alexandrite.channel.telegram.reply

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApi
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiException
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure
import org.foedusprogramme.alexandrite.channel.telegram.api.EditMessageText
import org.foedusprogramme.alexandrite.channel.telegram.api.InlineKeyboardMarkup
import org.foedusprogramme.alexandrite.channel.telegram.api.LinkPreviewOptions
import org.foedusprogramme.alexandrite.channel.telegram.api.ReplyParameters
import org.foedusprogramme.alexandrite.channel.telegram.api.SendMessage
import org.foedusprogramme.alexandrite.channel.telegram.format.FormattedText
import org.foedusprogramme.alexandrite.channel.telegram.format.formatted
import org.foedusprogramme.alexandrite.channel.telegram.format.parts
import org.foedusprogramme.alexandrite.channel.telegram.intake.BotSession
import org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatSettings
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Sends and edits the bot's messages at the [SendLimiter]'s pace, as plain text once more where Telegram refuses their
 * entities.
 *
 * A call that started runs to its end.
 */
@ChannelInstanceScoped
internal class Outbox(
    private val instance: ChannelInstance,
    private val api: BotApi,
    private val limiter: SendLimiter,
    private val texts: Texts,
    private val settings: ChatSettings?,
    private val session: BotSession,
) : Lifecycle {
    private val posting = SupervisorJob(instance.scope.coroutineContext[Job])
    private val scope = CoroutineScope(instance.scope.coroutineContext + posting)
    private val warnedEntities = AtomicBoolean()

    /** Sends the text [key] to [chat] without waiting, quoting [trigger] in a group. */
    fun notify(chat: ChatAddress, trigger: ChannelMessageRef?, key: String, vararg args: Pair<String, Any?>) {
        scope.launch { notice(chat, trigger, key, *args) }
    }

    /** Sends the text [key] to [chat], quoting [trigger] in a group. */
    suspend fun notice(chat: ChatAddress, trigger: ChannelMessageRef?, key: String, vararg args: Pair<String, Any?>) {
        val delivery = try {
            deliver(chat, FormattedText(texts.text(key, language(chat), *args)), trigger)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Delivery.NotDelivered(DeliveryFailure.UNKNOWN, e.toString())
        }
        if (delivery is Delivery.NotDelivered) {
            logger.warn("{} could not send the notice {} to chat {}: {}", instance.id, key, chat, delivery)
        }
    }

    /** Sends [message] to [chat] outside any reply. */
    suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery =
        deliver(chat, formatted(message.text, message.markup), message.replyTo)

    /** Runs [call] once [target]'s chat may take its next message. */
    suspend fun <T> paced(target: Target, call: suspend () -> T): T = limiter.message(target.chat, call)

    /** Runs [call] once [target]'s chat may take its next preview. */
    suspend fun <T> previewPaced(target: Target, call: suspend () -> T): T =
        limiter.preview(target.chat, target.group, call)

    /** Sends [text] to [target] without pacing, quoting [replyTo], under [keyboard], and returns the message's id. */
    suspend fun sendText(
        target: Target,
        text: FormattedText,
        replyTo: Long?,
        keyboard: InlineKeyboardMarkup? = null,
    ): Long = withFallback(target, text) {
        val reply = replyTo?.let { ReplyParameters(it, allowSendingWithoutReply = true) }
        val request = SendMessage(target.chat, it.text, target.thread, it.wireEntities(), NO_PREVIEW, reply, keyboard)
        api.sendMessage(request).messageId
    }

    /**
     * Sends [parts] to [target] in turn at the chat's pace, each quoting [replyTo], and returns the delivery of the
     * text whose messages [sent] they follow.
     */
    suspend fun sendParts(
        chat: ChatAddress,
        target: Target,
        parts: List<FormattedText>,
        replyTo: Long?,
        sent: List<Long> = emptyList(),
    ): Delivery {
        val ids = sent.toMutableList()
        for (part in parts) {
            try {
                ids += paced(target) { sendText(target, part, replyTo) }
            } catch (e: BotApiException) {
                return failed(chat, e).after(ids, sent.size + parts.size)
            }
        }
        return Delivery.Delivered(ids.map { ChannelMessageRef(chat, "$it") })
    }

    /** Has the message [message] of [target] read [text], without pacing. */
    suspend fun editText(target: Target, message: Long, text: FormattedText) {
        withFallback(target, text) {
            api.editMessageText(EditMessageText(target.chat, message, it.text, it.wireEntities(), NO_PREVIEW))
        }
    }

    /** The delivery of a message to [chat] that failed with [e]. */
    fun failed(chat: ChatAddress, e: BotApiException): Delivery.NotDelivered = when (e.failure) {
        BotApiFailure.TOO_LONG -> Delivery.NotDelivered(DeliveryFailure.TOO_LONG, e.description)

        BotApiFailure.CHAT_GONE -> {
            e.migrateTo?.let { logger.warn("{}: chat {} became the supergroup {}", instance.id, chat, it) }
            Delivery.NotDelivered(DeliveryFailure.CHAT_GONE, e.description)
        }

        BotApiFailure.FORBIDDEN -> Delivery.NotDelivered(DeliveryFailure.FORBIDDEN, e.description)

        BotApiFailure.RATE_LIMITED ->
            Delivery.NotDelivered(DeliveryFailure.RATE_LIMITED, e.description, retryAfter = e.retryAfter)

        BotApiFailure.TRANSIENT -> Delivery.NotDelivered(DeliveryFailure.TRANSIENT, e.description)

        else -> {
            checkToken(e)
            logger.warn("{} could not deliver a message to chat {}: {}", instance.id, chat, e.message)
            Delivery.NotDelivered(DeliveryFailure.UNKNOWN, e.description)
        }
    }

    /** Stops the runtime where [e] shows that Telegram no longer accepts the bot's token. */
    fun checkToken(e: BotApiException) {
        if (e.failure == BotApiFailure.UNAUTHORIZED) session.revoked()
    }

    override suspend fun onDrain() {
        posting.children.toList().joinAll()
    }

    private suspend fun deliver(chat: ChatAddress, text: FormattedText, replyTo: ChannelMessageRef?): Delivery {
        val target = targetOf(chat)
        if (target == null) {
            logger.warn("{} cannot deliver to {}, which names no Telegram chat", instance.id, chat)
            return Delivery.NotDelivered(DeliveryFailure.CHAT_GONE)
        }
        val parts = partsOf(text) ?: return Delivery.NotDelivered(DeliveryFailure.TOO_LONG)
        return sendParts(chat, target, parts, target.quoteOf(replyTo))
    }

    private suspend fun <T> withFallback(target: Target, text: FormattedText, call: suspend (FormattedText) -> T): T =
        try {
            whole { call(text) }
        } catch (e: BotApiException) {
            if (e.failure != BotApiFailure.ENTITIES_REJECTED || text.entities.isEmpty()) throw e
            if (warnedEntities.compareAndSet(false, true)) {
                logger.warn(
                    "{}: Telegram refused the entities of a message, which went again as plain text: {}",
                    instance.id,
                    e.message,
                )
            }
            paced(target) { whole { call(text.plain()) } }
        }

    private suspend fun <T> whole(call: suspend () -> T): T = withContext(NonCancellable) { call() }

    private suspend fun language(chat: ChatAddress): LanguageTag? = try {
        settings?.language(chat)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.debug("The language of chat {} is unknown: {}", chat, e.toString())
        null
    }

    private companion object {
        val NO_PREVIEW = LinkPreviewOptions(isDisabled = true)
    }
}

/** The messages [text] takes, null when it takes more than [MAX_PARTS_PER_REPLY]. */
internal fun partsOf(text: FormattedText): List<FormattedText>? = text.parts().takeIf { it.size <= MAX_PARTS_PER_REPLY }

/** This failure of a text whose messages [arrived] of [total] reached the chat. */
private fun Delivery.NotDelivered.after(arrived: List<Long>, total: Int): Delivery.NotDelivered {
    if (arrived.isEmpty()) return this
    val parts = "only ${arrived.size} of $total parts arrived, as messages ${arrived.joinToString()}"
    return Delivery.NotDelivered(kind, detail?.let { "$parts: $it" } ?: parts, retryable = false)
}

internal const val MAX_PARTS_PER_REPLY = 10

private val logger = LoggerFactory.getLogger(Outbox::class.java)
