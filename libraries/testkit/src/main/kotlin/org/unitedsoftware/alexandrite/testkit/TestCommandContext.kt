package org.unitedsoftware.alexandrite.testkit

import org.unitedsoftware.alexandrite.sdk.channel.Delivery
import org.unitedsoftware.alexandrite.sdk.channel.Markup
import org.unitedsoftware.alexandrite.sdk.channel.MessageKind
import org.unitedsoftware.alexandrite.sdk.channel.OutboundMessage
import org.unitedsoftware.alexandrite.sdk.chat.ChannelMessageRef
import org.unitedsoftware.alexandrite.sdk.chat.TurnInfo
import org.unitedsoftware.alexandrite.sdk.chat.TurnKind
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.testkit.plugin.ScriptedDeliveries

/** A [CommandContext] that records the notices its handler replies with, each in reply to [trigger]. */
public class TestCommandContext(
    override val turn: TurnInfo = testTurn(TurnKind.COMMAND),
    private val trigger: ChannelMessageRef? = null,
) : CommandContext {
    private val lock = Any()
    private val sent = mutableListOf<OutboundMessage>()
    private val visibility = mutableListOf<Boolean>()
    private val deliveries = ScriptedDeliveries()

    public val replies: List<OutboundMessage> get() = synchronized(lock) { sent.toList() }

    /** Whether each of [replies] was sent for the model to see, in the same order. */
    public val repliesForModel: List<Boolean> get() = synchronized(lock) { visibility.toList() }

    /** Queues the deliveries of the next replies. An unscripted reply reaches the chat as one message. */
    public fun scriptDeliveries(vararg deliveries: Delivery) {
        synchronized(lock) { this.deliveries.script(deliveries) }
    }

    override suspend fun reply(text: String, markup: Markup, forModel: Boolean): Delivery = synchronized(lock) {
        sent += OutboundMessage.builder(text, MessageKind.NOTICE).markup(markup).replyTo(trigger).build()
        visibility += forModel
        deliveries.next(turn.chat) ?: Delivery.Delivered(listOf(ChannelMessageRef(turn.chat, "reply-${sent.size}")))
    }
}

/** The context of [invocation], a COMMAND turn of its chat whose actor is its issuer. */
public fun testCommandContext(invocation: CommandInvocation): TestCommandContext =
    TestCommandContext(testTurn(TurnKind.COMMAND, invocation.chat) { actor(invocation.issuer) }, invocation.trigger)
