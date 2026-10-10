package org.foedusprogramme.alexandrite.channel.onebot.mapping

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.foedusprogramme.alexandrite.channel.onebot.protocol.GroupId
import org.foedusprogramme.alexandrite.channel.onebot.protocol.MessageId
import org.foedusprogramme.alexandrite.channel.onebot.protocol.UserId
import org.foedusprogramme.alexandrite.channel.onebot.protocol.event.OneBotEvent
import org.foedusprogramme.alexandrite.channel.onebot.protocol.message.OneBotMessage
import org.foedusprogramme.alexandrite.channel.onebot.protocol.message.OneBotSegment
import org.foedusprogramme.alexandrite.channel.onebot.protocol.result.OneBotFailureKind
import org.foedusprogramme.alexandrite.channel.onebot.protocol.result.OneBotResult
import org.foedusprogramme.alexandrite.sdk.channel.ChannelCapabilities
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure
import org.foedusprogramme.alexandrite.sdk.channel.DisplayFact
import org.foedusprogramme.alexandrite.sdk.channel.IncomingMessage
import org.foedusprogramme.alexandrite.sdk.channel.Markup
import org.foedusprogramme.alexandrite.sdk.channel.MediaAttachment
import org.foedusprogramme.alexandrite.sdk.channel.MediaContent
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatInfo
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.Quote
import org.foedusprogramme.alexandrite.sdk.chat.QuoteTrust
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import java.time.Instant

/**
 * The chats of an OneBot instance, printed the way the rest of Alexandrite reads them.
 *
 * A private chat is `private:<user id>` and a group is `group:<group id>`, the two ids the standard reports. A
 * temporary session, which is a private message that came from inside a group, keeps the group as the chat and the
 * sender as its thread, so that a reply goes back to the sender rather than to the group.
 */
public object OneBotChats {
    /** The prefix of a private chat. */
    public const val PRIVATE: String = "private"

    /** The prefix of a group chat. */
    public const val GROUP: String = "group"

    /** The chat [event] happened in. */
    public fun addressOf(instance: ChannelInstanceId, event: OneBotEvent.Message): ChatAddress = when (event) {
        is OneBotEvent.Message.Private -> if (event.subType == OneBotEvent.Message.GROUP_TEMPORARY) {
            ChatAddress(instance, tempChat(event), thread = event.userId.value)
        } else {
            ChatAddress(instance, "$PRIVATE:${event.userId}")
        }

        is OneBotEvent.Message.Group -> ChatAddress(instance, "$GROUP:${event.groupId}")
    }

    /** The user of [id] in this instance. */
    public fun user(instance: ChannelInstanceId, id: UserId): UserAddress = UserAddress(instance, id.value)

    /** The chat `private:<id>`. */
    public fun privateChat(instance: ChannelInstanceId, id: UserId): ChatAddress =
        ChatAddress(instance, "$PRIVATE:${id.value}")

    /** The chat `group:<id>`. */
    public fun groupChat(instance: ChannelInstanceId, id: GroupId): ChatAddress =
        ChatAddress(instance, "$GROUP:${id.value}")

    /** The group a temporary session came from, as the implementation reported it, or a chat of the sender alone. */
    private fun tempChat(event: OneBotEvent.Message.Private): String {
        val group = (event.raw["group_id"] as? JsonPrimitive)?.content
        return if (group.isNullOrEmpty()) "$PRIVATE:temporary" else "$GROUP:$group"
    }
}

/**
 * The messages of an OneBot instance, in both directions.
 *
 * A segment the SDK has no shape for is not dropped: it becomes a [DisplayFact] on the way in, so the model still
 * reads that it was there, and every message keeps the raw event, so a consumer that needs the original sees it.
 */
public object OneBotMessages {
    /** What an OneBot channel can do, as this module reports it. */
    public fun capabilities(): ChannelCapabilities = ChannelCapabilities.builder()
        .markups(setOf(Markup.PLAIN, Markup.MARKDOWN))
        .streaming(false)
        .proactive(true)
        .maxPartsPerReply(null)
        .build()

    /** The message [event] reported, as Alexandrite reads it. */
    public fun incoming(
        instance: ChannelInstanceId,
        event: OneBotEvent.Message,
        admins: Set<String> = emptySet(),
    ): IncomingMessage {
        val chat = OneBotChats.addressOf(instance, event)
        val segments = event.message.segments
        return IncomingMessage.builder(
            ref = ChannelMessageRef(chat, event.messageId.value),
            sender = sender(instance, event, admins),
            chatInfo = chatInfo(event),
            receivedAt = Instant.ofEpochSecond(event.time),
            forwarded = null,
        )
            .text(segments.filterIsInstance<OneBotSegment.Text>().joinToString("") { it.text })
            .media(media(segments))
            .quote(quote(event, chat))
            .facts(facts(event, segments))
            .build()
    }

