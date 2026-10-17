package org.foedusprogramme.alexandrite.channel.telegram.intake

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import org.foedusprogramme.alexandrite.channel.telegram.PollingConfig
import org.foedusprogramme.alexandrite.channel.telegram.TelegramInstanceConfig
import org.foedusprogramme.alexandrite.channel.telegram.WORK
import org.foedusprogramme.alexandrite.channel.telegram.api.AnswerCallbackQuery
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApi
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiException
import org.foedusprogramme.alexandrite.channel.telegram.api.BotApiFailure
import org.foedusprogramme.alexandrite.channel.telegram.api.Chat
import org.foedusprogramme.alexandrite.channel.telegram.api.DeleteMyCommands
import org.foedusprogramme.alexandrite.channel.telegram.api.EditMessageReplyMarkup
import org.foedusprogramme.alexandrite.channel.telegram.api.EditMessageText
import org.foedusprogramme.alexandrite.channel.telegram.api.InlineKeyboardMarkup
import org.foedusprogramme.alexandrite.channel.telegram.api.Message
import org.foedusprogramme.alexandrite.channel.telegram.api.MessageEntity
import org.foedusprogramme.alexandrite.channel.telegram.api.SendFile
import org.foedusprogramme.alexandrite.channel.telegram.api.SendMessage
import org.foedusprogramme.alexandrite.channel.telegram.api.SetMyCommands
import org.foedusprogramme.alexandrite.channel.telegram.api.TelegramFile
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

