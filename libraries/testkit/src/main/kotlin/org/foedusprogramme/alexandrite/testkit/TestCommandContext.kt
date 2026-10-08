package org.foedusprogramme.alexandrite.testkit

import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.Markup
import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.chat.ChannelMessageRef
import org.foedusprogramme.alexandrite.sdk.chat.TurnInfo
import org.foedusprogramme.alexandrite.sdk.chat.TurnKind
import org.foedusprogramme.alexandrite.sdk.turn.CommandContext
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation

/** A [CommandContext] that records the notices its handler replies with, each in reply to [trigger]. */
public class TestCommandContext(
    override val turn: TurnInfo = testTurn(TurnKind.COMMAND),
    private val trigger: ChannelMessageRef? = null,
) : CommandContext {
    private val lock = Any()
    private val sent = mutableListOf<OutboundMessage>()
    private val deliveries = ArrayDeque<Delivery>()

    public val replies: List<OutboundMessage> get() = synchronized(lock) { sent.toList() }

    /** Queues the deliveries of the next replies. An unscripted reply reaches the chat as one message. */
    public fun scriptDeliveries(vararg deliveries: Delivery) {
        synchronized(lock) { this.deliveries.addAll(deliveries) }
    }

    override suspend fun reply(text: String, markup: Markup): Delivery = synchronized(lock) {
        sent += OutboundMessage.builder(text, MessageKind.NOTICE).markup(markup).replyTo(trigger).build()
        deliveries.removeFirstOrNull() ?: Delivery.Delivered(listOf(ChannelMessageRef(turn.chat, "reply-${sent.size}")))
    }
}

/** The context of [invocation], a COMMAND turn of its chat whose actor is its issuer. */
public fun testCommandContext(invocation: CommandInvocation): TestCommandContext =
    TestCommandContext(testTurn(TurnKind.COMMAND, invocation.chat) { actor(invocation.issuer) }, invocation.trigger)
