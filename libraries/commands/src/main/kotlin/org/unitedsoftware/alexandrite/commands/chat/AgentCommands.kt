package org.unitedsoftware.alexandrite.commands.chat

import org.unitedsoftware.alexandrite.commands.support.RESET
import org.unitedsoftware.alexandrite.commands.support.Replies
import org.unitedsoftware.alexandrite.commands.support.TextKeys
import org.unitedsoftware.alexandrite.commands.support.commandWord
import org.unitedsoftware.alexandrite.commands.support.description
import org.unitedsoftware.alexandrite.commands.support.isKeyword
import org.unitedsoftware.alexandrite.commands.support.refusing
import org.unitedsoftware.alexandrite.commands.support.replies
import org.unitedsoftware.alexandrite.commands.support.spec
import org.unitedsoftware.alexandrite.commands.support.updateSettings
import org.unitedsoftware.alexandrite.commands.support.words
import org.unitedsoftware.alexandrite.sdk.chat.AgentId
import org.unitedsoftware.alexandrite.sdk.chat.ChannelInstanceId
import org.unitedsoftware.alexandrite.sdk.chat.LanguageTag
import org.unitedsoftware.alexandrite.sdk.di.Contribute
import org.unitedsoftware.alexandrite.sdk.di.Singleton
import org.unitedsoftware.alexandrite.sdk.i18n.Texts
import org.unitedsoftware.alexandrite.sdk.turn.AgentCatalog
import org.unitedsoftware.alexandrite.sdk.turn.AgentSummary
import org.unitedsoftware.alexandrite.sdk.turn.ChatSettings
import org.unitedsoftware.alexandrite.sdk.turn.CommandContext
import org.unitedsoftware.alexandrite.sdk.turn.CommandHandler
import org.unitedsoftware.alexandrite.sdk.turn.CommandInvocation
import org.unitedsoftware.alexandrite.sdk.turn.CommandSpec

/** `/agent` and `/home`. */
@Singleton
@Contribute(CommandHandler::class)
internal class AgentCommands(
    private val settings: ChatSettings,
    private val catalog: AgentCatalog,
    private val texts: Texts,
) : CommandHandler {
    override val commands: List<CommandSpec> = listOf(texts.spec(AGENT), texts.spec(HOME))

    override fun description(command: String, language: LanguageTag?): String = texts.description(command, language)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val replies = texts.replies(context.turn.language)
        val words = words(invocation.arguments)
        when (val name = commandWord(invocation.name)) {
            AGENT -> when {
                words.isEmpty() -> agents(invocation, context, replies)
                words.size > 1 -> context.reply(replies.usage(name))
                words[0].isKeyword(RESET) -> resetAgent(invocation, context)
                else -> switchAgent(words[0], invocation, context, replies)
            }

            HOME -> when {
                words.isEmpty() -> home(invocation, context, replies)
                words.size == 1 && words[0].isKeyword(OFF) -> homeOff(invocation, context, replies)
                words.size == 1 && words[0].isKeyword(RESET) -> resetHome(invocation, context, replies)
                else -> context.reply(replies.usage(name))
            }
        }
    }

    private suspend fun agents(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val chat = invocation.chat
        val snapshot = context.refusing { settings.snapshot(chat) } ?: return
        val agents = catalog.agents(chat.instance)
        val current = replies.text(
            TextKeys.AGENT_CURRENT,
            "agent" to snapshot.agent,
            "name" to (agents.firstOrNull { it.id == snapshot.agent }?.name ?: snapshot.agent),
            "source" to replies.agentSource(snapshot, chat),
        )
        val choices = agents.map { agent ->
            val description = agent.description
            val choice = if (description == null) {
                replies.text(TextKeys.AGENT_CHOICE_PLAIN, "id" to agent.id, "name" to agent.name)
            } else {
                replies.text(
                    TextKeys.AGENT_CHOICE,
                    "id" to agent.id,
                    "name" to agent.name,
                    "description" to description,
                )
            }
            if (agent.default) "$choice ${replies.text(TextKeys.AGENT_DEFAULT_MARK)}" else choice
        }
        context.reply(listOf(current, replies.text(TextKeys.AGENT_CHOICES), replies.list(choices)).joinToString("\n"))
    }

    private suspend fun switchAgent(
        word: String,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val agent = try {
            AgentId(word)
        } catch (e: IllegalArgumentException) {
            context.reply(replies.text(TextKeys.AGENT_MALFORMED, "text" to word))
            return
        }
        val snapshot = context.updateSettings(settings, invocation) { agent(agent) } ?: return
        val instance = invocation.chat.instance
        val text = texts.replies(snapshot.effectiveLanguage)
            .text(TextKeys.AGENT_SWITCHED, "agent" to snapshot.agent, "name" to name(instance, snapshot.agent))
        context.reply(text)
    }

    private suspend fun resetAgent(invocation: CommandInvocation, context: CommandContext) {
        val snapshot = context.updateSettings(settings, invocation) { resetAgent() } ?: return
        val replies = texts.replies(snapshot.effectiveLanguage)
        val source = replies.agentSource(snapshot, invocation.chat)
        context.reply(replies.text(TextKeys.AGENT_RESET, "agent" to snapshot.agent, "source" to source))
    }

    private suspend fun home(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val instance = invocation.chat.instance
        val current = context.refusing { settings.snapshot(invocation.chat) } ?: return
        if (current.home) {
            context.reply(replies.text(TextKeys.HOME_ALREADY, "agent" to current.agent, "instance" to instance))
            return
        }
        val snapshot = context.updateSettings(settings, invocation) { home() } ?: return
        val text = texts.replies(snapshot.effectiveLanguage)
            .text(TextKeys.HOME_SET, "agent" to snapshot.agent, "instance" to instance)
        context.reply(text)
    }

    private suspend fun homeOff(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val current = context.refusing { settings.snapshot(invocation.chat) } ?: return
        if (!current.home) {
            context.reply(replies.text(TextKeys.HOME_NOT_HOME, "agent" to current.agent))
            return
        }
        val snapshot = context.updateSettings(settings, invocation) { noHome() } ?: return
        context.reply(texts.replies(snapshot.effectiveLanguage).text(TextKeys.HOME_CLEARED, "agent" to current.agent))
    }

    private suspend fun resetHome(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val instance = invocation.chat.instance
        val current = context.refusing { settings.snapshot(invocation.chat) } ?: return
        if (summary(instance, current.agent)?.homeSetAt == null) {
            val text = replies.text(TextKeys.HOME_NOTHING_TO_RESET, "agent" to current.agent, "instance" to instance)
            context.reply(text)
            return
        }
        val snapshot = context.updateSettings(settings, invocation) { resetHome() } ?: return
        val home = summary(instance, snapshot.agent)?.home
        val reset = texts.replies(snapshot.effectiveLanguage)
        val text = if (home == null) {
            reset.text(TextKeys.HOME_RESET_NONE, "agent" to snapshot.agent, "instance" to instance)
        } else {
            reset.text(TextKeys.HOME_RESET, "agent" to snapshot.agent, "instance" to instance, "home" to home)
        }
        context.reply(text)
    }

    private fun summary(instance: ChannelInstanceId, agent: AgentId): AgentSummary? =
        catalog.agents(instance).firstOrNull { it.id == agent }

    /** How [agent] calls itself on [instance], its id when it does not serve the instance. */
    private fun name(instance: ChannelInstanceId, agent: AgentId): String =
        summary(instance, agent)?.name ?: agent.value

    private companion object {
        const val AGENT = "agent"
        const val HOME = "home"
        const val OFF = "off"
    }
}
