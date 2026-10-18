package org.unitedsoftware.alexandrite.commands.chat

import org.unitedsoftware.alexandrite.commands.support.Replies
import org.unitedsoftware.alexandrite.commands.support.TextKeys
import org.unitedsoftware.alexandrite.commands.support.commandWord
import org.unitedsoftware.alexandrite.commands.support.description
import org.unitedsoftware.alexandrite.commands.support.groupOf
import org.unitedsoftware.alexandrite.commands.support.refusing
import org.unitedsoftware.alexandrite.commands.support.replies
import org.unitedsoftware.alexandrite.commands.support.spec
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.turn.AgentControl
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import org.unitedsoftware.alexandrite.sdk.turn.rebuild

/** `/new` and `/cancel`. */
@Singleton
@Contribute(CommandHandler::class)
internal class ConversationCommands(private val control: AgentControl, private val texts: Texts) : CommandHandler {
    override val commands: List<CommandSpec> =
        listOf(texts.spec(NEW), texts.spec(CANCEL).rebuild { runsAlongside(true) })

    override fun description(command: String, language: LanguageTag?): String = texts.description(command, language)

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
