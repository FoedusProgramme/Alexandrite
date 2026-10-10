package org.foedusprogramme.alexandrite.commands.chat

import org.foedusprogramme.alexandrite.commands.support.Replies
import org.foedusprogramme.alexandrite.commands.support.TextKeys
import org.foedusprogramme.alexandrite.commands.support.commandWord
import org.foedusprogramme.alexandrite.commands.support.groupOf
import org.foedusprogramme.alexandrite.commands.support.refusing
import org.foedusprogramme.alexandrite.commands.support.replies
import org.foedusprogramme.alexandrite.commands.support.spec
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.CommandContext
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import org.foedusprogramme.alexandrite.sdk.turn.CommandSpec

/** `/new` and `/cancel`. */
@Singleton
@Contribute(CommandHandler::class)
internal class ConversationCommands(private val control: AgentControl, private val texts: Texts) : CommandHandler {
    override val commands: List<CommandSpec> = listOf(texts.spec(NEW), texts.spec(CANCEL))

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val replies = texts.replies(context.turn.language)
        val name = commandWord(invocation.name)
        when {
            invocation.arguments.isNotBlank() -> context.reply(replies.usage(name))
            name == NEW -> new(invocation, context, replies)
            name == CANCEL -> cancel(invocation, context, replies)
        }
    }

    private suspend fun new(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val chat = invocation.chat
        val agent = context.turn.agent
        context.refusing { control.newConversation(chat, invocation.issuer) } ?: return
        val group = control.groupOf(agent, chat)
        val text = if (group == null) {
            replies.text(TextKeys.NEW_DONE, "agent" to agent)
        } else {
            replies.text(TextKeys.NEW_DONE_LINKED, "agent" to agent, "count" to group.members.size)
        }
        context.reply(text)
    }

    private suspend fun cancel(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val cancelled = control.cancel(invocation.chat, invocation.issuer)
        context.reply(replies.text(if (cancelled) TextKeys.CANCEL_DONE else TextKeys.CANCEL_NONE))
    }

    private companion object {
        const val NEW = "new"
        const val CANCEL = "cancel"
    }
}
