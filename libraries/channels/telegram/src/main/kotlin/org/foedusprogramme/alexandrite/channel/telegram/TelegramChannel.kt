package org.foedusprogramme.alexandrite.channel.telegram

import org.foedusprogramme.alexandrite.channel.telegram.approval.ApprovalDesk
import org.foedusprogramme.alexandrite.channel.telegram.format.formatted
import org.foedusprogramme.alexandrite.channel.telegram.format.parts
import org.foedusprogramme.alexandrite.channel.telegram.intake.AccessGate
import org.foedusprogramme.alexandrite.channel.telegram.intake.CommandRules
import org.foedusprogramme.alexandrite.channel.telegram.reply.Deliveries
import org.foedusprogramme.alexandrite.channel.telegram.reply.MAX_PARTS_PER_REPLY
import org.foedusprogramme.alexandrite.channel.telegram.reply.MAX_UPLOAD_BYTES
import org.foedusprogramme.alexandrite.sdk.channel.ApprovalAnswer
import org.foedusprogramme.alexandrite.sdk.channel.ApprovalRequest
import org.foedusprogramme.alexandrite.sdk.channel.Channel
import org.foedusprogramme.alexandrite.sdk.channel.ChannelCapabilities
import org.foedusprogramme.alexandrite.sdk.channel.ChannelInstance
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.Markup
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.channel.ReplyRequest
import org.foedusprogramme.alexandrite.sdk.channel.ReplySink
import org.foedusprogramme.alexandrite.sdk.channel.TurnEventKind
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatUser
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Lifecycle

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

    override suspend fun openReply(request: ReplyRequest): ReplySink = deliveries.openReply(request)

    override suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery = deliveries.send(chat, message)

    override suspend fun askApproval(request: ApprovalRequest): ApprovalAnswer = when {
        deliveries.stopped -> ApprovalAnswer.Unavailable("the bot is stopping")

        request.turn.chat.instance != instance.id ->
            ApprovalAnswer.Unavailable("the chat ${request.turn.chat} belongs to another channel instance")

        else -> approvals.ask(request)
    }

    override fun mayRun(chat: ChatAddress, issuer: ChatUser, command: String, withArguments: Boolean): Boolean =
        rules.mayRun(chat, issuer.isAdmin, command, withArguments)

    override fun onStop() {
        deliveries.stop()
    }
}
