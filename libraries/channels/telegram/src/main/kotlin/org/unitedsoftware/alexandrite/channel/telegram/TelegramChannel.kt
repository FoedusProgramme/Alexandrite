package org.unitedsoftware.alexandrite.channel.telegram

import org.unitedsoftware.alexandrite.channel.telegram.approval.ApprovalDesk
import org.unitedsoftware.alexandrite.channel.telegram.format.formatted
import org.unitedsoftware.alexandrite.channel.telegram.format.parts
import org.unitedsoftware.alexandrite.channel.telegram.intake.AccessGate
import org.unitedsoftware.alexandrite.channel.telegram.intake.ChatTitles
import org.unitedsoftware.alexandrite.channel.telegram.intake.CommandRules
import org.unitedsoftware.alexandrite.channel.telegram.reply.Deliveries
import org.unitedsoftware.alexandrite.channel.telegram.reply.MAX_PARTS_PER_REPLY
import org.unitedsoftware.alexandrite.channel.telegram.reply.MAX_UPLOAD_BYTES
import org.unitedsoftware.alexandrite.channel.telegram.tools.TurnMessages
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalAnswer
import org.unitedsoftware.alexandrite.sdk.channel.ApprovalRequest
import org.unitedsoftware.alexandrite.sdk.channel.Channel
import org.unitedsoftware.alexandrite.sdk.channel.ChannelCapabilities
import org.unitedsoftware.alexandrite.sdk.channel.ChannelInstance
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.channel.ReplyRequest
import org.unitedsoftware.alexandrite.sdk.channel.ReplySink
import org.unitedsoftware.alexandrite.sdk.channel.TrustOffer
import org.unitedsoftware.alexandrite.sdk.channel.TurnEventKind
import org.unitedsoftware.alexandrite.sdk.chat.ChatAddress
import org.unitedsoftware.alexandrite.sdk.chat.ChatUser
import org.unitedsoftware.alexandrite.sdk.chat.UserAddress
import org.unitedsoftware.alexandrite.sdk.di.ChannelInstanceScoped
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Lifecycle

/** The front end of one bot, which delivers nothing to a chat that is not paired, nor once the instance stops. */
@ChannelInstanceScoped
@Contribute(Channel::class, name = "telegram")
internal class TelegramChannel(
    private val instance: ChannelInstance,
    private val config: TelegramInstanceConfig,
    private val gate: AccessGate,
    private val deliveries: Deliveries,
    private val approvals: ApprovalDesk,
    private val rules: CommandRules,
    private val titles: ChatTitles,
    private val turns: TurnMessages,
) : Channel,
    Lifecycle {
    override suspend fun capabilities(chat: ChatAddress): ChannelCapabilities = ChannelCapabilities.builder()
        .maxPartsPerReply(MAX_PARTS_PER_REPLY)
        .streaming(config.streaming.enabled)
        .finalReplacesPreview(true)
        .proactive(gate.isPaired(chat))
        .markups(setOf(Markup.PLAIN, Markup.MARKDOWN))
        .events(setOf(TurnEventKind.TOOL_CALL_STARTED, TurnEventKind.TOOL_CALL_UPDATED))
        .files(true)
        .maxFileBytes(MAX_UPLOAD_BYTES)
        .build()

    override suspend fun partsNeeded(chat: ChatAddress, text: String, markup: Markup): Int =
        formatted(text, markup).parts().size

    override suspend fun openReply(request: ReplyRequest): ReplySink {
        if (request.run == null) request.trigger?.let { turns.opened(request.turn.id, it) }
        return deliveries.openReply(request)
    }

    override suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery = deliveries.send(chat, message)

    override suspend fun title(chat: ChatAddress): String? = titles.title(chat)

    override suspend fun askApproval(request: ApprovalRequest): ApprovalAnswer = when {
        deliveries.stopped -> ApprovalAnswer.Unavailable("the bot is stopping")

        request.turn.chat.instance != instance.id ->
            ApprovalAnswer.Unavailable("the chat ${request.turn.chat} belongs to another channel instance")

        else -> approvals.ask(request)
    }

    override suspend fun offerTrust(offer: TrustOffer): ApprovalAnswer = when {
        deliveries.stopped -> ApprovalAnswer.Unavailable("the bot is stopping")

        offer.turn.chat.instance != instance.id && offer.turn.chat.instance != ChatAddress.AGENTS ->
            ApprovalAnswer.Unavailable("the chat ${offer.turn.chat} belongs to another channel instance")

        else -> approvals.offer(offer)
    }

    override fun mayRun(chat: ChatAddress, issuer: ChatUser, command: String, withArguments: Boolean): Boolean =
        rules.mayRun(chat, issuer.isOperator, command, withArguments)

    /** Whether [user] is one of the instance's configured admins, who are operators in every chat of the bot. */
    override suspend fun isOperator(chat: ChatAddress, user: UserAddress): Boolean =
        user.instance == instance.id && user.user.toLongOrNull() in config.adminUserIds

    override fun onStop() {
        deliveries.stop()
    }
}
