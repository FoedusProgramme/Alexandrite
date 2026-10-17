package org.foedusprogramme.alexandrite.channel.telegram.intake

import org.foedusprogramme.alexandrite.channel.telegram.api.Chat
import org.foedusprogramme.alexandrite.channel.telegram.api.Message
import org.foedusprogramme.alexandrite.channel.telegram.api.MessageEntity
import org.foedusprogramme.alexandrite.channel.telegram.api.MessageFiles
import org.foedusprogramme.alexandrite.channel.telegram.api.MessageOrigin
import org.foedusprogramme.alexandrite.channel.telegram.api.TelegramMedia
import org.foedusprogramme.alexandrite.channel.telegram.api.User
import org.foedusprogramme.alexandrite.channel.telegram.approval.ApprovalMessages
import org.foedusprogramme.alexandrite.sdk.channel.DisplayFact
import org.foedusprogramme.alexandrite.sdk.channel.IncomingMessage
import org.foedusprogramme.alexandrite.sdk.channel.MediaAttachment
import org.foedusprogramme.alexandrite.sdk.channel.MediaContent
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatInfo
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.ForwardKind
import org.foedusprogramme.alexandrite.sdk.chat.ForwardOrigin
import org.foedusprogramme.alexandrite.sdk.chat.Quote
import org.foedusprogramme.alexandrite.sdk.chat.QuoteTrust
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.transcript.MediaKind
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import java.time.Instant
import java.util.Locale

/** The bot as Telegram knows it. */
internal class BotIdentity(val id: Long, val username: String)

/** What a Telegram message is to the channel. */
internal sealed interface Inbound {
    class Message(val message: IncomingMessage) : Inbound

    class Command(val invocation: CommandInvocation, val message: IncomingMessage) : Inbound

    /** A message with content the channel does not pass on and no text. */
    class Unsupported(val chat: ChatAddress, val trigger: ChannelMessageRef) : Inbound

    class Ignored(val why: String) : Inbound
}

/**
 * Reads the messages that the bot [bot] of [instance] receives, whose operators are [admins], with the content of each
 * file from [files]; [answeredAdmin] tells which of the bot's messages answered an admin.
 */
