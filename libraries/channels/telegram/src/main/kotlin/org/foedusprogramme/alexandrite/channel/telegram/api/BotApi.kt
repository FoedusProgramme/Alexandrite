package org.foedusprogramme.alexandrite.channel.telegram.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import kotlin.time.Duration

/** The Bot API methods the channel calls, each throwing a [BotApiException] when it fails. */
internal interface BotApi {
    suspend fun getMe(): User

    suspend fun getWebhookInfo(): WebhookInfo

    /** The updates from [offset] on, which confirms those before it. */
    suspend fun getUpdates(offset: Long?, limit: Int, timeoutSeconds: Int): List<Update>

    suspend fun sendMessage(request: SendMessage): Message

    suspend fun sendPhoto(request: SendFile): Message

    suspend fun sendDocument(request: SendFile): Message

    suspend fun getFile(fileId: String): TelegramFile

    /** The bytes of the file at [path] of a [TelegramFile], failing as [BotApiFailure.TOO_LONG] past [maxBytes]. */
    suspend fun download(path: String, maxBytes: Long): ByteArray

    suspend fun editMessageText(request: EditMessageText): Message

    suspend fun editMessageReplyMarkup(request: EditMessageReplyMarkup)

    suspend fun deleteMessage(chat: Long, message: Long)

    suspend fun answerCallbackQuery(request: AnswerCallbackQuery)

    /** Shows that the bot is typing in [chat], or in its topic [thread]. */
    suspend fun sendChatAction(chat: Long, thread: Long?)

    suspend fun setMyCommands(request: SetMyCommands)

    suspend fun deleteMyCommands(request: DeleteMyCommands)

    /** Calls [method] with [params], one of the chat actions the agent's tools take, and returns its result. */
    suspend fun act(method: String, params: JsonObject): JsonElement
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

    /** A message that is gone or cannot be edited or deleted. */
    EDIT_TARGET_GONE,

    /** Entities that Telegram cannot apply to the text. */
    ENTITIES_REJECTED,

    /** A text longer than one message, or a file larger than Telegram takes. */
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
