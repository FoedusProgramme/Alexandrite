package org.foedusprogramme.alexandrite.sdk.channel

import dev.drewhamilton.poko.Poko
import kotlinx.serialization.Serializable
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.di.ContributedSpi

/** A channel instance's front end, contributed by exactly one channel-instance-scoped class of a channel plugin. */
@ContributedSpi
public interface Channel {
    /** What the channel can do in [chat]. */
    public suspend fun capabilities(chat: ChatAddress): ChannelCapabilities

    /** How many platform messages [text] in [markup] takes in [chat]. */
    public suspend fun partsNeeded(chat: ChatAddress, text: String, markup: Markup): Int

    /** Opens the reply of a turn that delivers to its chat, once the agent dequeues the turn. */
    public suspend fun openReply(request: ReplyRequest): ReplySink

    /** Sends [message] to [chat] outside any turn's reply. */
    public suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery
}

/** The reply of one turn, which delivers until the channel instance stops. */
public interface ReplySink {
    /** Shows [text], the whole text of [segment] so far, where a higher segment freezes the earlier ones. */
    public suspend fun preview(segment: Int, text: String)

    /** Delivers the final reply, in place of the previews where the channel can. */
    public suspend fun complete(message: OutboundMessage): Delivery

    /** Ends the reply without a final message and leaves the previews as they are. */
    public suspend fun abandon(end: ReplyEnd)
}

/** What a channel needs to open a turn's reply. */
@Poko
public class ReplyRequest(
    public val turn: TurnInfo,
    /** The message or interaction the turn answers, null when it answers none. */
    public val trigger: ChannelMessageRef?,
)

/** Why a reply ended without a final message. */
@JvmInline
@Serializable
public value class ReplyEnd internal constructor(public val id: String) {
    override fun toString(): String = id

    public companion object {
        public val CANCELLED: ReplyEnd = ReplyEnd("cancelled")

        public val FAILED: ReplyEnd = ReplyEnd("failed")

        /** The runtime is stopping. */
        public val SHUTDOWN: ReplyEnd = ReplyEnd("shutdown")
    }
}