internal class UpdateReader(
    private val instance: ChannelInstanceId,
    private val admins: Set<Long>,
    private val bot: BotIdentity,
    private val approvals: ApprovalMessages,
    private val answeredAdmin: (chat: Long, message: Long) -> Boolean = { _, _ -> false },
    private val files: (fileId: String) -> MediaContent,
) {
    /** The chat of [message], its forum topic as the thread. */
    fun chatOf(message: Message): ChatAddress {
        val topic = message.messageThreadId?.takeIf { message.isTopicMessage }
        return ChatAddress(instance, "${message.chat.id}", topic?.toString())
    }

    fun kindOf(chat: Chat): ChatKind = when (chat.type) {
        "private" -> ChatKind.DIRECT
        "group", "supergroup" -> ChatKind.GROUP
        "channel" -> ChatKind.BROADCAST
        else -> ChatKind.UNKNOWN
    }

    /** [user] as the sender of a message, whom [senderChat] sends on behalf of. */
    fun user(user: User, senderChat: Chat? = null): ChatUser = ChatUser(
        UserAddress(instance, "${user.id}"),
        nameOf(user),
        user.username,
        user.isBot,
        isAdmin = user.id in admins && senderChat == null,
    )

    /** Whether [message] is meant for the bot: any message with content in a direct chat, else one that names it. */
    fun addressed(message: Message): Boolean = if (kindOf(message.chat) == ChatKind.DIRECT) {
        message.text != null || message.caption != null || hasContent(message)
    } else {
        val command = command(message)
        if (command != null) command.forBot else mentionsBot(message) || repliesToBot(message)
    }

    fun read(message: Message): Inbound = read(listOf(message))

    /** What [parts] are to the channel: one message, or the messages of one album in order, as one message. */
    fun read(parts: List<Message>): Inbound {
        val message = parts.first()
        val from = message.from ?: return Inbound.Ignored("it has no sender")
        val chat = chatOf(message)
        val ref = ChannelMessageRef(chat, "${message.messageId}")
        val command = command(message)
        if (command != null && !command.forBot) return Inbound.Ignored("it is a command for @${command.target}")
        if (parts.none(::addressed)) return Inbound.Ignored("it is not addressed to the bot")
        val media = parts.flatMap { media(it, quoted = false) }
        if (parts.all { it.text == null && it.caption == null } && media.isEmpty()) {
            return if (parts.any(::hasContent)) Inbound.Unsupported(chat, ref) else Inbound.Ignored("it is a notice")
        }
        val facts = parts.flatMap(::facts).distinct()
        val quote = quote(message, chat)
        val quoted = (message.replyToMessage?.takeUnless { isTopicRoot(message) } ?: message.externalReply)
            ?.let { media(it, quoted = true) }
        val builder = IncomingMessage.builder(
            ref,
            user(from, message.senderChat),
            ChatInfo(kindOf(message.chat), message.chat.title, message.chat.username),
            Instant.ofEpochSecond(message.date),
            forwarded(message.forwardOrigin),
        )
            .quote(quote)
            .facts(facts)
            .media(media + quoted.orEmpty())
        if (command != null) {
            val text = "/${command.name}" + if (command.arguments.isBlank()) "" else " ${command.arguments}"
            val incoming = builder.text(text).build()
            val invocation = CommandInvocation.builder(chat, command.name, incoming.sender)
                .arguments(command.arguments)
                .trigger(ref)
                .build()
            return Inbound.Command(invocation, incoming)
        }
        val text = parts.mapNotNull { part ->
            val body = part.text ?: part.caption ?: return@mapNotNull null
            val entities = (if (part.text != null) part.entities else part.captionEntities).orEmpty()
            withoutMentions(body, entities).trim().takeIf { it.isNotEmpty() }
        }.joinToString("\n\n")
        if (text.isEmpty() && quote == null && media.isEmpty()) {
            return if (facts.isEmpty()) Inbound.Ignored("it only mentions the bot") else Inbound.Unsupported(chat, ref)
        }
        return Inbound.Message(builder.text(text).build())
    }

    /** The files of [message], each as an attachment of the quoted message when [quoted]. */
    private fun media(message: MessageFiles, quoted: Boolean): List<MediaAttachment> = buildList {
        fun file(kind: MediaKind, mediaType: String, media: TelegramMedia, name: String? = media.fileName) {
            add(attachment(kind, mediaType, media, name, quoted))
        }
        message.photo?.let(::largest)?.let { file(MediaKind.IMAGE, "image/jpeg", it, null) }
        message.document?.takeIf { message.animation == null }?.let { file(documentKind(it), typeOf(it, OCTETS), it) }
        message.audio?.let { file(MediaKind.AUDIO, typeOf(it, "audio/mpeg"), it) }
        message.voice?.let { file(MediaKind.AUDIO, typeOf(it, "audio/ogg"), it, null) }
        message.video?.let { file(MediaKind.VIDEO, typeOf(it, "video/mp4"), it) }
        message.videoNote?.let { file(MediaKind.VIDEO, "video/mp4", it, null) }
        message.animation?.let { file(MediaKind.VIDEO, typeOf(it, "video/mp4"), it) }
    }

    private fun attachment(
        kind: MediaKind,
        mediaType: String,
        media: TelegramMedia,
        name: String?,
        quoted: Boolean,
    ): MediaAttachment {
        val size = media.fileSize?.takeIf { it >= 0 }
        return MediaAttachment.builder(kind, mediaType, files(media.fileId))
            .size(size)
            .width((media.width ?: media.length)?.takeIf { it > 0 })
            .height((media.height ?: media.length)?.takeIf { it > 0 })
            .name(name?.takeIf { it.isNotBlank() })
            .fromQuote(quoted)
            .unavailable(TOO_LARGE.takeIf { size != null && size > MAX_DOWNLOAD_BYTES })
            .build()
    }

    /** The command that [message]'s text starts with, null when it starts with none. */
    private fun command(message: Message): CommandToken? {
        val text = message.text ?: return null
        val entity = message.entities?.firstOrNull() ?: return null
        val token = text.takeWhile { !it.isWhitespace() }
        if (entity.type != "bot_command" || entity.offset != 0 || entity.length != token.length) return null
        val name = token.drop(1).substringBefore('@')
        val target = token.substringAfter('@', "").ifEmpty { null }
        if (name.isEmpty() || name.startsWith('/') || name.any(Char::isISOControl)) return null
        val forBot = target == null || target.equals(bot.username, ignoreCase = true)
        return CommandToken(name, target, text.substring(token.length).trimStart(), forBot)
    }

    private fun mentionsBot(message: Message): Boolean {
        val text = message.text ?: message.caption ?: return false
        val entities = (if (message.text != null) message.entities else message.captionEntities).orEmpty()
        return entities.any { isBotMention(text, it) }
    }

    private fun repliesToBot(message: Message): Boolean =
        !isTopicRoot(message) && message.replyToMessage?.from?.id == bot.id

    /** Whether [message] replies to the root of its forum topic, as every message of a topic does by default. */
    private fun isTopicRoot(message: Message): Boolean =
        message.isTopicMessage && message.replyToMessage?.messageId == message.messageThreadId

    private fun isBotMention(text: String, entity: MessageEntity): Boolean {
        if (entity.offset < 0 || entity.length <= 0 || entity.offset + entity.length > text.length) return false
        return when (entity.type) {
            "mention" -> text.substring(entity.offset, entity.offset + entity.length)
                .equals("@${bot.username}", ignoreCase = true)

            "text_mention" -> entity.user?.id == bot.id

            else -> false
        }
    }

    /** [text] without the mentions of the bot, and without the space each one leaves doubled. */
    private fun withoutMentions(text: String, entities: List<MessageEntity>): String {
        var result = text
        for (entity in entities.filter { isBotMention(text, it) }.sortedByDescending { it.offset }) {
            val start = entity.offset
            result = result.removeRange(start, start + entity.length)
            if (start > 0 && start < result.length && result[start - 1].isWhitespace() && result[start] == ' ') {
                result = result.removeRange(start, start + 1)
            }
        }
        return result
    }

    /** What [message] quotes, an approval message of the bot as the line that stands for it. */
    private fun quote(message: Message, chat: ChatAddress): Quote? {
        val replied = message.replyToMessage?.takeUnless { isTopicRoot(message) }
        if (replied != null) {
            val byBot = replied.from?.id == bot.id && replied.senderChat == null
            val approval = approvals.quoted(message.chat.id, replied.messageId)?.takeIf { byBot }
            val text = approval ?: message.quote?.text ?: replied.text ?: replied.caption ?: ""
            val author = replied.senderChat?.let { Author(it.title, null, null) }
                ?: replied.from?.let { Author(nameOf(it), UserAddress(instance, "${it.id}"), it.isBot) }
            return Quote.builder(text)
                .senderName(author?.name)
                .sender(author?.address)
                .senderIsBot(author?.isBot)
                .target(ChannelMessageRef(chat, "${replied.messageId}"))
                .trust(if (approval != null) VOUCHED else trustOf(replied, byBot))
                .build()
        }
        val external = message.externalReply ?: return null
        val author = authorOf(external.origin)
        return Quote.builder(message.quote?.text ?: "")
            .senderName(author.name)
            .sender(author.address)
            .senderIsBot(author.isBot)
            .external(true)
            .build()
    }

    /**
     * How far a quote of [replied], from this chat, is trusted: its content when an admin wrote it themselves or it is
     * the bot's answer to an admin, which [byBot] sent; its author whenever Telegram names the user who sent it.
     */
    private fun trustOf(replied: Message, byBot: Boolean): QuoteTrust {
        val author = replied.from?.takeIf { replied.senderChat == null } ?: return QuoteTrust.UNTRUSTED
        val own = replied.forwardOrigin == null && replied.viaBot == null
        val vouched = own && if (byBot) answeredAdmin(replied.chat.id, replied.messageId) else author.id in admins
        return if (vouched) VOUCHED else AUTHOR_KNOWN
    }

    private fun forwarded(origin: MessageOrigin?): ForwardOrigin? {
        origin ?: return null
        val author = authorOf(origin)
        val kind = when (origin.type) {
            "user", "hidden_user" -> ForwardKind.USER
            "chat", "channel" -> ForwardKind.CHAT
            else -> ForwardKind.UNKNOWN
        }
        return ForwardOrigin(kind, author.name, author.address, chat = null)
    }

    private fun authorOf(origin: MessageOrigin): Author = when (origin.type) {
        "user" -> origin.senderUser?.let { Author(nameOf(it), UserAddress(instance, "${it.id}"), it.isBot) }
        "hidden_user" -> Author(origin.senderUserName, null, null)
        "chat" -> Author(origin.senderChat?.title, null, null)
        "channel" -> Author(origin.chat?.title, null, null)
        else -> null
    } ?: Author(null, null, null)

    private class CommandToken(val name: String, val target: String?, val arguments: String, val forBot: Boolean)

    private class Author(val name: String?, val address: UserAddress?, val isBot: Boolean?)
}

