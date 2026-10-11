package org.foedusprogramme.alexandrite.channel.telegram.api

import java.io.IOException
import kotlin.time.Duration

/** The Bot API methods the channel calls, each throwing a [BotApiException] when it fails. */
internal interface BotApi {
    suspend fun getMe(): User

    suspend fun getWebhookInfo(): WebhookInfo

    /** The updates from [offset] on, which confirms those before it. */
    suspend fun getUpdates(offset: Long?, limit: Int, timeoutSeconds: Int): List<Update>

    suspend fun sendMessage(request: SendMessage): Message

    suspend fun editMessageText(request: EditMessageText): Message

    suspend fun editMessageReplyMarkup(request: EditMessageReplyMarkup)

    suspend fun answerCallbackQuery(request: AnswerCallbackQuery)

    /** Shows that the bot is typing in [chat], or in its topic [thread]. */
    suspend fun sendChatAction(chat: Long, thread: Long?)
}

/** A Bot API call that failed. */
internal class BotApiException(
    val failure: BotApiFailure,
    val method: String,
    /** What Telegram said, null when it said nothing. */
    val description: String? = null,
    /** How long Telegram asked to wait. */
    val retryAfter: Duration? = null,
    /** The id of the supergroup that the chat became. */
    val migrateTo: Long? = null,
    cause: Throwable? = null,
    reason: String? = description,
) : IOException("$method failed: ${reason ?: failure}", cause)

/** What a [BotApiException] means for the call. */
internal enum class BotApiFailure {
    /** An edit to the text the message has. */
    NOT_MODIFIED,

    /** An edit of a message that is gone or cannot be edited. */
    EDIT_TARGET_GONE,

    /** Entities that Telegram cannot apply to the text. */
    ENTITIES_REJECTED,

    /** A text longer than one message. */
    TOO_LONG,

    /** A chat or topic that is gone or migrated. */
    CHAT_GONE,

    /** Another refused request. */
    BAD_REQUEST,

    /** A bot token that Telegram does not accept. */
    UNAUTHORIZED,

    /** A chat the bot may not write to. */
    FORBIDDEN,

    /** Another poller or a webhook for the same bot. */
    CONFLICT,

    /** Too many calls. */
    RATE_LIMITED,

    /** A server error, a timeout or a broken connection. */
    TRANSIENT,

    /** An answer that breaks the Bot API. */
    PROTOCOL,
}
