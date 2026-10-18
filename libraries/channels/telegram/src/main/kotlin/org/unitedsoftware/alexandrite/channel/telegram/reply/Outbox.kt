package org.unitedsoftware.alexandrite.channel.telegram.reply

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApi
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiException
import org.unitedsoftware.alexandrite.channel.telegram.api.BotApiFailure
import org.unitedsoftware.alexandrite.channel.telegram.api.EditMessageText
import org.unitedsoftware.alexandrite.channel.telegram.api.InlineKeyboardMarkup
import org.unitedsoftware.alexandrite.channel.telegram.api.LinkPreviewOptions
import org.unitedsoftware.alexandrite.channel.telegram.api.ReplyParameters
import org.unitedsoftware.alexandrite.channel.telegram.api.SendFile
import org.unitedsoftware.alexandrite.channel.telegram.api.SendMessage
import org.unitedsoftware.alexandrite.channel.telegram.format.FormattedText
import org.unitedsoftware.alexandrite.channel.telegram.format.formatted
import org.unitedsoftware.alexandrite.channel.telegram.format.parts
import org.unitedsoftware.alexandrite.channel.telegram.intake.BotSession
import org.unitedsoftware.alexandrite.sdk.channel.ChannelInstance
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure
import org.unitedsoftware.alexandrite.sdk.channel.OutboundFile
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatSettings
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import java.util.Locale
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

    /**
     * Sends [message]'s files to [chat] at the chat's pace, each in a message of its own, the first with the message's
     * text as its caption.
     */
    suspend fun sendFiles(chat: ChatAddress, message: OutboundMessage): Delivery {
        val target = targetOf(chat) ?: return Delivery.NotDelivered(DeliveryFailure.CHAT_GONE)
        val caption = message.text.takeIf { it.isNotBlank() }?.let { formatted(it, message.markup) }
        refusal(message.files, caption)?.let { return it }
        val ids = mutableListOf<Long>()
        for ((index, file) in message.files.withIndex()) {
            try {
                ids += paced(target) { upload(target, file, caption.takeIf { index == 0 }) }
            } catch (e: BotApiException) {
                return failed(chat, e).after(ids, message.files.size)
            }
        }
        return Delivery.Delivered(ids.map { ChannelMessageRef(chat, "$it") })
    }

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

    /** Uploads [file] to [target] as a photo where Telegram takes it as one, else as a document, and returns its id. */
    private suspend fun upload(target: Target, file: OutboundFile, caption: FormattedText?): Long {
        val photo = !file.asFile && file.mediaType.lowercase(Locale.ROOT) in PHOTO_TYPES && file.size <= MAX_PHOTO_BYTES
        if (photo) {
            try {
                return uploaded(target, file, caption, photo = true)
            } catch (e: BotApiException) {
                if (e.failure != BotApiFailure.BAD_REQUEST) throw e
                logger.info(
                    "{}: Telegram refused a file as a photo, so it goes as a document: {}",
                    instance.id,
                    e.message,
                )
            }
        }
        return uploaded(target, file, caption, photo = false)
    }

    private suspend fun uploaded(target: Target, file: OutboundFile, caption: FormattedText?, photo: Boolean): Long {
        suspend fun send(text: FormattedText?): Long {
            val request = SendFile(target.chat, file, target.thread, text?.text, text?.wireEntities())
            return (if (photo) api.sendPhoto(request) else api.sendDocument(request)).messageId
        }
        return if (caption == null) whole { send(null) } else withFallback(target, caption) { send(it) }
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

/** Why [files] with [caption] cannot be sent, null when they can. */
private fun refusal(files: List<OutboundFile>, caption: FormattedText?): Delivery.NotDelivered? {
    files.firstOrNull { it.size > MAX_UPLOAD_BYTES }?.let {
        return Delivery.NotDelivered(
            DeliveryFailure.TOO_LONG,
            "the file holds ${it.size} bytes, more than the $MAX_UPLOAD_BYTES bytes a bot may send",
        )
    }
    if (files.any { it.size == 0L }) {
        return Delivery.NotDelivered(DeliveryFailure.UNSUPPORTED, "the file is empty, and Telegram takes no empty file")
    }
    val length = caption?.text?.length ?: 0
    if (length > MAX_CAPTION_LENGTH) {
        return Delivery.NotDelivered(
            DeliveryFailure.TOO_LONG,
            "the caption takes $length characters, more than the $MAX_CAPTION_LENGTH a file's caption may have: " +
                "send a shorter one, and the rest as a reply",
        )
    }
    return null
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

/** The most bytes of a file that a bot may send. */
internal const val MAX_UPLOAD_BYTES: Long = 50L * 1024 * 1024

/** The most bytes of an image that Telegram takes as a photo. */
private const val MAX_PHOTO_BYTES: Long = 10L * 1024 * 1024

private const val MAX_CAPTION_LENGTH = 1024

/** The image types that Telegram takes as photos. */
private val PHOTO_TYPES = setOf("image/jpeg", "image/png", "image/webp")

private val logger = LoggerFactory.getLogger(Outbox::class.java)