    /** The message that sends [message] in [chat]. */
    public fun outgoing(chat: ChatAddress, message: OutboundMessage): OneBotMessage {
        val segments = when (message.markup.id) {
            Markup.MARKDOWN.id -> markdown(message.text)
            else -> listOf(OneBotSegment.Text(message.text))
        }
        val reply = message.replyTo?.let { OneBotSegment.Reply(MessageId(it.id)) }
        return OneBotMessage.ArrayValue(if (reply == null) segments else listOf(reply) + segments)
    }

    /** Whether [chat] is a group, which decides the action a message is sent with. */
    public fun isGroup(chat: ChatAddress): Boolean = chat.chat.startsWith(OneBotChats.GROUP + ":")

    /**
     * The user a temporary session belongs to, null when [chat] is no such session.
     *
     * A temporary session is a message of a group sent privately, and its thread is the user who sent it. A reply has
     * to go there rather than into the group the message came through.
     */
    public fun privateTemporary(chat: ChatAddress): UserId? = chat.thread
        ?.takeIf { isGroup(chat) && it.isNotEmpty() }
        ?.let(::UserId)

    /** The user a private chat belongs to, null when [chat] is no private chat. */
    public fun privateUser(chat: ChatAddress): UserId? = chat.chat
        .takeIf { it.startsWith(OneBotChats.PRIVATE + ":") }
        ?.removePrefix(OneBotChats.PRIVATE + ":")
        ?.takeIf { it.isNotEmpty() }
        ?.let(::UserId)

    /** The group a group chat belongs to, null when [chat] is no group chat. */
    public fun group(chat: ChatAddress): GroupId? = chat.chat
        .takeIf { it.startsWith(OneBotChats.GROUP + ":") }
        ?.removePrefix(OneBotChats.GROUP + ":")
        ?.takeIf { it.isNotEmpty() }
        ?.let(::GroupId)

    /** What a call that ended in [result] means for the delivery of a message to [chat]. */
    public fun delivery(result: OneBotResult<JsonElement>, chat: ChatAddress): Delivery = when (result) {
        is OneBotResult.Ok -> {
            // The data of the answer of a send is either the object that names the message or the message itself,
            // which is what an implementation that answers with the identifier alone sends.
            val data = (result.data as? JsonObject)?.get("data") as? JsonObject ?: result.data
            val id =
                ((data as? JsonObject)?.get("message_id") as? JsonPrimitive)?.content
                    ?: result.echo?.toString()
                    ?: "sent"
            Delivery.Delivered(listOf(ChannelMessageRef(chat, id)))
        }

        is OneBotResult.Async -> Delivery.Delivered(listOf(ChannelMessageRef(chat, "async")))

        is OneBotResult.Failed -> Delivery.NotDelivered(failureKind(result.retcode), result.message)

        is OneBotResult.Unreachable -> Delivery.NotDelivered(
            when (result.failure.kind) {
                OneBotFailureKind.TIMEOUT, OneBotFailureKind.CONNECTION -> DeliveryFailure.TRANSIENT
                OneBotFailureKind.AUTHENTICATION -> DeliveryFailure.FORBIDDEN
                else -> DeliveryFailure.UNKNOWN
            },
            result.failure.message,
        )

        is OneBotResult.Malformed -> Delivery.NotDelivered(DeliveryFailure.UNKNOWN, result.detail)
    }

    /** The text of [message] as segments, where a Markdown image becomes an image segment. */
    private fun markdown(text: String): List<OneBotSegment> {
        val segments = mutableListOf<OneBotSegment>()
        val plain = StringBuilder()
        var at = 0
        for (match in IMAGE.findAll(text)) {
            plain.append(text, at, match.range.first)
            if (plain.isNotEmpty()) {
                segments += OneBotSegment.Text(plain.toString())
                plain.setLength(0)
            }
            segments += OneBotSegment.Image(file = match.groupValues[2])
            at = match.range.last + 1
        }
        plain.append(text, at, text.length)
        if (plain.isNotEmpty()) segments += OneBotSegment.Text(plain.toString())
        return segments
    }

    /** The failure an implementation's [retcode] means, as far as this version can tell. */
    private fun failureKind(retcode: Int): DeliveryFailure = when (retcode) {
        RATE_LIMITED -> DeliveryFailure.RATE_LIMITED
        FORBIDDEN, NOT_ALLOWED -> DeliveryFailure.FORBIDDEN
        TOO_LONG -> DeliveryFailure.TOO_LONG
        NOT_FOUND -> DeliveryFailure.CHAT_GONE
        else -> DeliveryFailure.UNKNOWN
    }