private fun nameOf(user: User): String = listOfNotNull(user.firstName, user.lastName).joinToString(" ").trim()

private val VOUCHED = QuoteTrust(live = true, content = true)

private val AUTHOR_KNOWN = QuoteTrust(live = true, content = false)

/** Model-facing facts of the media that [message] holds, which the channel does not pass on. */
private fun facts(message: Message): List<DisplayFact> = listOfNotNull(
    DisplayFact("attachment", "sticker, not passed on by this channel").takeIf {
        message.sticker != null
    },
)

/** The largest of a photo's [sizes]. */
private fun largest(sizes: List<TelegramMedia>): TelegramMedia? =
    sizes.maxWithOrNull(compareBy<TelegramMedia>({ (it.width ?: 0).toLong() * (it.height ?: 0) }, { it.fileSize ?: 0 }))

/** The kind of the file a document holds, by its media type. */
private fun documentKind(document: TelegramMedia): MediaKind {
    val type = document.mimeType.orEmpty().lowercase(Locale.ROOT)
    return when {
        type in IMAGE_TYPES -> MediaKind.IMAGE
        type.startsWith("video/") -> MediaKind.VIDEO
        type.startsWith("audio/") -> MediaKind.AUDIO
        else -> MediaKind.FILE
    }
}

/** The media type of [media], [default] when Telegram gives none. */
private fun typeOf(media: TelegramMedia, default: String): String =
    media.mimeType?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) } ?: default

private const val OCTETS = "application/octet-stream"

/** The image types that a document may hold as a picture. */
private val IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

private const val TOO_LARGE = "larger than the 20 MB that a bot may download from Telegram"

/** Whether [message] holds media or other content than text. */
private fun hasContent(message: Message): Boolean = CONTENT.any { it(message) }

private val CONTENT: List<(Message) -> Boolean> = listOf(
    { it.photo != null },
    { it.video != null },
    { it.document != null },
    { it.audio != null },
    { it.voice != null },
    { it.videoNote != null },
    { it.sticker != null },
    { it.animation != null },
    { it.contact != null },
    { it.dice != null },
    { it.game != null },
    { it.poll != null },
    { it.venue != null },
    { it.location != null },
    { it.invoice != null },
    { it.story != null },
    { it.paidMedia != null },
)
