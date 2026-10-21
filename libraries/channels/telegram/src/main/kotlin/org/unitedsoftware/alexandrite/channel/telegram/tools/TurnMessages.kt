package org.unitedsoftware.alexandrite.channel.telegram.tools

import org.unitedsoftware.alexandrite.channel.telegram.api.Message
import org.unitedsoftware.alexandrite.internal.collection.BoundedMap
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.TurnId
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped

/** The message that started each recent turn, and the message it replies to, for the tools that act on them. */
@ChannelInstanceScoped
internal class TurnMessages {
    private val turns = BoundedMap<TurnId, TurnMessage>(KEPT)
    private val submitted = BoundedMap<ChannelMessageRef, Message>(KEPT)

    /** Notes [message], the first of the messages that [trigger] stands for, before its turn is submitted. */
    fun submitted(trigger: ChannelMessageRef, message: Message) {
        synchronized(turns) { submitted[trigger] = message }
    }

    /** Notes that [turn] opened its reply to [trigger], before it may call a tool. */
    fun opened(turn: TurnId, trigger: ChannelMessageRef) {
        val message = synchronized(turns) { submitted[trigger] } ?: return
        note(turn, message)
    }

    /** Notes [message], the first of the messages that started [turn]. */
    fun note(turn: TurnId, message: Message) {
        val replied = message.replyToMessage?.messageId
            ?.takeUnless { message.isTopicMessage && it == message.messageThreadId }
        synchronized(turns) { turns[turn] = TurnMessage(message.chat.id, message.messageId, replied) }
    }

    /** The messages of [turn], null when it started from no message this bot read or long ago. */
    fun of(turn: TurnId): TurnMessage? = synchronized(turns) { turns[turn] }

    private companion object {
        const val KEPT = 1024
    }
}

/** The message [message] of the Telegram chat [chat] that started a turn, and the message [replied] it replies to. */
internal class TurnMessage(val chat: Long, val message: Long, val replied: Long?)
