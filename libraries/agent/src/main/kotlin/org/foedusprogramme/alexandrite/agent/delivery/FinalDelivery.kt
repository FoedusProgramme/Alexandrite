package org.foedusprogramme.alexandrite.agent.delivery

import org.foedusprogramme.alexandrite.sdk.channel.ChannelCapabilities
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.DeliveryFailure
import org.foedusprogramme.alexandrite.sdk.channel.Markup
import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.ConversationId
import org.foedusprogramme.alexandrite.sdk.transcript.AssistantEntry
import org.foedusprogramme.alexandrite.sdk.transcript.TextPart
import kotlin.coroutines.cancellation.CancellationException

/** The text of [message] that a chat is shown. */
internal fun visibleText(message: AssistantEntry): String =
    message.parts.filterIsInstance<TextPart>().map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n")

/** The final reply [text] that replies to [trigger], in Markdown where [capabilities] take it. */
internal fun replyMessage(
    text: String,
    capabilities: ChannelCapabilities?,
    trigger: ChannelMessageRef?,
    conversation: ConversationId,
): OutboundMessage {
    val markdown = capabilities?.markups.orEmpty().contains(Markup.MARKDOWN)
    return OutboundMessage.builder(text, MessageKind.REPLY)
        .markup(if (markdown) Markup.MARKDOWN else Markup.PLAIN)
        .replyTo(trigger)
        .conversation(conversation)
        .build()
}

/** The delivery of [send], a failure of an unknown kind where it throws. */
internal suspend fun delivered(send: suspend () -> Delivery): Delivery = try {
    send()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Delivery.NotDelivered(DeliveryFailure.UNKNOWN, e.toString())
}
