package org.foedusprogramme.alexandrite.channel.telegram

import org.foedusprogramme.alexandrite.sdk.channel.Channel
import org.foedusprogramme.alexandrite.sdk.channel.ChannelCapabilities
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure
import org.foedusprogramme.alexandrite.sdk.channel.Markup
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.channel.ReplyEnd
import org.foedusprogramme.alexandrite.sdk.channel.ReplyRequest
import org.foedusprogramme.alexandrite.sdk.channel.ReplySink
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.di.ChannelInstanceScoped
import org.foedusprogramme.alexandrite.sdk.di.Contribute

/** The front end of one bot. Its output is a placeholder that delivers nothing until T2.6e. */
@ChannelInstanceScoped
@Contribute(Channel::class, name = "telegram")
internal class TelegramChannel(private val config: TelegramInstanceConfig) : Channel {
    override suspend fun capabilities(chat: ChatAddress): ChannelCapabilities = ChannelCapabilities.builder()
        .maxPartsPerReply(1)
        .streaming(config.streaming.enabled)
        .finalReplacesPreview(true)
        .proactive(true)
        .markups(setOf(Markup.PLAIN, Markup.MARKDOWN))
        .build()

    override suspend fun partsNeeded(chat: ChatAddress, text: String, markup: Markup): Int =
        maxOf(1, (text.length + MAX_TEXT - 1) / MAX_TEXT)

    override suspend fun openReply(request: ReplyRequest): ReplySink = Undelivered

    override suspend fun send(chat: ChatAddress, message: OutboundMessage): Delivery = NOT_DELIVERED

    private object Undelivered : ReplySink {
        override suspend fun preview(segment: Int, text: String) {}

        override suspend fun complete(message: OutboundMessage): Delivery = NOT_DELIVERED

        override suspend fun abandon(end: ReplyEnd) {}
    }

    private companion object {
        const val MAX_TEXT = 4096

        val NOT_DELIVERED = Delivery.NotDelivered(DeliveryFailure.TRANSIENT)
    }
}
