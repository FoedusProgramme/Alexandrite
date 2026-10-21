package org.unitedsoftware.alexandrite.commands.chat

import org.unitedsoftware.alexandrite.commands.support.Replies
import org.unitedsoftware.alexandrite.commands.support.TextKeys
import org.unitedsoftware.alexandrite.commands.support.commandWord
import org.unitedsoftware.alexandrite.commands.support.description
import org.unitedsoftware.alexandrite.commands.support.groupOf
import org.unitedsoftware.alexandrite.commands.support.refusing
import org.unitedsoftware.alexandrite.commands.support.replies
import org.unitedsoftware.alexandrite.commands.support.spec
import org.unitedsoftware.alexandrite.commands.support.words
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.turn.ChatLinks
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import org.unitedsoftware.alexandrite.sdk.turn.ConversationControl
import org.unitedsoftware.alexandrite.sdk.turn.TurnControl
import org.unitedsoftware.alexandrite.sdk.turn.rebuild

/** `/new` and `/cancel`. */
@Singleton
@Contribute(CommandHandler::class)
internal class ConversationCommands(
    private val turns: TurnControl,
    private val conversations: ConversationControl,
    private val links: ChatLinks,
    private val texts: Texts,
) : CommandHandler {
    override val commands: List<CommandSpec> =
        listOf(texts.spec(NEW), texts.spec(CANCEL).rebuild { runsAlongside(true) })

    override fun description(command: String, language: LanguageTag?): String = texts.description(command, language)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val replies = texts.replies(context.turn.language)
        val name = commandWord(invocation.name)
        val words = words(invocation.arguments)
        when {
            name == CANCEL && words.size <= 1 -> cancel(words.singleOrNull(), invocation, context, replies)
            words.isNotEmpty() -> context.reply(replies.usage(name))
            name == NEW -> new(invocation, context, replies)
        }
    }

    private suspend fun new(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val chat = invocation.chat
        val agent = context.turn.agent
        context.refusing { conversations.newConversation(chat, invocation.issuer) } ?: return
        val group = links.groupOf(agent, chat)
        val text = if (group == null) {
            replies.text(TextKeys.NEW_DONE, "agent" to agent)
        } else {
            replies.text(TextKeys.NEW_DONE_LINKED, "agent" to agent, "count" to group.members.size)
        }
        context.reply(text)
    }

    private suspend fun cancel(
        word: String?,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        if (word == null) {
            val cancelled = turns.cancel(invocation.chat, invocation.issuer)
            context.reply(replies.text(if (cancelled) TextKeys.CANCEL_DONE else TextKeys.CANCEL_NONE))
            return
        }
        val agent = try {
            AgentId(word)
        } catch (e: IllegalArgumentException) {
            context.reply(replies.text(TextKeys.AGENT_MALFORMED, "text" to word))
            return
        }
        val cancelled = turns.cancel(invocation.chat, invocation.issuer, agent)
        val key = if (cancelled) TextKeys.CANCEL_AGENT_DONE else TextKeys.CANCEL_AGENT_NONE
        context.reply(replies.text(key, "agent" to agent))
    }

    private companion object {
        const val NEW = "new"
        const val CANCEL = "cancel"
    }
}
