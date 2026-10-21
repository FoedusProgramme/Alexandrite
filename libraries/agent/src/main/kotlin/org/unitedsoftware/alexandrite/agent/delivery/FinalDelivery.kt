package org.unitedsoftware.alexandrite.agent.delivery

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.unitedsoftware.alexandrite.sdk.channel.ChannelCapabilities
import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.DeliveryFailure
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.ConversationId
import org.unitedsoftware.alexandrite.sdk.transcript.AssistantEntry
import org.unitedsoftware.alexandrite.sdk.transcript.TextPart

/** The text of [message] that a chat is shown. */
internal fun visibleText(message: AssistantEntry): String =
    visibleText(message.parts.filterIsInstance<TextPart>().map { it.text })

/** What a chat is shown of the text parts [texts]. */
internal fun visibleText(texts: Collection<CharSequence>): String =
    texts.filter { it.isNotBlank() }.joinToString("\n\n")

/** The final reply [text] that replies to [trigger], in Markdown where [capabilities] take it. */
internal fun replyMessage(
    text: String,
    capabilities: ChannelCapabilities?,
    trigger: ChannelMessageRef?,
    conversation: ConversationId,
    vouched: Boolean,
): OutboundMessage {
    val markdown = capabilities?.markups.orEmpty().contains(Markup.MARKDOWN)
    return OutboundMessage.builder(text, MessageKind.REPLY)
        .markup(if (markdown) Markup.MARKDOWN else Markup.PLAIN)
        .replyTo(trigger)
        .conversation(conversation)
        .vouched(vouched)
        .build()
}

/** The delivery of [send], a failure of an unknown kind where it throws. */
internal suspend fun delivered(send: suspend () -> Delivery): Delivery = try {
    send()
} catch (e: Throwable) {
    if (e is VirtualMachineError) throw e
    currentCoroutineContext().ensureActive()
    Delivery.NotDelivered(DeliveryFailure.UNKNOWN, e.toString())
}
