package org.foedusprogramme.alexandrite.channel.onebot.channel

import org.foedusprogramme.alexandrite.channel.onebot.api.OneBotApi
import org.foedusprogramme.alexandrite.channel.onebot.config.OneBotInstanceConfig
import org.foedusprogramme.alexandrite.channel.onebot.mapping.OneBotMessages
import org.foedusprogramme.alexandrite.sdk.channel.ApprovalAnswer
import org.foedusprogramme.alexandrite.sdk.channel.ApprovalRequest
import org.foedusprogramme.alexandrite.sdk.channel.Channel
import org.foedusprogramme.alexandrite.sdk.channel.ChannelCapabilities
import org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.Markup
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.channel.ReplyEnd
import org.foedusprogramme.alexandrite.sdk.channel.ReplyRequest
import org.foedusprogramme.alexandrite.sdk.channel.ReplySink
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.turn.TurnSubmitter

/**
 * The OneBot front end of one channel instance.
 *
 * A message the implementation reported becomes an [IncomingMessage] and goes to the agent through the
 * [TurnSubmitter], and what the agent answers goes back as segments over the actions of the standard. Which
 * transport carries either is [OneBotLink]'s business, not this class's.
 */
@ChannelInstanceScoped
@Contribute(Channel::class, name = "onebot")
internal class OneBotChannel(
    private val instance: ChannelInstance,
    private val config: OneBotInstanceConfig,
    private val sender: OneBotSender,
    /** The approvals this instance asks its admins about. */
    private val approvals: OneBotApprovals,
    /** The agent, null while the runtime has none. */
    private val submitter: TurnSubmitter?,
) : Channel {
    override suspend fun capabilities(chat: ChatAddress): ChannelCapabilities = OneBotMessages.capabilities()

    override suspend fun partsNeeded(chat: ChatAddress, text: String, markup: Markup): Int =
        if (text.isEmpty()) 0 else 1

    override suspend fun openReply(request: ReplyRequest): ReplySink = Sink(this, request)

    override suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery = sender.send(chat, message)

    override suspend fun askApproval(request: ApprovalRequest): ApprovalAnswer = approvals.ask(request)

    /** The reply of one turn, which delivers the final message of the agent. */
    private class Sink(private val channel: OneBotChannel, private val request: ReplyRequest) : ReplySink {
        override suspend fun preview(segment: Int, text: String) = Unit

        override suspend fun complete(message: OutboundMessage): Delivery =
            channel.sender.send(request.turn.chat, message)

        override suspend fun abandon(end: ReplyEnd) = Unit
    }
}
