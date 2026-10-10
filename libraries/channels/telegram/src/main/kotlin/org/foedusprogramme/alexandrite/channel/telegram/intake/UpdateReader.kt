package org.foedusprogramme.alexandrite.channel.telegram.intake

import org.foedusprogramme.alexandrite.channel.telegram.api.Chat
import org.foedusprogramme.alexandrite.channel.telegram.api.Message
import org.foedusprogramme.alexandrite.channel.telegram.api.MessageEntity
import org.foedusprogramme.alexandrite.channel.telegram.api.MessageOrigin
import org.foedusprogramme.alexandrite.channel.telegram.api.User
import org.foedusprogramme.alexandrite.sdk.channel.DisplayFact
import org.foedusprogramme.alexandrite.sdk.channel.IncomingMessage
import org.foedusprogramme.alexandrite.sdk.chat.ChannelInstanceId
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatInfo
import org.foedusprogramme.alexandrite.sdk.chat.ChatKind
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.chat.ForwardKind
import org.foedusprogramme.alexandrite.sdk.chat.ForwardOrigin
import org.foedusprogramme.alexandrite.sdk.chat.Quote
import org.foedusprogramme.alexandrite.sdk.chat.UserAddress
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import java.time.Instant

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

/** Reads the messages that the bot [bot] of [instance] receives, whose operators are [admins]. */
internal class UpdateReader(
    private val instance: ChannelInstanceId,
    private val admins: Set<Long>,
    private val bot: BotIdentity,
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

    fun read(message: Message): Inbound {
        val from = message.from ?: return Inbound.Ignored("it has no sender")
        val chat = chatOf(message)
        val ref = ChannelMessageRef(chat, "${message.messageId}")
        val command = command(message)
        if (command != null && !command.forBot) return Inbound.Ignored("it is a command for @${command.target}")
        if (!addressed(message)) return Inbound.Ignored("it is not addressed to the bot")
        val body = message.text ?: message.caption
            ?: return if (hasContent(message)) Inbound.Unsupported(chat, ref) else Inbound.Ignored("it is a notice")
        val facts = facts(message)
        val quote = quote(message, chat)
        val builder = IncomingMessage.builder(
            ref,
            user(from, message.senderChat),
            ChatInfo(kindOf(message.chat), message.chat.title, message.chat.username),
            Instant.ofEpochSecond(message.date),
            forwarded(message.forwardOrigin),
        )
            .quote(quote)
            .facts(facts)
        if (command != null) {
            val text = "/${command.name}" + if (command.arguments.isBlank()) "" else " ${command.arguments}"
            val incoming = builder.text(text).build()
            val invocation = CommandInvocation.builder(chat, command.name, incoming.sender)
                .arguments(command.arguments)
                .trigger(ref)
                .build()
            return Inbound.Command(invocation, incoming)
        }
        val entities = (if (message.text != null) message.entities else message.captionEntities).orEmpty()
        val text = withoutMentions(body, entities).trim()
        if (text.isEmpty() && quote == null) {
            return if (facts.isEmpty()) Inbound.Ignored("it only mentions the bot") else Inbound.Unsupported(chat, ref)
        }
        return Inbound.Message(builder.text(text).build())
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

    private fun quote(message: Message, chat: ChatAddress): Quote? {
        val replied = message.replyToMessage?.takeUnless { isTopicRoot(message) }
        if (replied != null) {
            val text = message.quote?.text ?: replied.text ?: replied.caption ?: ""
            val author = replied.senderChat?.let { Author(it.title, null, null) }
                ?: replied.from?.let { Author(nameOf(it), UserAddress(instance, "${it.id}"), it.isBot) }
            return Quote.builder(text)
                .senderName(author?.name)
                .sender(author?.address)
                .senderIsBot(author?.isBot)
                .target(ChannelMessageRef(chat, "${replied.messageId}"))
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

/** Model-facing facts of the media that [message] holds, which the channel does not pass on. */
private fun facts(message: Message): List<DisplayFact> = MEDIA
    .filter { (kind, present) -> present(message) && !(kind == "document" && message.animation != null) }
    .map { (kind, _) -> DisplayFact("attachment", "$kind, not passed on by this channel") }

/** Whether [message] holds media or other content than text. */
private fun hasContent(message: Message): Boolean =
    MEDIA.any { (_, present) -> present(message) } || CONTENT.any { it(message) }

private val MEDIA: List<Pair<String, (Message) -> Boolean>> = listOf(
    "photo" to { it.photo != null },
    "video" to { it.video != null },
    "document" to { it.document != null },
    "audio" to { it.audio != null },
    "voice" to { it.voice != null },
    "video note" to { it.videoNote != null },
    "sticker" to { it.sticker != null },
    "animation" to { it.animation != null },
)

private val CONTENT: List<(Message) -> Boolean> = listOf(
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