    private fun sender(instance: ChannelInstanceId, event: OneBotEvent.Message, admins: Set<String>): ChatUser {
        val reported = event.sender
        val id = event.userId.value
        val card = reported.card?.takeIf { it.isNotEmpty() }
        return ChatUser(
            address = OneBotChats.user(instance, event.userId),
            displayName = card ?: reported.nickname ?: id,
            username = null,
            isBot = reported.userId == event.selfId.value,
            isAdmin = id in admins || (event is OneBotEvent.Message.Group && reported.isAdmin),
        )
    }

    private fun chatInfo(event: OneBotEvent.Message): ChatInfo = when (event) {
        is OneBotEvent.Message.Private -> ChatInfo(ChatKind.DIRECT, null, null)
        is OneBotEvent.Message.Group -> ChatInfo(ChatKind.GROUP, event.sender.card, null)
    }

    private fun media(segments: List<OneBotSegment>): List<MediaAttachment> = segments.mapNotNull { segment ->
        val attachment = when (segment) {
            is OneBotSegment.Image -> MediaKind.IMAGE to (segment.url ?: segment.file)
            is OneBotSegment.Record -> MediaKind.AUDIO to (segment.url ?: segment.file)
            is OneBotSegment.Video -> MediaKind.VIDEO to (segment.url ?: segment.file)
            else -> return@mapNotNull null
        }
        val (kind, reference) = attachment
        if (reference.isNullOrEmpty()) return@mapNotNull null
        MediaAttachment.builder(kind, mediaType(kind), unread(reference))
            .name(reference.substringAfterLast('/').takeIf { it.isNotEmpty() })
            .build()
    }

    /** The media type this version reports while it cannot read the file itself. */
    private fun mediaType(kind: MediaKind): String = when (kind) {
        MediaKind.IMAGE -> "image/*"
        MediaKind.AUDIO -> "audio/*"
        else -> "video/*"
    }

    /**
     * The bytes of the file an implementation named, which this version does not read yet.
     *
     * Reading it needs the plugin's transport, so it stays the task of whoever asks for the bytes; failing here keeps
     * that visible rather than answering with an empty file.
     */
    private fun unread(reference: String): MediaContent = MediaContent {
        throw UnsupportedOperationException("Reading '$reference' is not implemented yet.")
    }

    private fun quote(event: OneBotEvent.Message, chat: ChatAddress): Quote? {
        val reply = event.message.segments.filterIsInstance<OneBotSegment.Reply>().firstOrNull() ?: return null
        return Quote.builder("")
            .target(ChannelMessageRef(chat, reply.id.value))
            .trust(QuoteTrust.UNTRUSTED)
            .build()
    }

    private fun facts(event: OneBotEvent.Message, segments: List<OneBotSegment>): List<DisplayFact> = buildList {
        if (event is OneBotEvent.Message.Group) {
            event.anonymous?.let { add(DisplayFact("anonymous", it.name ?: it.flag ?: "unnamed")) }
        }
        for (segment in segments) {
            when (segment) {
                is OneBotSegment.Unknown -> add(DisplayFact("unsupported segment", segment.type))
                is OneBotSegment.Json -> add(DisplayFact("json message", segment.data))
                is OneBotSegment.Xml -> add(DisplayFact("xml message", segment.data))
                is OneBotSegment.Forward -> add(DisplayFact("forwarded message", segment.id))
                is OneBotSegment.At -> add(DisplayFact("mentioned", segment.userId))
                is OneBotSegment.Image -> add(DisplayFact("image", segment.file ?: segment.url ?: "unnamed"))
                is OneBotSegment.Record -> add(DisplayFact("voice", segment.file ?: segment.url ?: "unnamed"))
                is OneBotSegment.Video -> add(DisplayFact("video", segment.file ?: segment.url ?: "unnamed"))
                is OneBotSegment.Face -> add(DisplayFact("emoji", segment.id))
                is OneBotSegment.Share -> add(DisplayFact("link", segment.url))
                else -> Unit
            }
        }
    }

    private val IMAGE = Regex("!\\[([^\\]]*)]\\(([^)\\s]+)\\)")

    private const val RATE_LIMITED = 1004

    private const val FORBIDDEN = 1003

    private const val NOT_ALLOWED = 1002

    private const val TOO_LONG = 1005

    private const val NOT_FOUND = 100
}
