package org.foedusprogramme.alexandrite.channel.telegram.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** The envelope of every Bot API answer. */
@Serializable
internal class Envelope<T>(
    val ok: Boolean = false,
    val result: T? = null,
    val description: String? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    val parameters: ResponseParameters? = null,
)

@Serializable
internal class ResponseParameters(
    /** Seconds to wait before the next call. */
    @SerialName("retry_after") val retryAfter: Long? = null,
    /** The id of the supergroup that the chat became. */
    @SerialName("migrate_to_chat_id") val migrateToChatId: Long? = null,
)

@Serializable
internal class Update(@SerialName("update_id") val updateId: Long, val message: Message? = null)

/** A message, its media and other content kept only as present or absent. */
@Serializable
internal class Message(
    @SerialName("message_id") val messageId: Long,
    @SerialName("message_thread_id") val messageThreadId: Long? = null,
    @SerialName("is_topic_message") val isTopicMessage: Boolean = false,
    val from: User? = null,
    @SerialName("sender_chat") val senderChat: Chat? = null,
    /** Unix seconds. */
    val date: Long,
    val chat: Chat,
    @SerialName("forward_origin") val forwardOrigin: MessageOrigin? = null,
    @SerialName("reply_to_message") val replyToMessage: Message? = null,
    @SerialName("external_reply") val externalReply: ExternalReplyInfo? = null,
    val quote: TextQuote? = null,
    @SerialName("via_bot") val viaBot: User? = null,
    @SerialName("edit_date") val editDate: Long? = null,
    val text: String? = null,
    val entities: List<MessageEntity>? = null,
    val caption: String? = null,
    @SerialName("caption_entities") val captionEntities: List<MessageEntity>? = null,
    val animation: JsonElement? = null,
    val audio: JsonElement? = null,
    val document: JsonElement? = null,
    val photo: JsonElement? = null,
    val sticker: JsonElement? = null,
    val story: JsonElement? = null,
    val video: JsonElement? = null,
    @SerialName("video_note") val videoNote: JsonElement? = null,
    val voice: JsonElement? = null,
    @SerialName("paid_media") val paidMedia: JsonElement? = null,
    val contact: JsonElement? = null,
    val dice: JsonElement? = null,
    val game: JsonElement? = null,
    val poll: JsonElement? = null,
    val venue: JsonElement? = null,
    val location: JsonElement? = null,
    val invoice: JsonElement? = null,
)

@Serializable
internal class User(
    val id: Long,
    @SerialName("is_bot") val isBot: Boolean = false,
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String? = null,
    val username: String? = null,
    /** Whether the bot's privacy mode is off, null outside `getMe`. */
    @SerialName("can_read_all_group_messages") val canReadAllGroupMessages: Boolean? = null,
)

@Serializable
internal class Chat(
    val id: Long,
    /** `private`, `group`, `supergroup` or `channel`. */
    val type: String,
    val title: String? = null,
    val username: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("is_forum") val isForum: Boolean = false,
)

/** A span of a text, its offset and length in UTF-16 code units. */
@Serializable
internal class MessageEntity(
    val type: String,
    val offset: Int,
    val length: Int,
    val url: String? = null,
    val user: User? = null,
    val language: String? = null,
    @SerialName("custom_emoji_id") val customEmojiId: String? = null,
)

/** Where a forwarded message came from, flat over the origin types. */
@Serializable
internal class MessageOrigin(
    /** `user`, `hidden_user`, `chat` or `channel`. */
    val type: String,
    val date: Long = 0,
    @SerialName("sender_user") val senderUser: User? = null,
    @SerialName("sender_user_name") val senderUserName: String? = null,
    @SerialName("sender_chat") val senderChat: Chat? = null,
    val chat: Chat? = null,
    @SerialName("message_id") val messageId: Long? = null,
    @SerialName("author_signature") val authorSignature: String? = null,
)

/** The part of a replied message that the user quoted. */
@Serializable
internal class TextQuote(
    val text: String,
    val entities: List<MessageEntity>? = null,
    val position: Int = 0,
    @SerialName("is_manual") val isManual: Boolean = false,
)

/** A replied message from another chat or topic. */
@Serializable
internal class ExternalReplyInfo(
    val origin: MessageOrigin,
    val chat: Chat? = null,
    @SerialName("message_id") val messageId: Long? = null,
)

@Serializable
internal class WebhookInfo(
    /** Empty when no webhook is set. */
    val url: String = "",
    @SerialName("pending_update_count") val pendingUpdateCount: Int = 0,
    @SerialName("last_error_message") val lastErrorMessage: String? = null,
)

@Serializable
internal class SendMessage(
    @SerialName("chat_id") val chatId: Long,
    val text: String,
    @SerialName("message_thread_id") val messageThreadId: Long? = null,
    val entities: List<MessageEntity>? = null,
    @SerialName("link_preview_options") val linkPreviewOptions: LinkPreviewOptions? = null,
    @SerialName("reply_parameters") val replyParameters: ReplyParameters? = null,
)

@Serializable
internal class EditMessageText(
    @SerialName("chat_id") val chatId: Long,
    @SerialName("message_id") val messageId: Long,
    val text: String,
    val entities: List<MessageEntity>? = null,
    @SerialName("link_preview_options") val linkPreviewOptions: LinkPreviewOptions? = null,
)

@Serializable
internal class LinkPreviewOptions(@SerialName("is_disabled") val isDisabled: Boolean)

@Serializable
internal class ReplyParameters(
    @SerialName("message_id") val messageId: Long,
    @SerialName("allow_sending_without_reply") val allowSendingWithoutReply: Boolean? = null,
)

@Serializable
internal class GetUpdates(
    val offset: Long?,
    val limit: Int,
    val timeout: Int,
    @SerialName("allowed_updates") val allowedUpdates: List<String>,
)

@Serializable
internal class SendChatAction(
    @SerialName("chat_id") val chatId: Long,
    @SerialName("message_thread_id") val messageThreadId: Long?,
    val action: String,
)
