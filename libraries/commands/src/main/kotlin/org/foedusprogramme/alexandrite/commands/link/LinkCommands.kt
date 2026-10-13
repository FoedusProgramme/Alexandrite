package org.foedusprogramme.alexandrite.commands.link

import kotlinx.coroutines.CancellationException
import org.foedusprogramme.alexandrite.commands.support.Replies
import org.foedusprogramme.alexandrite.commands.support.TextKeys
import org.foedusprogramme.alexandrite.commands.support.commandWord
import org.foedusprogramme.alexandrite.commands.support.groupOf
import org.foedusprogramme.alexandrite.commands.support.refusing
import org.foedusprogramme.alexandrite.commands.support.replies
import org.foedusprogramme.alexandrite.commands.support.spec
import org.foedusprogramme.alexandrite.commands.support.words
import org.foedusprogramme.alexandrite.internal.pairing.Redemption
import org.foedusprogramme.alexandrite.sdk.channel.ChannelDirectory
import org.foedusprogramme.alexandrite.sdk.channel.Delivery
import org.foedusprogramme.alexandrite.sdk.channel.Markup
import org.foedusprogramme.alexandrite.sdk.channel.MessageKind
import org.foedusprogramme.alexandrite.sdk.channel.OutboundMessage
import org.foedusprogramme.alexandrite.sdk.chat.ChatAddress
import org.foedusprogramme.alexandrite.sdk.chat.ChatSettings
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.CommandContext
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import org.foedusprogramme.alexandrite.sdk.turn.CommandSpec
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** `/link` and `/unlink`. */
@Singleton
@Contribute(CommandHandler::class)
internal class LinkCommands(
    private val control: AgentControl,
    private val settings: ChatSettings,
    private val channels: ChannelDirectory,
    private val codes: LinkCodes,
    private val texts: Texts,
    private val clock: Clock,
) : CommandHandler {
    override val commands: List<CommandSpec> = listOf(texts.spec(LINK), texts.spec(UNLINK))

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val replies = texts.replies(context.turn.language)
        val argument = invocation.arguments.trim()
        when (val name = commandWord(invocation.name)) {
            LINK -> when {
                argument.isEmpty() -> offer(invocation, context, replies)
                else -> redeem(argument, invocation, context, replies)
            }

            UNLINK -> {
                val words = words(argument)
                val target = words.singleOrNull()?.let(ChatAddress::parseOrNull)
                when {
                    words.isEmpty() -> unlink(null, invocation, context, replies)
                    target == null -> context.reply(replies.usage(name))
                    else -> unlink(target, invocation, context, replies)
                }
            }
        }
    }

    private suspend fun offer(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val chat = invocation.chat
        val agent = context.turn.agent
        val code = codes.offer(LinkOffer(agent, chat, invocation.issuer.address))
        val offer = replies.text(
            TextKeys.LINK_OFFER,
            "agent" to agent,
            "code" to code.text,
            "minutes" to minutesUntil(code.expiresAt),
        )
        val current = control.groupOf(agent, chat)?.let { group ->
            val others = (listOf(group.anchor) + group.members).filter { it != chat }
            replies.text(TextKeys.LINK_CURRENT, "chats" to others.joinToString(", ") { markdownLiteral("$it") })
        }
        context.reply(listOfNotNull(offer, current).joinToString("\n\n"), Markup.MARKDOWN, forModel = false)
    }

    private suspend fun redeem(
        input: String,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        when (val redemption = codes.redeem(input, invocation.chat)) {
            is Redemption.Redeemed -> link(redemption.payload, invocation, context, replies)

            is Redemption.Rejected ->
                context.reply(replies.text(TextKeys.LINK_REJECTED, "tries" to redemption.triesLeft))

            is Redemption.Locked ->
                context.reply(replies.text(TextKeys.LINK_LOCKED, "minutes" to minutesUntil(redemption.until)))

            Redemption.OwnCode -> context.reply(replies.text(TextKeys.LINK_OWN_CODE))

            Redemption.Malformed -> context.reply(replies.text(TextKeys.LINK_MALFORMED))
        }
    }

    private suspend fun link(
        offer: LinkOffer,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val chat = invocation.chat
        context.refusing { control.link(offer.agent, offer.anchor, chat, invocation.issuer) } ?: return
        logger.info(
            "{} is linked to {} at agent '{}' with a code that {} offered",
            chat,
            offer.anchor,
            offer.agent,
            offer.by,
        )
        val current = context.turn.agent
        val lines = mutableListOf(replies.text(TextKeys.LINK_LINKED, "agent" to offer.agent, "anchor" to offer.anchor))
        if (current != offer.agent) {
            lines += replies.text(TextKeys.LINK_OTHER_AGENT, "current" to current, "agent" to offer.agent)
        }
        context.reply(lines.joinToString("\n"))
        tellAnchor(offer, chat)
    }

    /** Tells the anchor of [offer] that [member] joined. */
    private suspend fun tellAnchor(offer: LinkOffer, member: ChatAddress) {
        val anchor = offer.anchor
        val delivery = try {
            val text = texts.text(
                TextKeys.LINK_ANCHOR_NOTICE,
                settings.language(anchor),
                "chat" to member,
                "agent" to offer.agent,
            )
            channels.channel(anchor.instance)?.send(anchor, OutboundMessage.builder(text, MessageKind.NOTICE).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Cannot tell {} that {} is linked to it: {}", anchor, member, e.toString())
            return
        }
        if (delivery !is Delivery.Delivered) {
            logger.warn(
                "Cannot tell {} that {} is linked to it: {}",
                anchor,
                member,
                delivery ?: "its channel is closed",
            )
        }
    }

    private suspend fun unlink(
        target: ChatAddress?,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val chat = invocation.chat
        val agent = context.turn.agent
        if (target != null) {
            val group = control.groupOf(agent, chat)
            if (group == null) {
                context.reply(replies.text(TextKeys.UNLINK_NOT_LINKED, "agent" to agent))
                return
            }
            if (target != group.anchor && target !in group.members) {
                context.reply(replies.text(TextKeys.UNLINK_NOT_IN_GROUP, "chat" to target))
                return
            }
        }
        val member = target ?: chat
        val revocation = context.refusing { control.unlink(agent, member, invocation.issuer) } ?: return
        val key = when {
            revocation.declared && revocation.removed -> TextKeys.UNLINK_DECLARED_REMOVED
            revocation.declared -> TextKeys.UNLINK_DECLARED
            revocation.removed -> TextKeys.UNLINK_DONE
            else -> TextKeys.UNLINK_NOT_LINKED
        }
        context.reply(replies.text(key, "chat" to member, "agent" to agent))
    }

    /** The whole minutes from now until [instant], at least 1. */
    private fun minutesUntil(instant: Instant): Long {
        val millis = Duration.between(clock.instant(), instant).toMillis()
        return maxOf(1, Math.ceilDiv(millis, MILLIS_PER_MINUTE))
    }

    private companion object {
        const val LINK = "link"
        const val UNLINK = "unlink"
        const val MILLIS_PER_MINUTE = 60_000L
        val logger = LoggerFactory.getLogger(LinkCommands::class.java)
    }
}

/** [text] with the characters that start inline Markdown escaped. */
private fun markdownLiteral(text: String): String = text.replace(MARKDOWN_SIGNS) { "\\${it.value}" }

private val MARKDOWN_SIGNS = Regex("[\\\\`*_~\\[\\]<&]")
