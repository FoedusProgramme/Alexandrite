package org.unitedsoftware.alexandrite.channel.telegram.reply

import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress

/** A Telegram chat, or a forum topic of one, by the ids the Bot API takes. */
internal class Target(val chat: Long, val thread: Long?) {
    /** Whether the chat is a group, which Telegram gives a negative id. */
    val group: Boolean get() = chat < 0

    /**
     * The id of the message [ref] when it is in this chat and this is a group, or any chat where [anyChat], null
     * otherwise.
     */
    fun quoteOf(ref: ChannelMessageRef?, anyChat: Boolean = false): Long? =
        ref?.takeIf { (group || anyChat) && it.chat.chat == "$chat" }?.id?.toLongOrNull()
}

/** The Telegram chat of [address], null when it names none. */
internal fun targetOf(address: ChatAddress): Target? {
    val chat = address.chat.toLongOrNull() ?: return null
    val thread = address.thread?.let { it.toLongOrNull() ?: return null }
    return Target(chat, thread)
}