/**
 * A [BotApi] in memory whose polls answer what the test queued, a poll with nothing queued waiting for ever, and which
 * keeps the bot's messages.
 */
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

    /** Each send, edit and chat action, in order. */
    val sent = mutableListOf<SentCall>()

    /** What each message of the bot reads, by chat and message id. */
    val messages = HashMap<Pair<Long, Long>, String>()

    /** The inline keyboard of each message of the bot that has one, by chat and message id. */
    val keyboards = HashMap<Pair<Long, Long>, InlineKeyboardMarkup>()

    /** The answers to button presses, in order. */
    val answers = mutableListOf<AnswerCallbackQuery>()

    /** How long each send and edit takes to answer. */
    var latency: Duration = Duration.ZERO

    /** The files that `getFile` and `download` serve, by file id. */
    val files = HashMap<String, ByteArray>()

    /** The files the bot sent, in order. */
    val uploads = mutableListOf<SendFile>()

    private val failures = HashMap<String, ArrayDeque<BotApiException>>()
    private var lastMessage = 100L

    override suspend fun getMe(): User = started("getMe") { me }

    override suspend fun getWebhookInfo(): WebhookInfo = started("getWebhookInfo") { WebhookInfo(webhook) }

    override suspend fun getUpdates(offset: Long?, limit: Int, timeoutSeconds: Int): List<Update> {
        offsets += offset
        pollTimes += clock()
        val next = polls.removeFirstOrNull() ?: awaitCancellation()
        return next()
    }

    override suspend fun sendMessage(request: SendMessage): Message {
        val reply = request.replyParameters?.messageId
        val thread = request.messageThreadId
        sent += SentCall("sendMessage", request.chatId, null, request.text, request.entities, reply, thread)
            .also { it.previewDisabled = request.linkPreviewOptions?.isDisabled }
        answer("sendMessage")
        val id = ++lastMessage
        messages[request.chatId to id] = request.text
        request.replyMarkup?.let { keyboards[request.chatId to id] = it }
        return Message(id, date = 0, chat = Chat(request.chatId, "private"), text = request.text)
    }

    override suspend fun sendPhoto(request: SendFile): Message = upload("sendPhoto", request)

    override suspend fun sendDocument(request: SendFile): Message = upload("sendDocument", request)

    override suspend fun getFile(fileId: String): TelegramFile {
        answer("getFile")
        val bytes = files[fileId] ?: throw BotApiException(BotApiFailure.BAD_REQUEST, "getFile", "invalid file_id")
        return TelegramFile(fileId, bytes.size.toLong(), "documents/$fileId")
    }

    override suspend fun download(path: String, maxBytes: Long): ByteArray {
        answer("download")
        val bytes = files.getValue(path.removePrefix("documents/"))
        if (bytes.size > maxBytes) throw BotApiException(BotApiFailure.TOO_LONG, "download", "too large")
        return bytes
    }

    override suspend fun editMessageText(request: EditMessageText): Message {
        val id = request.messageId
        sent += SentCall("editMessageText", request.chatId, id, request.text, request.entities)
            .also { it.previewDisabled = request.linkPreviewOptions?.isDisabled }
        answer("editMessageText")
        if (request.chatId to id !in messages) {
            throw BotApiException(BotApiFailure.EDIT_TARGET_GONE, "editMessageText", "message to edit not found")
        }
        messages[request.chatId to id] = request.text
        keyboards -= request.chatId to id
        return Message(id, date = 0, chat = Chat(request.chatId, "private"), text = request.text)
    }

    override suspend fun editMessageReplyMarkup(request: EditMessageReplyMarkup) {
        sent += SentCall("editMessageReplyMarkup", request.chatId, request.messageId)
        answer("editMessageReplyMarkup")
        val key = request.chatId to request.messageId
        if (key !in messages) {
            throw BotApiException(BotApiFailure.EDIT_TARGET_GONE, "editMessageReplyMarkup", "message to edit not found")
        }
        request.replyMarkup?.let { keyboards[key] = it } ?: keyboards.remove(key)
    }

    override suspend fun deleteMessage(chat: Long, message: Long) {
        sent += SentCall("deleteMessage", chat, message)
        answer("deleteMessage")
        if (chat to message !in messages) {
            throw BotApiException(BotApiFailure.EDIT_TARGET_GONE, "deleteMessage", "message to delete not found")
        }
        messages -= chat to message
        keyboards -= chat to message
    }

    override suspend fun answerCallbackQuery(request: AnswerCallbackQuery) {
        answers += request
        failures["answerCallbackQuery"]?.removeFirstOrNull()?.let { throw it }
    }

    override suspend fun sendChatAction(chat: Long, thread: Long?) {
        sent += SentCall("sendChatAction", chat, thread = thread)
        failures["sendChatAction"]?.removeFirstOrNull()?.let { throw it }
    }

    override suspend fun setMyCommands(request: SetMyCommands) {}

    override suspend fun deleteMyCommands(request: DeleteMyCommands) {}

    private suspend fun upload(method: String, request: SendFile): Message {
        val caption = request.caption
        sent += SentCall(method, request.chatId, null, caption, request.captionEntities, thread = request.thread)
        answer(method)
        uploads += request
        val id = ++lastMessage
        messages[request.chatId to id] = caption.orEmpty()
        return Message(id, date = 0, chat = Chat(request.chatId, "private"), caption = caption)
    }

    /** Fails the next call of [method] with [failure]. */
    fun fail(method: String, failure: BotApiFailure, retryAfter: Duration? = null, migrateTo: Long? = null) {
        val exception = BotApiException(failure, method, "scripted", retryAfter, migrateTo)
        failures.getOrPut(method) { ArrayDeque() } += exception
    }

    /** The calls of [method], or of every method. */
    fun calls(method: String? = null): List<SentCall> = sent.filter { method == null || it.method == method }

    private suspend fun answer(method: String) {
        if (latency.isPositive()) delay(latency)
        failures[method]?.removeFirstOrNull()?.let { throw it }
    }

    /** A send, edit or chat action made at [at] by [clock]. */
    inner class SentCall(
        val method: String,
        val chat: Long,
        val message: Long? = null,
        val text: String? = null,
        entities: List<MessageEntity>? = null,
        val replyTo: Long? = null,
        val thread: Long? = null,
    ) {
        val at: Long = clock()
        val entities: List<String> = entities.orEmpty().map { "${it.type} ${it.offset}+${it.length}" }

        /** Whether the call turned link previews off, null when it said nothing. */
        var previewDisabled: Boolean? = null

        override fun toString(): String = "$method at $at in $chat ${message ?: ""}: $text $entities"
    }

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
