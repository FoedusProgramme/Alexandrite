package org.unitedsoftware.alexandrite.channel.telegram.reply

import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress

/** A Telegram chat, or a forum topic of one, by the ids the Bot API takes. */
internal class Target(val chat: Long, val thread: Long?) {
    /** Whether the chat is a group, which Telegram gives a negative id. */
    val group: Boolean get() = chat < 0

    /** The id of the message [ref] when this is a group and the message is in it, null otherwise. */
    fun quoteOf(ref: ChannelMessageRef?): Long? = ref?.takeIf { group && it.chat.chat == "$chat" }?.id?.toLongOrNull()
}

/** The Telegram chat of [address], null when it names none. */
internal fun targetOf(address: ChatAddress): Target? {
    val chat = address.chat.toLongOrNull() ?: return null
    val thread = address.thread?.let { it.toLongOrNull() ?: return null }
    return Target(chat, thread)
}
