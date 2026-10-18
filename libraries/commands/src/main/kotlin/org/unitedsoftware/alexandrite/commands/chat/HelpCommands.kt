package org.unitedsoftware.alexandrite.commands.chat

import org.slf4j.LoggerFactory
import org.unitedsoftware.alexandrite.commands.support.Replies
import org.unitedsoftware.alexandrite.commands.support.TextKeys
import org.unitedsoftware.alexandrite.commands.support.description
import org.unitedsoftware.alexandrite.commands.support.replies
import org.unitedsoftware.alexandrite.commands.support.spec
import org.unitedsoftware.alexandrite.sdk.channel.Channel
import org.unitedsoftware.alexandrite.sdk.channel.ChannelDirectory
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec
import kotlin.coroutines.cancellation.CancellationException

/** Every command handler of the runtime. */
@Singleton
internal class Handlers(val all: List<CommandHandler>)

/** `/help`, which lists the commands of every handler that the chat's channel lets the issuer run. */
@Singleton
@Contribute(CommandHandler::class)
internal class HelpCommands(
    private val handlers: Lazy<Handlers>,
    private val channels: ChannelDirectory,
    private val texts: Texts,
) : CommandHandler {
    override val commands: List<CommandSpec> = listOf(texts.spec(HELP))

    override fun description(command: String, language: LanguageTag?): String = texts.description(command, language)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val replies = texts.replies(context.turn.language)
        if (invocation.arguments.isNotBlank()) {
            context.reply(replies.usage(HELP))
            return
        }
        val channel = channels.channel(invocation.chat.instance)
        val claimed = handlers.value.all.flatMap { handler -> handler.commands.map { handler to it.name } }
        val items = claimed.sortedBy { it.second }.mapNotNull { (handler, name) ->
            item(invocation, channel, handler, name, context.turn.language, replies)
        }
        val text = if (items.isEmpty()) {
            replies.text(TextKeys.HELP_NONE)
        } else {
            replies.text(TextKeys.HELP_LIST, "commands" to items.joinToString("\n"))
        }
        context.reply(text, forModel = false)
    }

    /** The line of [name], null when the issuer may run it in no form. */
    private fun item(
        invocation: CommandInvocation,
        channel: Channel?,
        handler: CommandHandler,
        name: String,
        language: LanguageTag?,
        replies: Replies,
    ): String? {
        val bare = channel?.mayRun(invocation.chat, invocation.issuer, name, false) ?: true
        val withArguments = channel?.mayRun(invocation.chat, invocation.issuer, name, true) ?: true
        if (!bare && !withArguments) return null
        val key = if (withArguments) TextKeys.HELP_ITEM else TextKeys.HELP_ITEM_BARE
        return replies.text(key, "command" to name, "description" to described(handler, name, language))
    }

    private fun described(handler: CommandHandler, name: String, language: LanguageTag?): String = try {
        handler.description(name, language)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("Cannot describe /{} in {}: {}", name, language, e.toString())
        handler.commands.first { it.name == name }.description
    }

    private companion object {
        const val HELP = "help"
    }
}

private val logger = LoggerFactory.getLogger(HelpCommands::class.java)
