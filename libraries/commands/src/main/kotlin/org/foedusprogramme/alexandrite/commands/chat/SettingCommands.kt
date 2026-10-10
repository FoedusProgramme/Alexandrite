package org.foedusprogramme.alexandrite.commands.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.foedusprogramme.alexandrite.commands.support.RESET
import org.foedusprogramme.alexandrite.commands.support.Replies
import org.foedusprogramme.alexandrite.commands.support.TextKeys
import org.foedusprogramme.alexandrite.commands.support.commandWord
import org.foedusprogramme.alexandrite.commands.support.groupOf
import org.foedusprogramme.alexandrite.commands.support.isKeyword
import org.foedusprogramme.alexandrite.commands.support.languageInForce
import org.foedusprogramme.alexandrite.commands.support.refusing
import org.foedusprogramme.alexandrite.commands.support.replies
import org.foedusprogramme.alexandrite.commands.support.spec
import org.foedusprogramme.alexandrite.commands.support.updateSettings
import org.foedusprogramme.alexandrite.commands.support.words
import org.foedusprogramme.alexandrite.sdk.chat.LanguageTag
import org.foedusprogramme.alexandrite.sdk.di.Contribute
import org.foedusprogramme.alexandrite.sdk.di.Singleton
import org.foedusprogramme.alexandrite.sdk.i18n.Texts
import org.foedusprogramme.alexandrite.sdk.model.ModelEndpoint
import org.foedusprogramme.alexandrite.sdk.model.ModelInfo
import org.foedusprogramme.alexandrite.sdk.model.ModelProvider
import org.foedusprogramme.alexandrite.sdk.model.ReasoningEffort
import org.foedusprogramme.alexandrite.sdk.transcript.ModelRef
import org.foedusprogramme.alexandrite.sdk.turn.AgentControl
import org.foedusprogramme.alexandrite.sdk.turn.CommandContext
import org.foedusprogramme.alexandrite.sdk.turn.CommandHandler
import org.foedusprogramme.alexandrite.sdk.turn.CommandInvocation
import org.foedusprogramme.alexandrite.sdk.turn.CommandSpec
import org.slf4j.LoggerFactory
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/** `/model`, `/reasoning`, `/language` and `/settings`. */
@Singleton
@Contribute(CommandHandler::class)
internal class SettingCommands(
    private val control: AgentControl,
    private val providers: List<ModelProvider>,
    private val texts: Texts,
) : CommandHandler {
    override val commands: List<CommandSpec> = listOf(MODEL, REASONING, LANGUAGE, SETTINGS).map(texts::spec)

    override suspend fun handle(invocation: CommandInvocation, context: CommandContext) {
        val replies = texts.replies(context.turn.language)
        val argument = invocation.arguments.trim()
        val words = words(argument)
        when (val name = commandWord(invocation.name)) {
            MODEL -> when {
                argument.isEmpty() -> model(invocation, context, replies)
                argument.isKeyword(RESET) -> resetModel(invocation, context)
                '/' in argument -> setModel(argument, invocation, context, replies)
                words.size == 1 -> listModels(argument, invocation, context, replies)
                else -> context.reply(replies.usage(name))
            }

            REASONING -> when {
                words.isEmpty() -> reasoning(invocation, context, replies)
                words.size > 1 -> context.reply(replies.usage(name))
                words[0].isKeyword(RESET) -> resetReasoning(invocation, context)
                else -> setReasoning(words[0], invocation, context, replies)
            }

            LANGUAGE -> when {
                words.isEmpty() -> language(invocation, context, replies)
                words.size > 1 -> context.reply(replies.usage(name))
                words[0].isKeyword(RESET) -> resetLanguage(invocation, context)
                else -> setLanguage(words[0], invocation, context, replies)
            }

            SETTINGS -> when {
                words.isEmpty() -> settings(invocation, context, replies)
                else -> context.reply(replies.usage(name))
            }
        }
    }

    private suspend fun model(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val snapshot = context.refusing { control.settings(invocation.chat) } ?: return
        val model = replies.model(snapshot)
        val current = replies.text(TextKeys.MODEL_CURRENT, "model" to model.value, "source" to model.source)
        context.reply(current + "\n" + replies.text(TextKeys.MODEL_ENDPOINTS, "endpoints" to endpointIds(replies)))
    }

    private suspend fun listModels(
        endpointId: String,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val endpoint = providers.flatMap { it.endpoints }.firstOrNull { it.id.value == endpointId }
        if (endpoint == null) {
            val text = replies.text(
                TextKeys.MODEL_NO_ENDPOINT,
                "endpoint" to endpointId,
                "endpoints" to endpointIds(replies),
            )
            context.reply(text)
            return
        }
        val snapshot = context.refusing { control.settings(invocation.chat) } ?: return
        val models = listing(endpoint)
        if (models == null) {
            context.reply(replies.text(TextKeys.MODEL_LIST_FAILED, "endpoint" to endpoint.id))
            return
        }
        val ids = models.map { it.id }.distinct().sorted()
        if (ids.isEmpty()) {
            context.reply(replies.text(TextKeys.MODEL_LIST_EMPTY, "endpoint" to endpoint.id))
            return
        }
        val current = (snapshot.model ?: snapshot.defaults.model)?.takeIf { it.endpoint == endpoint.id }?.model
        val shown = ids.take(MAX_LISTED).map { id ->
            if (id == current) "$id ${replies.text(TextKeys.MODEL_CURRENT_MARK)}" else id
        }
        val lines = listOfNotNull(
            replies.text(TextKeys.MODEL_LIST, "endpoint" to endpoint.id, "models" to replies.list(shown)),
            (ids.size - MAX_LISTED).takeIf { it > 0 }?.let { replies.text(TextKeys.MODEL_LIST_MORE, "count" to it) },
        )
        context.reply(lines.joinToString("\n"))
    }

    /** The models of [endpoint], null when it does not list them in time. */
    private suspend fun listing(endpoint: ModelEndpoint): List<ModelInfo>? {
        val models = try {
            withTimeoutOrNull(LISTING_TIMEOUT) { endpoint.models() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Cannot list the models of endpoint '{}': {}", endpoint.id, e.toString())
            return null
        }
        if (models == null) logger.warn("Endpoint '{}' did not list its models within {}", endpoint.id, LISTING_TIMEOUT)
        return models
    }

    private suspend fun setModel(
        text: String,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val model = ModelRef.parseOrNull(text)
        if (model == null) {
            context.reply(replies.usage(MODEL))
            return
        }
        val snapshot = context.updateSettings(control, invocation) { model(model) } ?: return
        context.reply(texts.replies(snapshot.languageInForce).text(TextKeys.MODEL_SET, "model" to model))
    }

    private suspend fun resetModel(invocation: CommandInvocation, context: CommandContext) {
        val snapshot = context.updateSettings(control, invocation) { resetModel() } ?: return
        val replies = texts.replies(snapshot.languageInForce)
        val model = replies.model(snapshot)
        context.reply(replies.text(TextKeys.MODEL_RESET, "model" to model.value, "source" to model.source))
    }

    private suspend fun reasoning(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val snapshot = context.refusing { control.settings(invocation.chat) } ?: return
        val effort = replies.reasoning(snapshot)
        val text = replies.text(
            TextKeys.REASONING_CURRENT,
            "effort" to effort.value,
            "source" to effort.source,
            "efforts" to EFFORTS,
        )
        context.reply(text)
    }

    private suspend fun setReasoning(
        text: String,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val effort = ReasoningEffort.of(text.lowercase(Locale.ROOT))
        if (effort !in ReasoningEffort.entries) {
            context.reply(replies.text(TextKeys.REASONING_UNKNOWN, "text" to text, "efforts" to EFFORTS))
            return
        }
        val snapshot = context.updateSettings(control, invocation) { reasoning(effort) } ?: return
        context.reply(texts.replies(snapshot.languageInForce).text(TextKeys.REASONING_SET, "effort" to effort))
    }

    private suspend fun resetReasoning(invocation: CommandInvocation, context: CommandContext) {
        val snapshot = context.updateSettings(control, invocation) { resetReasoning() } ?: return
        val replies = texts.replies(snapshot.languageInForce)
        val effort = replies.reasoning(snapshot)
        context.reply(replies.text(TextKeys.REASONING_RESET, "effort" to effort.value, "source" to effort.source))
    }

    private suspend fun language(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val snapshot = context.refusing { control.settings(invocation.chat) } ?: return
        val language = replies.language(snapshot)
        val text = replies.text(TextKeys.LANGUAGE_CURRENT, "language" to language.value, "source" to language.source)
        context.reply(text)
    }

    private suspend fun setLanguage(
        text: String,
        invocation: CommandInvocation,
        context: CommandContext,
        replies: Replies,
    ) {
        val language = try {
            LanguageTag.of(text)
        } catch (e: IllegalArgumentException) {
            context.reply(replies.text(TextKeys.LANGUAGE_MALFORMED, "text" to text))
            return
        }
        val snapshot = context.updateSettings(control, invocation) { language(language) } ?: return
        context.reply(texts.replies(snapshot.languageInForce).text(TextKeys.LANGUAGE_SET, "language" to language))
    }

    private suspend fun resetLanguage(invocation: CommandInvocation, context: CommandContext) {
        val snapshot = context.updateSettings(control, invocation) { resetLanguage() } ?: return
        val replies = texts.replies(snapshot.languageInForce)
        val language = replies.language(snapshot)
        val text = replies.text(TextKeys.LANGUAGE_RESET, "language" to language.value, "source" to language.source)
        context.reply(text)
    }

    private suspend fun settings(invocation: CommandInvocation, context: CommandContext, replies: Replies) {
        val chat = invocation.chat
        val snapshot = context.refusing { control.settings(chat) } ?: return
        val agent = snapshot.agent
        val instance = chat.instance
        val summary = control.agents(instance).firstOrNull { it.id == agent }
        val group = control.groupOf(agent, chat)
        val model = replies.model(snapshot)
        val effort = replies.reasoning(snapshot)
        val language = replies.language(snapshot)
        val home = summary?.home
        val lines = listOf(
            replies.text(TextKeys.SETTINGS_TITLE, "chat" to chat),
            replies.text(
                TextKeys.SETTINGS_AGENT,
                "agent" to agent,
                "name" to (summary?.name ?: agent),
                "source" to replies.agentSource(snapshot, chat),
            ),
            replies.text(TextKeys.SETTINGS_MODEL, "model" to model.value, "source" to model.source),
            replies.text(TextKeys.SETTINGS_REASONING, "effort" to effort.value, "source" to effort.source),
            replies.text(TextKeys.SETTINGS_LANGUAGE, "language" to language.value, "source" to language.source),
            when {
                snapshot.home -> replies.text(TextKeys.SETTINGS_HOME_HERE, "agent" to agent, "instance" to instance)

                home != null -> replies.text(
                    TextKeys.SETTINGS_HOME_ELSEWHERE,
                    "agent" to agent,
                    "instance" to instance,
                    "home" to home,
                )

                else -> replies.text(TextKeys.SETTINGS_HOME_NONE, "agent" to agent, "instance" to instance)
            },
            if (group == null) {
                replies.text(TextKeys.SETTINGS_NOT_LINKED, "agent" to agent)
            } else {
                val others = (listOf(group.anchor) + group.members).filter { it != chat }
                replies.text(
                    TextKeys.SETTINGS_LINKED,
                    "agent" to agent,
                    "chats" to others.joinToString(", "),
                    "anchor" to group.anchor,
                )
            },
            replies.text(if (invocation.issuer.isAdmin) TextKeys.SETTINGS_ADMIN else TextKeys.SETTINGS_MEMBER),
        )
        context.reply(lines.joinToString("\n"))
    }

    private fun endpointIds(replies: Replies): String = providers.flatMap {
        it.endpoints
    }.joinToString(", ") { it.id.value }.ifEmpty { replies.text(TextKeys.VALUE_NONE) }

    private companion object {
        const val MODEL = "model"
        const val REASONING = "reasoning"
        const val LANGUAGE = "language"
        const val SETTINGS = "settings"
        const val MAX_LISTED = 40
        val LISTING_TIMEOUT = 10.seconds
        val EFFORTS = ReasoningEffort.entries.joinToString(", ")
        val logger = LoggerFactory.getLogger(SettingCommands::class.java)
    }
}
